package de.mischpult.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import de.mischpult.R

/**
 * Console palette: graphite panels and rubber pads, recessed black displays,
 * and colour only where hardware would have light, in LEDs, displays and
 * waveforms. Deck A is blue, deck B amber.
 */
object DjColors {
    /** The chassis. */
    val background = Color(0xFF151617)
    /** Panels. */
    val surface = Color(0xFF1F2123)
    /** Pads (buttons). */
    val surfaceHigh = Color(0xFF2A2D30)
    /** Display windows: readouts, waveforms, fields. */
    val lcd = Color(0xFF0E1011)
    /** An LED that is off, and inactive segments. */
    val ledOff = Color(0xFF3A3D40)
    val outline = Color(0xFF3A3D40)
    /** Fader and slider caps. */
    val cap = Color(0xFFC9CBC6)
    val capShade = Color(0xFF8E918C)
    val text = Color(0xFFE6E6E1)
    val textDim = Color(0xFF9A9C98)
    val deckA = Color(0xFF3FA9F5)
    val deckB = Color(0xFFFF9F43)
    val cue = Color(0xFFF4D35E)
    val error = Color(0xFFFF6B5E)

    fun deck(i: Int) = if (i == 0) deckA else deckB
}

/**
 * Bundled fonts (SIL Open Font License, texts in assets/licenses): Barlow
 * Condensed for all lettering, like the print on a hardware panel; Share Tech
 * Mono for readouts (time, BPM, key), like a device display.
 */
object DjFonts {
    val condensed = FontFamily(
        Font(R.font.barlow_condensed_regular, FontWeight.Normal),
        Font(R.font.barlow_condensed_medium, FontWeight.Medium),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
        Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    )
    val mono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
}

private val Condensed = DjFonts.condensed

private fun TextStyle.console() = copy(fontFamily = Condensed, letterSpacing = 0.sp)

private val typography = Typography().let { t ->
    Typography(
        t.displayLarge.console(), t.displayMedium.console(), t.displaySmall.console(),
        t.headlineLarge.console(), t.headlineMedium.console(), t.headlineSmall.console(),
        t.titleLarge.console(), t.titleMedium.console(), t.titleSmall.console(),
        t.bodyLarge.console(), t.bodyMedium.console(), t.bodySmall.console(),
        t.labelLarge.console(), t.labelMedium.console(), t.labelSmall.console(),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DjTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = DjColors.deckA,
            secondary = DjColors.deckB,
            background = DjColors.background,
            surface = DjColors.surface,
            surfaceVariant = DjColors.surfaceHigh,
            surfaceContainer = DjColors.surface,
            surfaceContainerHigh = DjColors.surfaceHigh,
            onBackground = DjColors.text,
            onSurface = DjColors.text,
            onSurfaceVariant = DjColors.textDim,
            outline = DjColors.outline,
            error = DjColors.error,
        ),
        typography = typography,
    ) {
        CompositionLocalProvider(
            // Controls are sized for fingers themselves; no extra 48 dp touch padding
            LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
            // Pads push in instead of rippling
            LocalRippleConfiguration provides null,
            LocalIndication provides PadPress,
            content = content,
        )
    }
}
