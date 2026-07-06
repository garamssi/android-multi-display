package com.desklink.android.presentation.display

import android.annotation.SuppressLint
import android.app.Activity
import android.os.SystemClock
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.desklink.android.data.input.TouchCollector
import com.desklink.android.domain.model.ConnectionState
import com.desklink.android.presentation.components.AppGlyph
import com.desklink.android.presentation.components.GlassCircleButton
import com.desklink.android.presentation.components.IndeterminateBar
import com.desklink.android.presentation.components.SpinnerRing
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans
import com.desklink.android.service.MirrorConnectionService

@Composable
fun DisplayScreen(
    onDisconnected: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: DisplayViewModel = hiltViewModel(),
) {
    val context = LocalContext.current

    // The floating control is hidden by default so it never blocks the mirror. A
    // two-finger tap reveals it (a single tap can't be used — it is forwarded to the
    // Mac as a remote touch); it auto-hides after a few idle seconds.
    var controlsShown by remember { mutableStateOf(false) }
    // Whether the Settings / Disconnect buttons are expanded next to the handle.
    var controlsExpanded by remember { mutableStateOf(false) }
    // Bumped on every interaction to restart the auto-hide countdown.
    var interactionNonce by remember { mutableIntStateOf(0) }

    val revealControls = {
        controlsShown = true
        interactionNonce++
    }

    // Auto-hide the control after an idle period. Re-armed whenever the control is
    // shown or the user interacts (interactionNonce changes).
    LaunchedEffect(controlsShown, interactionNonce) {
        if (controlsShown) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsShown = false
            controlsExpanded = false
        }
    }

    // Two-finger tap = "reveal controls". Multi-touch gestures are reserved for the
    // app and not forwarded to the Mac; single-finger touches drive the remote.
    val twoFingerDetector = remember { TwoFingerTapDetector() }

    // Single-finger long-press = right-click. Timing runs on the composition scope; the
    // detector stays pure. `longPressConsumed` swallows the rest of the gesture after a
    // right-click fires so the trailing MOVE/UP don't move or re-press the Mac cursor.
    val longPressDetector = remember { LongPressDetector() }
    val gestureScope = rememberCoroutineScope()
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longPressConsumed by remember { mutableStateOf(false) }

    // Inertial scroll: track the two-finger scroll velocity and, on release, keep
    // emitting decaying SCROLL deltas so the content glides like a real tablet.
    val velocityTracker = remember { VelocityTracker2D() }
    val flingDecay = remember { FlingDecay() }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var scrolling by remember { mutableStateOf(false) }
    var lastScrollTimeMs by remember { mutableLongStateOf(0L) }

    // Shared control-channel state. Drives the reconnecting overlay and the automatic
    // return to Connect when the link is terminally lost.
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    // Guards the one-time exit so manual disconnect and the state-driven exit (or the
    // Disconnected emitted by teardown itself) can't navigate twice.
    var exiting by remember { mutableStateOf(false) }

    // Single exit path: tear down the pipeline and leave to the Connect screen.
    val leaveToConnect = {
        if (!exiting) {
            exiting = true
            viewModel.teardown()
            onDisconnected()
        }
    }

    // Auto-terminate mirroring when the connection is terminally lost (Mac stopped,
    // USB pulled, or reconnect attempts exhausted) instead of freezing on the last
    // frame. Transient Reconnecting/Connecting states keep the overlay up instead.
    LaunchedEffect(connectionState) {
        val state = connectionState
        if (state is ConnectionState.Error || state is ConnectionState.Disconnected) {
            leaveToConnect()
        }
    }

    // Vsync renderer drives the decoder's render loop via the view model.
    val vsyncRenderer = remember { VsyncRenderer(renderTick = { viewModel.renderFrame() }) }

    // Immersive fullscreen mode + renderer lifecycle.
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

    // Keep the connection alive while this session is active — including when the app
    // is minimized (the composition is retained on background, so onDispose runs only
    // when we actually LEAVE the Display screen, not on minimize). This stops the OS
    // from freezing the process and dropping the keep-alive.
    DisposableEffect(Unit) {
        MirrorConnectionService.start(context)
        onDispose { MirrorConnectionService.stop(context) }
    }

    // Hardware back: tear down decoder/sockets, then leave the screen.
    BackHandler { leaveToConnect() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                @SuppressLint("ClickableViewAccessibility")
                SurfaceView(ctx).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // Surface ready — hand it to the decoder pipeline.
                            viewModel.onSurfaceAvailable(holder.surface)
                        }

                        override fun surfaceChanged(
                            holder: SurfaceHolder,
                            format: Int,
                            width: Int,
                            height: Int,
                        ) {
                            // Surface (re)created at a known size; ensure the decoder
                            // has the current surface.
                            viewModel.onSurfaceAvailable(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            viewModel.onSurfaceDestroyed()
                        }
                    })

                    // Touch handling. Multi-finger gestures are intercepted for the app
                    // (two-finger tap reveals the control); single-finger touches are
                    // forwarded to the Mac as the remote cursor.
                    setOnTouchListener { view, event ->
                        val phase = event.toPointerPhase()
                        if (phase != null) {
                            // Long-press -> right-click. Feed the detector, then schedule a
                            // fire check one threshold after DOWN; cancel it as soon as the
                            // gesture can't be a long-press (lift or second finger).
                            longPressDetector.onEvent(
                                phase = phase,
                                pointerCount = event.pointerCount,
                                eventTimeMs = event.eventTime,
                                x = event.getX(0),
                                y = event.getY(0),
                            )
                            when (phase) {
                                PointerPhase.DOWN -> {
                                    longPressConsumed = false
                                    longPressJob?.cancel()
                                    longPressJob = gestureScope.launch {
                                        delay(longPressDetector.longPressThresholdMs)
                                        if (longPressDetector.fireIfElapsed(SystemClock.uptimeMillis())) {
                                            val nx = (longPressDetector.anchorX / view.width.coerceAtLeast(1))
                                                .coerceIn(0f, 1f)
                                            val ny = (longPressDetector.anchorY / view.height.coerceAtLeast(1))
                                                .coerceIn(0f, 1f)
                                            // The DOWN was already forwarded as a left press;
                                            // release it then right-click, in order.
                                            viewModel.sendLongPressRightClick(nx, ny)
                                            longPressConsumed = true
                                        }
                                    }
                                    // A new touch stops any glide in progress (tap to catch),
                                    // like a real tablet. Reset velocity for the new gesture.
                                    flingJob?.cancel()
                                    flingJob = null
                                    velocityTracker.reset()
                                    scrolling = false
                                    lastScrollTimeMs = 0L
                                }

                                // The two-finger scroll ends when a finger lifts. If it was
                                // still moving at release (not paused first), let it glide.
                                PointerPhase.UP,
                                PointerPhase.POINTER_UP -> {
                                    longPressJob?.cancel()
                                    val sinceLastScroll =
                                        if (lastScrollTimeMs == 0L) Long.MAX_VALUE
                                        else event.eventTime - lastScrollTimeMs
                                    if (scrolling &&
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
                                                vx = decayed.first
                                                vy = decayed.second
                                                delay(flingDecay.frameMs)
                                            }
                                        }
                                    }
                                    scrolling = false
                                }

                                PointerPhase.CANCEL -> {
                                    longPressJob?.cancel()
                                    velocityTracker.reset()
                                    scrolling = false
                                }

                                PointerPhase.POINTER_DOWN -> {
                                    longPressJob?.cancel()
                                    // A second finger begins a fresh scroll; stop any glide
                                    // so it can't run alongside the new live scroll.
                                    flingJob?.cancel()
                                    flingJob = null
                                }

                                PointerPhase.MOVE -> Unit
                            }

                            val outcome = twoFingerDetector.onEvent(
                                phase = phase,
                                pointerCount = event.pointerCount,
                                eventTimeMs = event.eventTime,
                                primaryX = event.getX(0),
                                primaryY = event.getY(0),
                            )
                            if (outcome.enteredMultiTouch) {
                                // The first finger's DOWN was already forwarded; release
                                // it so the Mac doesn't keep the button pressed.
                                val nx = (event.getX(0) / view.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val ny = (event.getY(0) / view.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                                viewModel.cancelTouch(nx, ny)
                            }
                            if (outcome.twoFingerTap) {
                                revealControls()
                            }
                            if (outcome.scrollDx != 0f || outcome.scrollDy != 0f) {
                                // Two-finger drag -> scroll. Normalize the px delta to a
                                // fraction of the view (device-independent) for the Mac.
                                val dxNorm = outcome.scrollDx / view.width.coerceAtLeast(1)
                                val dyNorm = outcome.scrollDy / view.height.coerceAtLeast(1)
                                viewModel.sendScroll(dxNorm, dyNorm)
                                // Track velocity (normalized units/ms) for the release fling.
                                val dt = if (lastScrollTimeMs == 0L) flingDecay.frameMs
                                    else (event.eventTime - lastScrollTimeMs).coerceAtLeast(1L)
                                velocityTracker.track(dxNorm, dyNorm, dt)
                                lastScrollTimeMs = event.eventTime
                                scrolling = true
                            }
                            if (outcome.suppressForward) {
                                return@setOnTouchListener true
                            }
                        }

                        // A long-press already became a right-click; swallow the rest of
                        // this gesture so its trailing MOVE/UP aren't forwarded as a left
                        // drag/hover on the Mac.
                        if (longPressConsumed) {
                            return@setOnTouchListener true
                        }

                        val touches = TouchCollector.collect(event, view.width, view.height)
                        if (touches.isNotEmpty()) {
                            viewModel.sendTouches(touches)
                        }
                        // Consume moves/downs so we keep receiving the gesture stream.
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
                    keepScreenOn = true
                }
            },
        )

        // Floating control (spec §6). Hidden until a two-finger tap reveals it, then
        // auto-hides. The glass handle sits at the top-center; tapping it expands two
        // extra buttons (Settings + Disconnect) to its LEFT. Compose consumes taps on
        // these buttons; every other touch falls through to the SurfaceView.
        AnimatedVisibility(
            visible = controlsShown,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp),
        ) {
            FloatingControl(
                expanded = controlsExpanded,
                onToggle = {
                    controlsExpanded = !controlsExpanded
                    interactionNonce++ // keep the control up while the user is using it
                },
                onSettings = {
                    // Leave to Settings (not Connect): set the exit guard so the
                    // Disconnected from teardown doesn't also navigate to Connect.
                    if (!exiting) {
                        exiting = true
                        viewModel.teardown()
                        onOpenSettings()
                    }
                },
                onDisconnect = { leaveToConnect() },
            )
        }

        // While the link is re-establishing, dim the frozen frame and show a spinner
        // so the stale image is never mistaken for a live one.
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

