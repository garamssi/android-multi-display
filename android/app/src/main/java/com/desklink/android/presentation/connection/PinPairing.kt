package com.desklink.android.presentation.connection

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.outlined.ArrowBackIosNew
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DesktopMac
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.desklink.android.domain.model.ProtocolConstants
import com.desklink.android.presentation.components.GradientButton
import com.desklink.android.presentation.components.MonoText
import com.desklink.android.presentation.components.SpinnerRing
import com.desklink.android.presentation.theme.DeskLinkTokens
import com.desklink.android.presentation.theme.PlexSans
import kotlin.math.roundToInt

/** The visual states of the Wi-Fi pairing PIN screen (design 01c). */
enum class PairingPhase { Entering, Verifying, WrongPin, Reentry }

/**
 * Full-screen Wi-Fi pairing PIN entry (design 01c). The tablet collects the 6-digit code
 * shown on the Mac via an on-screen keypad — no system IME — then auto-submits. Layout is
 * a two-column split on wide landscape and a single centered column otherwise.
 *
 * Pure renderer: it owns only the in-progress [pin] text; the phase and remaining attempts
 * are driven by the caller from the connection result. [onReenter] / [onClear] clear the
 * field for another try (the caller keeps the attempt counter).
 */
@Composable
fun PinEntryContent(
    deviceName: String,
    host: String,
    phase: PairingPhase,
    attemptsLeft: Int,
    onSubmit: (pin: String) -> Unit,
    onReenter: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pinLength = ProtocolConstants.PAIRING_PIN_LENGTH
    var pin by remember { mutableStateOf("") }
    // Clear the entry whenever the field is (re-)armed for typing: a fresh screen, or a
    // retry via "Re-enter PIN" / Clear.
    LaunchedEffect(phase) {
        if (phase == PairingPhase.Entering || phase == PairingPhase.Reentry) pin = ""
    }

    val acceptsInput = phase == PairingPhase.Entering || phase == PairingPhase.Reentry

    fun input(digit: Char) {
        if (!acceptsInput || pin.length >= pinLength) return
        val next = pin + digit
        pin = next
        if (next.length == pinLength) onSubmit(next)
    }

    fun backspace() {
        if (acceptsInput && pin.isNotEmpty()) pin = pin.dropLast(1)
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val split = maxWidth > maxHeight && maxWidth >= 720.dp

        if (split) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 54.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    PairingContext(deviceName = deviceName, host = host, compact = false)
                }
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    PinPanel(
                        pin = pin,
                        phase = phase,
                        attemptsLeft = attemptsLeft,
                        compact = false,
                        onInput = ::input,
                        onBackspace = ::backspace,
                        onReenter = onReenter,
                        onClear = onClear,
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(84.dp))
                PairingContext(deviceName = deviceName, host = host, compact = true)
                Spacer(Modifier.height(26.dp))
                PinPanel(
                    pin = pin,
                    phase = phase,
                    attemptsLeft = attemptsLeft,
                    compact = true,
                    onInput = ::input,
                    onBackspace = ::backspace,
                    onReenter = onReenter,
                    onClear = onClear,
                )
                Spacer(Modifier.height(40.dp))
            }
        }

        BackButton(
            onBack = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 20.dp, top = 12.dp),
        )
    }
}

@Composable
private fun BackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(42.dp)
            .clip(DeskLinkTokens.ShapeChip)
            .background(DeskLinkTokens.Surface04, DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border10), DeskLinkTokens.ShapeChip)
            .clickable(onClick = onBack),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.ArrowBackIosNew,
            contentDescription = "Back",
            tint = DeskLinkTokens.TextPrimary,
            modifier = Modifier.size(18.dp),
        )
    }
}

