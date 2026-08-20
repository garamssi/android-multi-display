package com.desklink.android.presentation.display

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.desklink.android.data.codec.VsyncRenderer
import com.desklink.android.presentation.components.AppGlyph
import com.desklink.android.presentation.components.GlassCircleButton
import com.desklink.android.presentation.components.IndeterminateBar
import com.desklink.android.presentation.components.SpinnerRing
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans
import com.desklink.android.service.MirrorConnectionService
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility")
@Composable
fun DisplayScreen(
    onDisconnected: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DisplayViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    var controlsExpanded by remember { mutableStateOf(false) }

    val longPressDetector = remember { LongPressDetector() }
    val gestureScope = rememberCoroutineScope()

    // isFlipped applies a lossless 180 turn on the tablet (view + touch inversion); it is never sent to the Mac.
    val rotation = remember { viewModel.displayRotation() }
    val flipped = rotation.isFlipped

    val zoom = remember { ViewZoom() }
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    // Single-finger press is debounced: withheld on DOWN, cancelled if a second finger lands, so two fingers never emit a click.
    var awaitingPress by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longPressConsumed by remember { mutableStateOf(false) }
    var gestureMulti by remember { mutableStateOf(false) }
    var pressJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var lastNx by remember { mutableFloatStateOf(0f) }
    var lastNy by remember { mutableFloatStateOf(0f) }

    var twoFingerMode by remember { mutableStateOf(TwoFingerMode.Undecided) }
    var startDist by remember { mutableFloatStateOf(0f) }
    var startCx by remember { mutableFloatStateOf(0f) }
    var startCy by remember { mutableFloatStateOf(0f) }
    var prevPinchDist by remember { mutableFloatStateOf(0f) }
    var prevCx by remember { mutableFloatStateOf(0f) }
    var prevCy by remember { mutableFloatStateOf(0f) }

    val velocityTracker = remember { VelocityTracker2D() }
    val flingDecay = remember { FlingDecay() }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var scrolling by remember { mutableStateOf(false) }
    var lastScrollTimeMs by remember { mutableLongStateOf(0L) }

    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()
    var exiting by remember { mutableStateOf(false) }

    val leaveToConnect = {
        if (!exiting) {
            exiting = true
            viewModel.teardown()
            onDisconnected()
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState.isTerminal) {
            leaveToConnect()
        }
    }

    val vsyncRenderer = remember { VsyncRenderer(renderTick = { viewModel.renderFrame() }) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val previousOrientation = activity?.requestedOrientation
        activity?.let {
            val windowInsetsController =
                WindowCompat.getInsetsController(it.window, it.window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.requestedOrientation = if (rotation.isPortrait) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
        }
        vsyncRenderer.start()
        onDispose {
            vsyncRenderer.stop()
            activity?.let {
                val windowInsetsController =
                    WindowCompat.getInsetsController(it.window, it.window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                it.requestedOrientation =
                    previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }

    DisposableEffect(Unit) {
        MirrorConnectionService.start(context)
        onDispose { MirrorConnectionService.stop(context) }
    }

    BackHandler { leaveToConnect() }

    // 180 flip = negative scale on BOTH axes (not a single-axis mirror) with top-left pivot: screen = -scale*content + (viewSize - offset), which ViewZoom inverts when flipped so taps stay accurate.
    fun applyZoom() {
        surfaceView?.let {
            val w = it.width.toFloat()
            val h = it.height.toFloat()
            if (flipped) {
                it.scaleX = -zoom.scale
                it.scaleY = -zoom.scale
                it.translationX = w - zoom.offsetX
                it.translationY = h - zoom.offsetY
            } else {
                it.scaleX = zoom.scale
                it.scaleY = zoom.scale
                it.translationX = zoom.offsetX
                it.translationY = zoom.offsetY
            }
        }
    }

    // viewW/viewH are the untransformed touch-layer size (px); getX/getY are screen-space, inverse-mapped through the zoom.
    fun handleTouch(event: MotionEvent, viewW: Int, viewH: Int) {
        val phase = event.toPointerPhase() ?: return
        val count = event.pointerCount
        val w = viewW.toFloat()
        val h = viewH.toFloat()

        longPressDetector.onEvent(phase, count, event.eventTime, event.getX(0), event.getY(0))

        when (phase) {
            PointerPhase.DOWN -> {
                gestureMulti = false
                longPressConsumed = false
                flingJob?.cancel(); flingJob = null
                velocityTracker.reset(); scrolling = false; lastScrollTimeMs = 0L

                val nx = zoom.contentNormalizedX(event.getX(0), w, flipped)
                val ny = zoom.contentNormalizedY(event.getY(0), h, flipped)
                lastNx = nx; lastNy = ny
                viewModel.sendPointerMove(nx, ny)
                awaitingPress = true; pressed = false

                pressJob?.cancel()
                pressJob = gestureScope.launch {
                    delay(PRESS_DEBOUNCE_MS)
                    if (awaitingPress && !gestureMulti) {
                        viewModel.sendPointerDown(lastNx, lastNy)
                        pressed = true; awaitingPress = false
                    }
                }

                longPressJob?.cancel()
                longPressJob = gestureScope.launch {
                    delay(longPressDetector.longPressThresholdMs)
                    if (longPressDetector.fireIfElapsed(SystemClock.uptimeMillis())) {
                        val rx = zoom.contentNormalizedX(longPressDetector.anchorX, w, flipped)
                        val ry = zoom.contentNormalizedY(longPressDetector.anchorY, h, flipped)
                        viewModel.sendLongPressRightClick(rx, ry)
                        longPressConsumed = true
                        pressed = false
                    }
                }
            }

            PointerPhase.POINTER_DOWN -> {
                longPressJob?.cancel()
                pressJob?.cancel()
                if (awaitingPress) {
                    awaitingPress = false // press never committed -> no click leaks
                } else if (pressed) {
                    viewModel.cancelTouch(lastNx, lastNy) // release the committed press
                    pressed = false
                }
                gestureMulti = true
                flingJob?.cancel(); flingJob = null
                prevPinchDist = 0f
                twoFingerMode = TwoFingerMode.Undecided
                if (count >= 3) scrolling = false
            }

            PointerPhase.MOVE -> when {
                gestureMulti && count == 2 -> {
                    val curDist = pointerDistance(event)
                    val curCx = pointerCentroidX(event)
                    val curCy = pointerCentroidY(event)
                    if (prevPinchDist <= MIN_PINCH_DIST_PX) {
                        startDist = curDist; startCx = curCx; startCy = curCy
                        prevPinchDist = curDist; prevCx = curCx; prevCy = curCy
                        twoFingerMode = TwoFingerMode.Undecided
                    } else {
                        if (twoFingerMode == TwoFingerMode.Undecided) {
                            val spread = abs(curDist - startDist)
                            val travel = hypot(curCx - startCx, curCy - startCy)
                            if (max(spread, travel) > GESTURE_SLOP_PX) {
                                twoFingerMode =
                                    if (spread >= travel) TwoFingerMode.Zoom else TwoFingerMode.ScrollPan
                            }
                        }
                        when (twoFingerMode) {
                            TwoFingerMode.Zoom -> {
                                zoom.pinch(curDist / prevPinchDist, curCx, curCy, w, h)
                                applyZoom()
                            }

                            TwoFingerMode.ScrollPan -> {
                                val dCx = curCx - prevCx
                                val dCy = curCy - prevCy
                                if (zoom.isZoomed) {
                                    // Zoom transform is pre-flip, so under a 180 flip the pan delta must be negated to follow the finger.
                                    val panX = if (flipped) -dCx else dCx
                                    val panY = if (flipped) -dCy else dCy
                                    zoom.pan(panX, panY, w, h) // pan the magnified view
                                    applyZoom()
                                } else {
                                    val dxN = dCx / w
                                    val dyN = dCy / h
                                    viewModel.sendScroll(dxN, dyN)
                                    val dt = if (lastScrollTimeMs == 0L) flingDecay.frameMs
                                        else (event.eventTime - lastScrollTimeMs).coerceAtLeast(1L)
                                    velocityTracker.track(dxN, dyN, dt)
                                    lastScrollTimeMs = event.eventTime
                                    scrolling = true
                                }
                            }

                            TwoFingerMode.Undecided -> Unit // wait for the slop before acting
                        }
                        prevPinchDist = curDist; prevCx = curCx; prevCy = curCy
                    }
                }

                !gestureMulti && count == 1 -> {
                    val nx = zoom.contentNormalizedX(event.getX(0), w, flipped)
                    val ny = zoom.contentNormalizedY(event.getY(0), h, flipped)
                    lastNx = nx; lastNy = ny
                    if (!longPressConsumed) viewModel.sendPointerMove(nx, ny)
                }
            }

            PointerPhase.POINTER_UP -> {
                longPressJob?.cancel()
                prevPinchDist = 0f
                twoFingerMode = TwoFingerMode.Undecided
            }

            PointerPhase.UP -> {
                longPressJob?.cancel(); pressJob?.cancel()

                if (!gestureMulti) {
                    when {
                        pressed && !longPressConsumed -> viewModel.sendPointerUp(lastNx, lastNy)
                        awaitingPress && !longPressConsumed -> {
                            viewModel.sendPointerDown(lastNx, lastNy)
                            viewModel.sendPointerUp(lastNx, lastNy)
                        }
                    }
                }

                val sinceLastScroll =
                    if (lastScrollTimeMs == 0L) Long.MAX_VALUE else event.eventTime - lastScrollTimeMs
                if (scrolling && !zoom.isZoomed &&
                    sinceLastScroll <= FLING_MAX_RELEASE_GAP_MS &&
                    flingDecay.isActive(velocityTracker.velocityX, velocityTracker.velocityY)
                ) {
                    var vx = velocityTracker.velocityX
                    var vy = velocityTracker.velocityY
                    flingJob?.cancel()
                    flingJob = gestureScope.launch {
                        while (flingDecay.isActive(vx, vy)) {
                            val (dx, dy) = flingDecay.step(vx, vy)
                            viewModel.sendScroll(dx, dy)
                            val decayed = flingDecay.decay(vx, vy)
                            vx = decayed.first; vy = decayed.second
                            delay(flingDecay.frameMs)
                        }
                    }
                }

                awaitingPress = false; pressed = false; gestureMulti = false
                scrolling = false; prevPinchDist = 0f; longPressConsumed = false
            }

            PointerPhase.CANCEL -> {
                longPressJob?.cancel(); pressJob?.cancel()
                if (!gestureMulti && pressed && !longPressConsumed) viewModel.sendPointerUp(lastNx, lastNy)
                awaitingPress = false; pressed = false; gestureMulti = false
                scrolling = false; prevPinchDist = 0f; longPressConsumed = false
                velocityTracker.reset()
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val container = FrameLayout(ctx)
                val surface = SurfaceView(ctx).apply {
                    // Pivot at top-left so the zoom transform matches ViewZoom's math (screen = content * scale + offset).
                    pivotX = 0f
                    pivotY = 0f
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            viewModel.onSurfaceAvailable(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            viewModel.onSurfaceAvailable(holder.surface)
                            applyZoom()
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })
                }
                container.addView(
                    surface,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT,
                    ),
                )
                // Touches land on the untransformed container, so getX/getY stay screen-space regardless of the SurfaceView's zoom/flip.
                container.setOnTouchListener { v, event ->
                    handleTouch(event, v.width, v.height)
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.ACTION_POINTER_DOWN,
                        MotionEvent.ACTION_MOVE,
                        MotionEvent.ACTION_UP,
                        MotionEvent.ACTION_POINTER_UP,
                        MotionEvent.ACTION_CANCEL -> true

                        else -> false
                    }
                }
                container.keepScreenOn = true
                surfaceView = surface
                container
            },
        )

        DraggableControls(
            expanded = controlsExpanded,
            initialFractionX = HANDLE_INITIAL_FRACTION_X,
            initialFractionY = HANDLE_INITIAL_FRACTION_Y,
            onToggle = { controlsExpanded = !controlsExpanded },
            onSettings = {
                if (!exiting) {
                    exiting = true
                    viewModel.teardown()
                    onOpenSettings()
                }
            },
            onDisconnect = { leaveToConnect() },
        )

        val reconnecting = connectionState.isInProgress
        if (reconnecting && !exiting) {
            ReconnectingOverlay(modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ReconnectingOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SpinnerRing(size = 64.dp) {
                AppGlyph(
                    size = 44.dp,
                    cornerRadius = DeskLinkTokens.RadiusGlyphSmall,
                    iconSize = 22.dp,
                    elevated = false,
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Reconnecting…",
                color = DeskLinkTokens.TextPrimary,
                fontFamily = PlexSans,
                fontSize = 18.sp,
                fontWeight = FontWeight.W600,
            )
            Spacer(Modifier.height(16.dp))
            IndeterminateBar()
        }
    }
}

private const val CONTROLS_IDLE_DIM_MS = 4_000L

private const val CONTROLS_DIM_ALPHA = 0.65f

private val HANDLE_SIZE = 48.dp

private const val HANDLE_INITIAL_FRACTION_X = 0.5f
private const val HANDLE_INITIAL_FRACTION_Y = 0.9f

private const val FLING_MAX_RELEASE_GAP_MS = 60L

private const val PRESS_DEBOUNCE_MS = 60L

// Guards divide-by-~zero when a two-finger gesture starts with the fingers almost coincident.
private const val MIN_PINCH_DIST_PX = 1f

private const val GESTURE_SLOP_PX = 24f

private enum class TwoFingerMode { Undecided, Zoom, ScrollPan }

private fun pointerDistance(event: MotionEvent): Float =
    hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

private fun pointerCentroidX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f

private fun pointerCentroidY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f

private fun MotionEvent.toPointerPhase(): PointerPhase? = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
    MotionEvent.ACTION_POINTER_DOWN -> PointerPhase.POINTER_DOWN
    MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
    MotionEvent.ACTION_POINTER_UP -> PointerPhase.POINTER_UP
    MotionEvent.ACTION_UP -> PointerPhase.UP
    MotionEvent.ACTION_CANCEL -> PointerPhase.CANCEL
    else -> null
}

private val StandardEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
private fun DraggableControls(
    expanded: Boolean,
    initialFractionX: Float,
    initialFractionY: Float,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val handlePx = with(density) { HANDLE_SIZE.toPx() }
        val rangeX = (constraints.maxWidth - handlePx).coerceAtLeast(1f)
        val rangeY = (constraints.maxHeight - handlePx).coerceAtLeast(1f)

        var hx by remember { mutableFloatStateOf((initialFractionX * rangeX).coerceIn(0f, rangeX)) }
        var hy by remember { mutableFloatStateOf((initialFractionY * rangeY).coerceIn(0f, rangeY)) }

        var interaction by remember { mutableIntStateOf(0) }
        var idle by remember { mutableStateOf(false) }
        LaunchedEffect(interaction, expanded) {
            idle = false
            if (!expanded) {
                delay(CONTROLS_IDLE_DIM_MS)
                idle = true
            }
        }
        val dim by animateFloatAsState(
            targetValue = if (idle && !expanded) CONTROLS_DIM_ALPHA else 1f,
            animationSpec = tween(durationMillis = 300),
            label = "controlsDim",
        )

        Box(
            modifier = Modifier
                .offset { IntOffset(hx.coerceIn(0f, rangeX).roundToInt(), hy.coerceIn(0f, rangeY).roundToInt()) }
                .alpha(dim),
        ) {
            FloatingControl(
                expanded = expanded,
                expandLeft = hx > rangeX / 2f, // expand toward screen center so buttons stay on-screen
                onToggle = { interaction++; onToggle() },
                onSettings = onSettings,
                onDisconnect = onDisconnect,
                handleModifier = Modifier.pointerInput(rangeX, rangeY) {
                    detectDragGestures(
                        onDragStart = { interaction++ },
                    ) { change, drag ->
                        change.consume()
                        hx = (hx + drag.x).coerceIn(0f, rangeX)
                        hy = (hy + drag.y).coerceIn(0f, rangeY)
                    }
                },
            )
        }
    }
}

@Composable
private fun FloatingControl(
    expanded: Boolean,
    expandLeft: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    handleModifier: Modifier = Modifier,
) {
    val expand by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "overlayExpand",
    )
    val handleRotation by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "handleRotation",
    )
    val handleFill by animateColorAsState(
        targetValue = if (expanded) DeskLinkTokens.HandleOpenFill else DeskLinkTokens.GlassFill,
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "handleFill",
    )
    val handleBorder by animateColorAsState(
        targetValue = if (expanded) DeskLinkTokens.HandleOpenBorder else DeskLinkTokens.Border14,
        animationSpec = tween(durationMillis = 300, easing = StandardEasing),
        label = "handleBorder",
    )

    val density = LocalDensity.current
    val handlePx = with(density) { HANDLE_SIZE.toPx() }
    val gapPx = with(density) { 12.dp.toPx() }
    val groupWidthPx = with(density) { (48.dp * 2 + 12.dp).toPx() }

    Box {
        if (expand > 0f) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .offset {
                        val x = if (expandLeft) -(groupWidthPx + gapPx) else (handlePx + gapPx)
                        IntOffset(x.roundToInt(), 0)
                    }
                    .graphicsLayer { alpha = expand },
            ) {
                GlassCircleButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onSettings,
                    enabled = expanded,
                )
                GlassCircleButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Disconnect",
                    onClick = onDisconnect,
                    enabled = expanded,
                )
            }
        }

        GlassCircleButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "Controls",
            onClick = onToggle,
            containerColor = handleFill,
            borderColor = handleBorder,
            iconTint = if (expanded) Color.White else DeskLinkTokens.TextPrimary,
            modifier = handleModifier.graphicsLayer { rotationZ = handleRotation },
        )
    }
}