/** Idle time before the floating control auto-hides after being revealed. */
private const val CONTROLS_AUTO_HIDE_MS = 8_000L

/** Max gap between the last scroll movement and the finger lift for a fling to start.
 *  A longer pause means the user deliberately stopped, so the content should not glide. */
private const val FLING_MAX_RELEASE_GAP_MS = 60L

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

@Composable
private fun FloatingControl(
    expanded: Boolean,
    onToggle: () -> Unit,
    onSettings: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 0 (collapsed) → 1 (expanded). Drives width reveal, fade, slide and gap.
    val expand by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 340, easing = StandardEasing),
        label = "overlayExpand",
    )
    // Handle rotates 90° and turns accent-filled when open (.3s).
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

    // Two 48dp buttons + 12dp inter-button gap = 108dp of content. The clipped region
    // is widened by a shadow-slack margin so the rightmost (Disconnect) button's 1dp
    // border and drop shadow are never sliced at the clip edge — that slicing showed
    // up as a half-rendered X. The slack doubles as the gap to the handle, so a
    // separate spacer isn't needed.
    val groupContentWidth = 108.dp
    val shadowSlack = 14.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Expandable extras group (to the LEFT of the handle). Content is left-aligned,
        // so the slack sits on the right of the X (transparent) — the reveal gap.
        Box(
            modifier = Modifier
                .width((groupContentWidth + shadowSlack) * expand)
                .graphicsLayer {
                    alpha = expand
                    translationX = (1f - expand) * 14.dp.toPx()
                }
                .clipToBounds(),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassCircleButton(
                    icon = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    onClick = onSettings,
                    enabled = expanded,
                )
                // Same neutral glass styling as the Settings button (no red tint).
                GlassCircleButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "Disconnect",
                    onClick = onDisconnect,
                    enabled = expanded,
                )
            }
        }

        // Handle (the ⋯ button). Tapping it expands the group to its left.
        GlassCircleButton(
            icon = Icons.Outlined.MoreVert,
            contentDescription = "Controls",
            onClick = onToggle,
            containerColor = handleFill,
            borderColor = handleBorder,
            iconTint = if (expanded) Color.White else DeskLinkTokens.TextPrimary,
            modifier = Modifier.graphicsLayer { rotationZ = handleRotation },
        )
    }
}
