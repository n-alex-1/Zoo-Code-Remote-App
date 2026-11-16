package de.xilox.zooremote.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Zoo Remote brand palette (session 12 redesign, based on the approved mockup): a near-black
 * background with a subtle violet cast, lavender accents, tonal purple containers for chips and
 * cards, and a green reserved for success status pills.
 */
object ZooColors {
	// Accents
	val lavender = Color(0xFFB7A5E5)
	val lavenderDark = Color(0xFF6C5B9C)
	val lavenderTonal = Color(0xFF3A3357)
	val lavenderOnTonal = Color(0xFFD9CCEE)
	val success = Color(0xFF4CC38B)
	val successDark = Color(0xFF1E6E4A)
	val warning = Color(0xFFF5A623)

	// Dark surfaces (phone OLED-ish, slight purple tint like the mockup)
	val darkBackground = Color(0xFF0F0D15)
	val darkSurface = Color(0xFF17141F)
	val darkCard = Color(0xFF1F1B2B)
	val darkSurfaceVariant = Color(0xFF2A2538)
	val darkOnSurface = Color(0xFFECE9F4)
	val darkOnSurfaceVariant = Color(0xFFA9A3BC)
	val darkDivider = Color(0xFF2E2A3C)

	// Light surfaces
	val lightBackground = Color(0xFFFAF8FE)
	val lightSurface = Color(0xFFFFFFFF)
	val lightSurfaceVariant = Color(0xFFECE8F5)
	val lightOnSurface = Color(0xFF1B1826)
	val lightOnSurfaceVariant = Color(0xFF575168)

	internal val DarkScheme = darkColorScheme(
		primary = lavender,
		onPrimary = Color(0xFF241E38),
		primaryContainer = lavenderTonal,
		onPrimaryContainer = lavenderOnTonal,
		secondary = Color(0xFFCBC3E0),
		onSecondary = Color(0xFF241E38),
		secondaryContainer = Color(0xFF3E3757),
		onSecondaryContainer = Color(0xFFE8E2F6),
		tertiary = Color(0xFFAFCBE8),
		onTertiary = Color(0xFF152534),
		error = Color(0xFFF2B8B5),
		onError = Color(0xFF390001),
		errorContainer = Color(0xFF5C1A1D),
		onErrorContainer = Color(0xFFF9DEDC),
		background = darkBackground,
		onBackground = darkOnSurface,
		surface = darkSurface,
		onSurface = darkOnSurface,
		surfaceVariant = darkSurfaceVariant,
		onSurfaceVariant = darkOnSurfaceVariant,
		surfaceContainer = darkCard,
		surfaceContainerHigh = darkCard,
		surfaceContainerHighest = darkSurfaceVariant,
		outline = Color(0xFF494459),
		outlineVariant = darkDivider,
)

	internal val LightScheme = lightColorScheme(
		primary = lavenderDark,
		onPrimary = Color(0xFFFBF9FF),
		primaryContainer = Color(0xFFE5DDF8),
		onPrimaryContainer = Color(0xFF241E38),
		secondary = Color(0xFF4E4470),
		secondaryContainer = Color(0xFFE0D9F0),
		tertiary = Color(0xFF2E5C86),
		error = Color(0xFF8C1D18),
		background = lightBackground,
		onBackground = lightOnSurface,
		surface = lightSurface,
		onSurface = lightOnSurface,
		surfaceVariant = lightSurfaceVariant,
		onSurfaceVariant = lightOnSurfaceVariant,
	)

}

/** Extra non-M3 tokens available through the theme (dividers, card tint). */
data class ZooTokens(
	val divider: Color,
	val card: Color,
)

val LocalZooTokens = staticCompositionLocalOf {
	ZooTokens(divider = ZooColors.darkDivider, card = ZooColors.darkCard)
}

/**
 * App-wide Material 3 theme. Replaces the stock `darkColorScheme()`/`lightColorScheme()` so every
 * screen (status feed, session picker, mode/model pickers) follows the same violet design language.
 */
@Composable
fun ZooRemoteTheme(
	darkTheme: Boolean = isSystemInDarkTheme(),
	content: @Composable () -> Unit,
) {
	val colorScheme = if (darkTheme) ZooColors.DarkScheme else ZooColors.LightScheme
	val tokens = if (darkTheme) {
		ZooTokens(divider = ZooColors.darkDivider, card = ZooColors.darkCard)
	} else {
		ZooTokens(divider = Color(0xFFE2DDF0), card = Color(0xFFFFFFFF))
	}

	// Keep the stock typography scale; only the palette changes.
	MaterialTheme(colorScheme = colorScheme, typography = Typography()) {
		androidx.compose.runtime.CompositionLocalProvider(LocalZooTokens provides tokens, content = content)
	}
}
