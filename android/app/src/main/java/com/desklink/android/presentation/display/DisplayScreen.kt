package com.desklink.android.presentation.display

import android.annotation.SuppressLint
import android.app.Activity
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
import com.desklink.android.domain.model.ConnectionState
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

    // Floating controls handle: always visible, draggable anywhere, position persisted, and
    // dimmed while idle. Using a fixed on-screen handle (not a multi-finger gesture) avoids
    // clashing with the tablet's system gestures.
    var controlsExpanded by remember { mutableStateOf(false) }

    // Single-finger long-press = right-click. Timing runs on the composition scope.
    val longPressDetector = remember { LongPressDetector() }
    val gestureScope = rememberCoroutineScope()

    // Local pinch-zoom of the mirror view (client-side; Mac not involved).
    val zoom = remember { ViewZoom() }
    // The transformed SurfaceView, so the zoom transform can be applied to it directly.
    var surfaceView by remember { mutableStateOf<SurfaceView?>(null) }

    // Single-finger press is debounced: on DOWN the cursor tracks (hover) but the press is
    // withheld briefly; if a second finger lands first the press is cancelled — so two
    // fingers NEVER emit a click. Committed after PRESS_DEBOUNCE_MS if still one finger.
    var awaitingPress by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longPressConsumed by remember { mutableStateOf(false) }
    var gestureMulti by remember { mutableStateOf(false) }
    var pressJob by remember { mutableStateOf<Job?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var lastNx by remember { mutableFloatStateOf(0f) }
    var lastNy by remember { mutableFloatStateOf(0f) }

    // Two-finger pinch/pan tracking (screen px; the touch layer is untransformed). Each
    // two-finger gesture locks into one intent (zoom OR scroll/pan) once it passes a slop,
    // so the two never fight. `start*` anchor the lock decision; `prev*` give per-frame deltas.
    var twoFingerMode by remember { mutableStateOf(TwoFingerMode.Undecided) }
    var startDist by remember { mutableFloatStateOf(0f) }
    var startCx by remember { mutableFloatStateOf(0f) }
    var startCy by remember { mutableFloatStateOf(0f) }
    var prevPinchDist by remember { mutableFloatStateOf(0f) }
    var prevCx by remember { mutableFloatStateOf(0f) }
    var prevCy by remember { mutableFloatStateOf(0f) }

    // Inertial scroll (two-finger, only while not zoomed).
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
        val state = connectionState
        if (state is ConnectionState.Error || state is ConnectionState.Disconnected) {
            leaveToConnect()
        }
    }

    val vsyncRenderer = remember { VsyncRenderer(renderTick = { viewModel.renderFrame() }) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.let {
            val windowInsetsController =
                WindowCompat.getInsetsController(it.window, it.window.decorView)
            windowInsetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
            it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        vsyncRenderer.start()
        onDispose {
            vsyncRenderer.stop()
            activity?.let {
                val windowInsetsController =
                    WindowCompat.getInsetsController(it.window, it.window.decorView)
                windowInsetsController.show(WindowInsetsCompat.Type.systemBars())
                it.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    DisposableEffect(Unit) {
        MirrorConnectionService.start(context)
        onDispose { MirrorConnectionService.stop(context) }
    }

    BackHandler { leaveToConnect() }

    // Applies the current local zoom to the rendered SurfaceView (pivot top-left).
    fun applyZoom() {
        surfaceView?.let {
            it.scaleX = zoom.scale
            it.scaleY = zoom.scale
            it.translationX = zoom.offsetX
            it.translationY = zoom.offsetY
        }
    }

    // Core gesture router. `viewW`/`viewH` are the untransformed touch layer size (px), so
    // getX/getY are screen-space; touch coordinates are inverse-mapped through the zoom.
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

                val nx = zoom.contentNormalizedX(event.getX(0), w)
                val ny = zoom.contentNormalizedY(event.getY(0), h)
                lastNx = nx; lastNy = ny
                // Position the cursor immediately (hover); withhold the press.
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
                        val rx = zoom.contentNormalizedX(longPressDetector.anchorX, w)
                        val ry = zoom.contentNormalizedY(longPressDetector.anchorY, h)
                        // The press has committed by now (debounce << long-press); release
                        // it then right-click, in order.
                        viewModel.sendLongPressRightClick(rx, ry)
                        longPressConsumed = true
                        pressed = false
                    }
                }
            }

            PointerPhase.POINTER_DOWN -> {
                // A second/third finger: this can never be a single-finger touch.
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
                // Seed on the next two-finger MOVE (start positions), and decide the mode then.
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
                        // (Re)seed after two fingers land or a finger lifts/rejoins.
                        startDist = curDist; startCx = curCx; startCy = curCy
                        prevPinchDist = curDist; prevCx = curCx; prevCy = curCy
                        twoFingerMode = TwoFingerMode.Undecided
                    } else {
                        // Lock the gesture to zoom OR scroll/pan once it passes the slop —
                        // whichever moved more from the start (spread vs. translate).
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
                                    zoom.pan(dCx, dCy, w, h) // pan the magnified view
                                    applyZoom()
                                } else {
                                    // Not zoomed: two-finger drag scrolls the Mac; track velocity for fling.
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
                    val nx = zoom.contentNormalizedX(event.getX(0), w)
                    val ny = zoom.contentNormalizedY(event.getY(0), h)
                    lastNx = nx; lastNy = ny
                    // Hover (before commit) or drag (after) — the Mac classifies by down-state.
                    if (!longPressConsumed) viewModel.sendPointerMove(nx, ny)
                }
            }

            PointerPhase.POINTER_UP -> {
                // A finger lifted mid-gesture; re-seed the pinch trackers and re-decide the
                // mode on the next MOVE (pointer indices shift). Keep suppressing single-finger.
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
                            // Quick tap before the debounce elapsed -> a click.
                            viewModel.sendPointerDown(lastNx, lastNy)
                            viewModel.sendPointerUp(lastNx, lastNy)
                        }
                    }
                }

                // Fling the Mac scroll if released while still moving (only when not zoomed).
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
                    // Pivot at top-left so the zoom transform matches ViewZoom's math
                    // (screen = content * scale + offset).
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
                // Touches land on the (untransformed) container, so getX/getY are
                // screen-space regardless of the SurfaceView's zoom transform.
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
            initialFractionX = viewModel.controlHandleFractionX(),
            initialFractionY = viewModel.controlHandleFractionY(),
            onToggle = { controlsExpanded = !controlsExpanded },
            onMoved = { fx, fy -> viewModel.saveControlHandleFraction(fx, fy) },
            onSettings = {
                if (!exiting) {
                    exiting = true
                    viewModel.teardown()
                    onOpenSettings()
                }
            },
            onDisconnect = { leaveToConnect() },
        )

        val reconnecting = connectionState is ConnectionState.Reconnecting ||
            connectionState is ConnectionState.Connecting ||
            connectionState is ConnectionState.Handshaking ||
            connectionState is ConnectionState.Negotiating
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