/** Left/top context: lock glyph, title, subtitle, and (wide only) device chip + TLS note. */
@Composable
private fun PairingContext(deviceName: String, host: String, compact: Boolean) {
    Column(
        horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        AccentIconTile(
            icon = Icons.Outlined.Lock,
            tileSize = if (compact) 54.dp else 64.dp,
            iconSize = if (compact) 27.dp else 32.dp,
        )
        Spacer(Modifier.height(if (compact) 16.dp else 22.dp))
        Text(
            text = "Enter pairing PIN",
            color = DeskLinkTokens.TextPrimary,
            fontFamily = PlexSans,
            fontSize = if (compact) 22.sp else 29.sp,
            fontWeight = FontWeight.W700,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))
        Text(
            text = if (compact) {
                "Code shown on $deviceName"
            } else {
                "Type the 6-digit code shown on $deviceName (DeskLink -> Settings)."
            },
            color = DeskLinkTokens.TextSecondary,
            fontFamily = PlexSans,
            fontSize = if (compact) 12.5.sp else 15.sp,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
        )
        if (!compact) {
            Spacer(Modifier.height(26.dp))
            DeviceChip(deviceName = deviceName, host = host)
            Spacer(Modifier.height(20.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = DeskLinkTokens.Success,
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    text = "Encrypted with TLS - one-time pairing",
                    color = DeskLinkTokens.TextTertiary,
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                )
            }
        }
    }
}

@Composable
private fun AccentIconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tileSize: androidx.compose.ui.unit.Dp,
    iconSize: androidx.compose.ui.unit.Dp,
) {
    Box(
        modifier = Modifier
            .size(tileSize)
            .clip(DeskLinkTokens.ShapeGlyph)
            .background(DeskLinkTokens.AccentTileBg, DeskLinkTokens.ShapeGlyph)
            .border(BorderStroke(1.dp, DeskLinkTokens.AccentTileBorder), DeskLinkTokens.ShapeGlyph),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = DeskLinkTokens.AccentIcon,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun DeviceChip(deviceName: String, host: String) {
    Row(
        modifier = Modifier
            .clip(DeskLinkTokens.ShapeChip)
            .background(DeskLinkTokens.Surface03, DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border07), DeskLinkTokens.ShapeChip)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(DeskLinkTokens.ShapeChip)
                .background(DeskLinkTokens.AccentChipBg, DeskLinkTokens.ShapeChip),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.DesktopMac,
                contentDescription = null,
                tint = DeskLinkTokens.AccentIcon,
                modifier = Modifier.size(17.dp),
            )
        }
        Column {
            Text(
                text = deviceName,
                color = DeskLinkTokens.TextPrimary,
                fontFamily = PlexSans,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.W600,
            )
            Spacer(Modifier.height(2.dp))
            MonoText(text = host, color = DeskLinkTokens.TextSecondary, fontSize = 12.sp)
        }
    }
}

