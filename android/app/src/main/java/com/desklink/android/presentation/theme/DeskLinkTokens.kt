package com.desklink.android.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object DeskLinkTokens {

    val AccentGradientTop = Color(0xFF7079FF)
    val AccentGradientBottom = Color(0xFF5B6BFF)
    val AccentSolid = Color(0xFF5B6BFF)
    val AccentLight = Color(0xFF7C86FF)
    val AccentViolet = Color(0xFF8A5BFF)

    val Success = Color(0xFF35D0A5)
    val SuccessText = Color(0xFF4FE0BA)
    val Error = Color(0xFFFF5C5C)
    val ErrorLight = Color(0xFFFF7A7A)
    val ErrorText = Color(0xFFFF8A8A)
    val Warning = Color(0xFFE0A64B)

    val AppBg = Color(0xFF0A0C10)
    val PageRadialCenter = Color(0xFF14161F)
    val PageRadialEnd = AppBg
    val PageRadialErrorCenter = Color(0xFF1C1418) // error state tint

    val Surface03 = Color.White.copy(alpha = 0.03f)
    val Surface04 = Color.White.copy(alpha = 0.04f)
    val Surface05 = Color.White.copy(alpha = 0.05f)
    val Surface06 = Color.White.copy(alpha = 0.06f)
    val Surface09 = Color.White.copy(alpha = 0.09f)

    val Border06 = Color.White.copy(alpha = 0.06f)
    val Border07 = Color.White.copy(alpha = 0.07f)
    val Border08 = Color.White.copy(alpha = 0.08f)
    val Border10 = Color.White.copy(alpha = 0.10f)
    val Border14 = Color.White.copy(alpha = 0.14f)
    val Border16 = Color.White.copy(alpha = 0.16f)
    val Border18 = Color.White.copy(alpha = 0.18f) // unselected card hover border

    val TextPrimary = Color(0xFFEAEDF3)
    val TextSecondary = Color(0xFF98A0AF)
    val TextTertiary = Color(0xFF7A8290)
    val TextQuaternary = Color(0xFF626A78)
    val TextValue = Color(0xFFD3D8E2) // mono value text on cards
    val TextBody = Color(0xFFB9C0CC) // body/subtitle on chips & outline buttons

    val AccentSelectedBorder = Color(0xFF7C86FF).copy(alpha = 0.5f) // rgba(124,134,255,.5)
    val AccentSelectedBg = Color(0xFF7C86FF).copy(alpha = 0.1f) // rgba(124,134,255,.1)

    val ErrorTintBg = Error.copy(alpha = 0.12f) // alert square bg
    val ErrorTintBorder = Error.copy(alpha = 0.30f)
    val ErrorChipBg = Error.copy(alpha = 0.08f)
    val ErrorChipBorder = Error.copy(alpha = 0.18f)
    val ErrorChipText = Color(0xFF7A5B5B)
    val SuccessChipBg = Success.copy(alpha = 0.08f)
    val SuccessChipBorder = Success.copy(alpha = 0.20f)
    val WarningChipBg = Warning.copy(alpha = 0.08f)
    val WarningChipBorder = Warning.copy(alpha = 0.24f)

    val GlassFill = Color(0xFF12151B).copy(alpha = 0.72f) // rgba(18,21,27,.72)
    val GlassFillHover = Color(0xFF1E222D).copy(alpha = 0.85f)
    val HandleOpenFill = AccentSolid.copy(alpha = 0.95f) // rgba(91,107,255,.95)
    val HandleOpenBorder = Color(0xFF8C96FF).copy(alpha = 0.6f) // rgba(140,150,255,.6)

    val AccentIcon = Color(0xFFAAB4FF)
    val AccentTileBg = AccentLight.copy(alpha = 0.12f)     // lock / device icon tile fill
    val AccentTileBorder = AccentLight.copy(alpha = 0.30f)
    val AccentChipBg = AccentLight.copy(alpha = 0.16f)      // device-card inner icon tile
    val PinSlotFill = AccentLight.copy(alpha = 0.08f)       // entered/empty-active slot fill
    val PinSlotBorder = AccentLight.copy(alpha = 0.45f)     // filled slot border
    val PinSlotActiveFill = AccentLight.copy(alpha = 0.14f) // caret slot fill
    val PinSlotVerifyFill = AccentLight.copy(alpha = 0.10f) // masked verifying slot
    val PinSlotVerifyBorder = AccentLight.copy(alpha = 0.35f)
    val PinSlotErrorFill = Error.copy(alpha = 0.08f)
    val PinSlotErrorBorder = Error.copy(alpha = 0.50f)
    val PinSlotErrorText = Color(0xFFFF9A9A)

    val AccentVertical: Brush = Brush.verticalGradient(
        colors = listOf(AccentGradientTop, AccentGradientBottom),
    )

    val AppGlyphGradient: Brush = Brush.linearGradient(
        colorStops = arrayOf(
            0.0f to AccentLight,
            0.55f to AccentSolid,
            1.0f to AccentViolet,
        ),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

    val ProgressGradient: Brush = Brush.horizontalGradient(
        colors = listOf(AccentGradientTop, AccentViolet),
    )

    val RadiusButton = 11.dp
    val RadiusButtonLarge = 16.dp // 230x56 CTA
    val RadiusButtonMedium = 14.dp // error buttons
    val RadiusCard = 14.dp
    val RadiusChip = 12.dp
    val RadiusSegment = 10.dp
    val RadiusSegmentTrack = 14.dp
    val RadiusGlyphSmall = 16.dp
    val RadiusGlyph = 22.dp
    val RadiusSquareButton = 11.dp // 40dp header back square
    val RadiusPinSlot = 13.dp // PIN digit slot
    val RadiusKeypadKey = 14.dp // on-screen keypad key
    val RadiusPill = 999.dp

    val ShapeGlyph = RoundedCornerShape(RadiusGlyph)
    val ShapeCard = RoundedCornerShape(RadiusCard)
    val ShapeChip = RoundedCornerShape(RadiusChip)
    val ShapePill = RoundedCornerShape(RadiusPill)
    val ShapeSegmentTrack = RoundedCornerShape(RadiusSegmentTrack)
    val ShapeSegment = RoundedCornerShape(RadiusSegment)
    val ShapePinSlot = RoundedCornerShape(RadiusPinSlot)
    val ShapeKeypadKey = RoundedCornerShape(RadiusKeypadKey)
}