/** Idle time before the always-visible controls handle dims. */
private const val CONTROLS_IDLE_DIM_MS = 4_000L

/** Opacity the controls handle fades to while idle (brightens on touch / when expanded). */
private const val CONTROLS_DIM_ALPHA = 0.4f

/** Diameter of the draggable controls handle (matches GlassCircleButton). */
private val HANDLE_SIZE = 48.dp

/** Max gap between the last scroll movement and the finger lift for a fling to start. */
private const val FLING_MAX_RELEASE_GAP_MS = 60L

/** How long the first finger's press is withheld to see if a second finger joins (so a
 *  two-finger gesture never emits a click). Short enough to feel responsive. */
private const val PRESS_DEBOUNCE_MS = 60L

/** Guards against a divide-by-~zero when a two-finger gesture starts with the fingers
 *  almost coincident. */
private const val MIN_PINCH_DIST_PX = 1f

/** Movement (finger spread or centroid travel, px) before a two-finger gesture commits to
 *  zoom or scroll/pan. Keeps the two from firing together on incidental jitter. */
private const val GESTURE_SLOP_PX = 24f

/** A two-finger gesture is locked to one intent after it passes [GESTURE_SLOP_PX]. */
private enum class TwoFingerMode { Undecided, Zoom, ScrollPan }

private fun pointerDistance(event: MotionEvent): Float =
    hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

private fun pointerCentroidX(event: MotionEvent): Float = (event.getX(0) + event.getX(1)) / 2f

private fun pointerCentroidY(event: MotionEvent): Float = (event.getY(0) + event.getY(1)) / 2f

/** Maps an Android MotionEvent to the detector's coarse phase (null = ignored). */
private fun MotionEvent.toPointerPhase(): PointerPhase? = when (actionMasked) {
    MotionEvent.ACTION_DOWN -> PointerPhase.DOWN
    MotionEvent.ACTION_POINTER_DOWN -> PointerPhase.POINTER_DOWN
    MotionEvent.ACTION_MOVE -> PointerPhase.MOVE
    MotionEvent.ACTION_POINTER_UP -> PointerPhase.POINTER_UP
    MotionEvent.ACTION_UP -> PointerPhase.UP
    MotionEvent.ACTION_CANCEL -> PointerPhase.CANCEL
    else -> null
}

/** Cubic-bezier(.4,0,.2,1) — the "standard" easing used throughout the handoff. */
private val StandardEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * Always-visible controls: a draggable handle whose position is restored from and saved to
 * [onMoved], dimmed while idle. Uses a fixed on-screen affordance (not a multi-finger
 * gesture) so it never clashes with the tablet's own system gestures.
 */
@Composable
private fun DraggableControls(
    expanded: Boolean,
    initialFractionX: Float,
    initialFractionY: Float,
    onToggle: () -> Unit,
    onMoved: (fractionX: Float, fractionY: Float) -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val handlePx = with(density) { HANDLE_SIZE.toPx() }
        // Draggable range for the handle's top-left, keeping it fully on-screen.
        val rangeX = (constraints.maxWidth - handlePx).coerceAtLeast(1f)
        val rangeY = (constraints.maxHeight - handlePx).coerceAtLeast(1f)

        var hx by remember { mutableFloatStateOf((initialFractionX * rangeX).coerceIn(0f, rangeX)) }
        var hy by remember { mutableFloatStateOf((initialFractionY * rangeY).coerceIn(0f, rangeY)) }

        // Dim while idle; brighten on interaction; stay bright while expanded.
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
                        onDragEnd = {
                            onMoved((hx / rangeX).coerceIn(0f, 1f), (hy / rangeY).coerceIn(0f, 1f))
                        },
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
    // Group = two 48dp buttons + a 12dp gap.
    val groupWidthPx = with(density) { (48.dp * 2 + 12.dp).toPx() }

    // The handle is anchored at (0,0); the expandable group is drawn beside it (toward
    // screen center) without shifting the handle, so dragging stays predictable.
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