/** Right/lower panel: the slot row plus keypad / spinner / error, keyed on [phase]. */
@Composable
private fun PinPanel(
    pin: String,
    phase: PairingPhase,
    attemptsLeft: Int,
    compact: Boolean,
    onInput: (Char) -> Unit,
    onBackspace: () -> Unit,
    onReenter: () -> Unit,
    onClear: () -> Unit,
) {
    val pinLength = ProtocolConstants.PAIRING_PIN_LENGTH
    val slotW = if (compact) 42.dp else 52.dp
    val slotGap = if (compact) 9.dp else 11.dp
    // Width of the slot row, so the retry chrome (header / actions) lines up with it.
    val rowWidth = slotW * pinLength + slotGap * (pinLength - 1)

    // Shake the slots once when a wrong PIN is rejected (design: ~0.5s translateX). Reset
    // to rest in any other phase so an early retry can't leave the row mid-offset.
    val shakeX = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        if (phase == PairingPhase.WrongPin) {
            shakeX.snapTo(0f)
            shakeX.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 500
                    (-2f) at 50
                    4f at 100
                    (-7f) at 150
                    7f at 200
                    (-7f) at 250
                    7f at 300
                    (-7f) at 350
                    4f at 400
                    (-2f) at 450
                },
            )
        } else {
            shakeX.snapTo(0f)
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (phase == PairingPhase.Reentry) {
            ReentryHeader(attemptsLeft = attemptsLeft, width = rowWidth)
            Spacer(Modifier.height(18.dp))
        }

        PinSlots(
            pin = pin,
            phase = phase,
            slotWidth = slotW,
            slotHeight = if (compact) 54.dp else 64.dp,
            gap = slotGap,
            digitSize = if (compact) 24.sp else 28.sp,
            caretHeight = if (compact) 24.dp else 28.dp,
            modifier = Modifier.offset { IntOffset(shakeX.value.roundToInt(), 0) },
        )

        when (phase) {
            PairingPhase.Entering -> {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "${pin.length} of $pinLength entered",
                    color = DeskLinkTokens.TextTertiary,
                    fontFamily = PlexSans,
                    fontSize = 12.5.sp,
                )
                Spacer(Modifier.height(26.dp))
                Keypad(compact = compact, onInput = onInput, onBackspace = onBackspace)
            }

            PairingPhase.Reentry -> {
                Spacer(Modifier.height(16.dp))
                FreshPinHint()
                Spacer(Modifier.height(26.dp))
                Keypad(compact = compact, onInput = onInput, onBackspace = onBackspace)
            }

            PairingPhase.Verifying -> {
                Spacer(Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                ) {
                    SpinnerRing(size = 20.dp, strokeWidth = 2.4.dp) {}
                    Text(
                        text = "Verifying PIN...",
                        color = DeskLinkTokens.TextBody,
                        fontFamily = PlexSans,
                        fontSize = 15.sp,
                    )
                }
            }

            PairingPhase.WrongPin -> {
                Spacer(Modifier.height(22.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = DeskLinkTokens.ErrorText,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        text = "Incorrect PIN - $attemptsLeft attempt${if (attemptsLeft == 1) "" else "s"} left",
                        color = DeskLinkTokens.ErrorText,
                        fontFamily = PlexSans,
                        fontSize = 14.5.sp,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Row(
                    modifier = Modifier.width(rowWidth),
                    horizontalArrangement = Arrangement.spacedBy(11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GradientButton(
                        text = "Re-enter PIN",
                        onClick = onReenter,
                        modifier = Modifier.weight(1f),
                        leadingIcon = Icons.Outlined.Refresh,
                        height = 48.dp,
                        cornerRadius = DeskLinkTokens.RadiusChip,
                        fontSize = 15.sp,
                        iconSize = 18.dp,
                    )
                    ClearIconButton(onClear = onClear)
                }
                Spacer(Modifier.height(16.dp))
                MonoText(
                    text = "ERR_PAIR_PIN_MISMATCH",
                    color = DeskLinkTokens.ErrorChipText,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** Re-entry header: "Try again — enter the new code" + a remaining-attempts pill. */
@Composable
private fun ReentryHeader(attemptsLeft: Int, width: androidx.compose.ui.unit.Dp) {
    Row(
        modifier = Modifier.width(width),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Try again - enter the new code",
            color = DeskLinkTokens.TextBody,
            fontFamily = PlexSans,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.W500,
        )
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .clip(DeskLinkTokens.ShapePill)
                .background(DeskLinkTokens.ErrorChipBg, DeskLinkTokens.ShapePill)
                .border(BorderStroke(1.dp, DeskLinkTokens.ErrorTintBorder), DeskLinkTokens.ShapePill)
                .padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = DeskLinkTokens.PinSlotErrorText,
                modifier = Modifier.size(12.dp),
            )
            MonoText(
                text = "$attemptsLeft left",
                color = DeskLinkTokens.PinSlotErrorText,
                fontSize = 11.sp,
            )
        }
    }
}

/** Mono hint reminding the user the Mac PIN rotates, so they should use the latest. */
@Composable
private fun FreshPinHint() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Refresh,
            contentDescription = null,
            tint = DeskLinkTokens.TextTertiary,
            modifier = Modifier.size(13.dp),
        )
        MonoText(
            text = "A fresh PIN may show on your Mac - use the latest",
            color = DeskLinkTokens.TextTertiary,
            fontSize = 11.5.sp,
        )
    }
}

