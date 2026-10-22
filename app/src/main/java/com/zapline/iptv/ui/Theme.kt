package com.zapline.iptv.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.zapline.iptv.R

/*
 * Zapline design tokens.
 *
 * Night-time living room: an indigo-plum base instead of flat black, lilac-grey text,
 * and one accent - amber, the colour of a studio "on air" tally light - reserved for
 * things that are live right now (current programme, progress, the guide's now-line,
 * the live indicator) and for the primary action.
 */
object Palette {
    val Ink = Color(0xFF15131F)          // app background
    val Night = Color(0xFF1C1929)        // rows, sheets
    val Dusk = Color(0xFF262236)         // raised surfaces, logo tiles
    val Haze = Color(0xFF332D47)         // pressed / selected surfaces
    val Line = Color(0xFF3A3450)
    val LineSoft = Color(0xFF2A2539)
    val Text = Color(0xFFEDEAF5)
    val Muted = Color(0xFFA49DB8)
    val Faint = Color(0xFF6F6888)
    val Lilac = Color(0xFFB9AEE0)
    val Alert = Color(0xFFFF8A7A)

    /** The user's accent. Snapshot state, so changing it recolours the whole app at once. */
    private val accent = mutableStateOf(Accents.first().color)

    /** Accent: live / now / primary action. Named after a studio tally light. */
    val Tally: Color get() = accent.value
    /** Accent mixed into the background, for selected and active surfaces. */
    val TallyDim: Color get() = lerp(Palette.Ink, accent.value, 0.24f)
    /** Text and icons placed on a solid accent fill. */
    val OnTally: Color get() = lerp(accent.value, Color.Black, 0.82f)
    /** Accent-tinted text on an accent-dim surface (selected labels). */
    val TallyText: Color get() = lerp(accent.value, Color.White, 0.55f)

    fun setAccent(c: Color) { accent.value = c }
}

data class Accent(val name: String, val color: Color)

/** Accent choices: all tuned to stay readable against the indigo background. */
val Accents = listOf(
    Accent("Amber", Color(0xFFFFB547)),
    Accent("Tangerine", Color(0xFFFF8F4A)),
    Accent("Coral", Color(0xFFFF7A6B)),
    Accent("Red", Color(0xFFFF5A64)),
    Accent("Rose", Color(0xFFFF6FAE)),
    Accent("Orchid", Color(0xFFE07BFF)),
    Accent("Violet", Color(0xFFA98BFF)),
    Accent("Periwinkle", Color(0xFF8FA2FF)),
    Accent("Sky", Color(0xFF5CC8FF)),
    Accent("Teal", Color(0xFF3FD0C0)),
    Accent("Mint", Color(0xFF6EE7A8)),
    Accent("Lime", Color(0xFFB8E05A)),
    Accent("Lemon", Color(0xFFF2E05C)),
    Accent("Ice", Color(0xFFC9DCFF)),
)

val Display = FontFamily(
    Font(R.font.bricolage_semibold, FontWeight.SemiBold),
    Font(R.font.bricolage_bold, FontWeight.Bold),
    Font(R.font.bricolage_extrabold, FontWeight.ExtraBold),
)

val Body = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold),
)

/** Tabular figures so clock times and counts line up in columns. */
private const val TNUM = "tnum"

private val type = Typography(
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.ExtraBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.015).em),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.01).em),
    titleLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TNUM),
    labelLarge = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM),
    labelSmall = TextStyle(fontFamily = Body, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, fontFeatureSettings = TNUM),
)

private fun scheme(accent: Color) = darkColorScheme(
    primary = accent,
    onPrimary = Palette.OnTally,
    primaryContainer = Palette.TallyDim,
    onPrimaryContainer = Palette.TallyText,
    secondary = Palette.Lilac,
    onSecondary = Color(0xFF231C3D),
    secondaryContainer = Color(0xFF2F2945),
    onSecondaryContainer = Color(0xFFDCD5F5),
    background = Palette.Ink,
    onBackground = Palette.Text,
    surface = Palette.Ink,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Dusk,
    onSurfaceVariant = Palette.Muted,
    surfaceContainerLowest = Palette.Ink,
    surfaceContainerLow = Palette.Night,
    surfaceContainer = Palette.Night,
    surfaceContainerHigh = Palette.Dusk,
    surfaceContainerHighest = Palette.Haze,
    outline = Palette.Line,
    outlineVariant = Palette.LineSoft,
    error = Palette.Alert,
    scrim = Color(0xCC0B0A12),
)

/** Hierarchy in the corners: big containers are softer, small controls tighter. */
private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun ZaplineTheme(content: @Composable () -> Unit) {
    val accent = Palette.Tally
    val colors = remember(accent) { scheme(accent) }
    MaterialTheme(colorScheme = colors, typography = type, shapes = shapes) {
        // Light text by default everywhere, including screens drawn without a Surface (the player),
        // so nothing can ever fall back to black on the dark background.
        CompositionLocalProvider(LocalContentColor provides Palette.Text, content = content)
    }
}