@Composable
private fun ClearIconButton(onClear: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(DeskLinkTokens.ShapeChip)
            .background(DeskLinkTokens.Surface04, DeskLinkTokens.ShapeChip)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border14), DeskLinkTokens.ShapeChip)
            .clickable(onClick = onClear),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = "Clear",
            tint = DeskLinkTokens.TextBody,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun PinSlots(
    pin: String,
    phase: PairingPhase,
    slotWidth: androidx.compose.ui.unit.Dp,
    slotHeight: androidx.compose.ui.unit.Dp,
    gap: androidx.compose.ui.unit.Dp,
    digitSize: androidx.compose.ui.unit.TextUnit,
    caretHeight: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val pinLength = ProtocolConstants.PAIRING_PIN_LENGTH

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        repeat(pinLength) { index ->
            val fill: Color
            val border: Color
            val textColor: Color
            when (phase) {
                PairingPhase.Verifying -> {
                    fill = DeskLinkTokens.PinSlotVerifyFill
                    border = DeskLinkTokens.PinSlotVerifyBorder
                    textColor = DeskLinkTokens.TextSecondary
                }
                PairingPhase.WrongPin -> {
                    fill = DeskLinkTokens.PinSlotErrorFill
                    border = DeskLinkTokens.PinSlotErrorBorder
                    textColor = DeskLinkTokens.PinSlotErrorText
                }
                // Entering and Reentry share the active/filled/empty rendering.
                PairingPhase.Entering, PairingPhase.Reentry -> {
                    val isActive = index == pin.length
                    val isFilled = index < pin.length
                    fill = when {
                        isActive -> DeskLinkTokens.PinSlotActiveFill
                        isFilled -> DeskLinkTokens.PinSlotFill
                        else -> DeskLinkTokens.Surface03
                    }
                    border = when {
                        isActive -> DeskLinkTokens.AccentLight
                        isFilled -> DeskLinkTokens.PinSlotBorder
                        else -> DeskLinkTokens.Border10
                    }
                    textColor = DeskLinkTokens.TextPrimary
                }
            }

            Box(
                modifier = Modifier
                    .size(width = slotWidth, height = slotHeight)
                    .clip(DeskLinkTokens.ShapePinSlot)
                    .background(fill, DeskLinkTokens.ShapePinSlot)
                    .border(BorderStroke(1.5.dp, border), DeskLinkTokens.ShapePinSlot),
                contentAlignment = Alignment.Center,
            ) {
                when (phase) {
                    PairingPhase.Verifying ->
                        MonoText(text = "•", color = textColor, fontSize = digitSize, fontWeight = FontWeight.W600)
                    PairingPhase.WrongPin ->
                        MonoText(text = pin.getOrNull(index)?.toString() ?: "", color = textColor, fontSize = digitSize, fontWeight = FontWeight.W600)
                    PairingPhase.Entering, PairingPhase.Reentry -> {
                        if (index < pin.length) {
                            MonoText(text = pin[index].toString(), color = textColor, fontSize = digitSize, fontWeight = FontWeight.W600)
                        } else if (index == pin.length) {
                            Caret(heightDp = caretHeight)
                        }
                    }
                }
            }
        }
    }
}

/** Blinking caret shown in the active (next-to-fill) slot. */
@Composable
private fun Caret(heightDp: androidx.compose.ui.unit.Dp) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(530, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caretAlpha",
    )
    Box(
        modifier = Modifier
            .width(2.dp)
            .height(heightDp)
            .alpha(alpha)
            .clip(DeskLinkTokens.ShapePill)
            .background(DeskLinkTokens.AccentLight),
    )
}

@Composable
private fun Keypad(compact: Boolean, onInput: (Char) -> Unit, onBackspace: () -> Unit) {
    val keyW = if (compact) 58.dp else 64.dp
    val keyH = if (compact) 48.dp else 52.dp
    val gap = if (compact) 10.dp else 12.dp
    val digitSize = if (compact) 20.sp else 22.sp

    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        listOf(listOf('1', '2', '3'), listOf('4', '5', '6'), listOf('7', '8', '9')).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { digit ->
                    DigitKey(digit = digit, width = keyW, height = keyH, fontSize = digitSize, onInput = onInput)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Spacer(Modifier.size(width = keyW, height = keyH))
            DigitKey(digit = '0', width = keyW, height = keyH, fontSize = digitSize, onInput = onInput)
            Box(
                modifier = Modifier
                    .size(width = keyW, height = keyH)
                    .clip(DeskLinkTokens.ShapeKeypadKey)
                    .clickable(onClick = onBackspace),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.Backspace,
                    contentDescription = "Backspace",
                    tint = DeskLinkTokens.TextSecondary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun DigitKey(
    digit: Char,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onInput: (Char) -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(DeskLinkTokens.ShapeKeypadKey)
            .background(DeskLinkTokens.Surface05, DeskLinkTokens.ShapeKeypadKey)
            .border(BorderStroke(1.dp, DeskLinkTokens.Border08), DeskLinkTokens.ShapeKeypadKey)
            .clickable { onInput(digit) },
        contentAlignment = Alignment.Center,
    ) {
        MonoText(text = digit.toString(), color = DeskLinkTokens.TextPrimary, fontSize = fontSize)
    }
}
