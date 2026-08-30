package com.nikhil.app.ui.theme

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit

enum class RadarThemeVariant(val displayName: String) {
    DEFAULT("Default"),
    OLIVIA("Pink"),
    WISHBONE("Yellow"),
    GIRL_IN_RED("Red"),
    TV_GIRL("Brown");

    companion object {
        fun fromName(name: String?): RadarThemeVariant =
            entries.find { it.name == name } ?: DEFAULT
    }
}

private val DarkColorScheme = darkColorScheme(
    primary = WineGlow,
    onPrimary = PlumBlack,
    secondary = LavenderMist,
    onSecondary = PlumBlack,
    tertiary = DustyCoral,
    onTertiary = PlumBlack,
    background = PlumBlack,
    onBackground = MoonlightText,
    surface = PlumSurface,
    onSurface = MoonlightText
)

private val LightColorScheme = lightColorScheme(
    primary = WineDeep,
    onPrimary = Parchment,
    secondary = LavenderDusk,
    onSecondary = Parchment,
    tertiary = CoralWarm,
    onTertiary = Parchment,
    background = Parchment,
    onBackground = InkPlum,
    surface = ParchmentSurface,
    onSurface = InkPlum
)

// The artist themes are all designed dark-mode-first (that's the app's whole
// vibe), so unlike Default they don't currently get a separate light variant.
// If you want one later, this is the spot to add it — the picker UI already
// just switches on the enum, so a light branch drops in without touching
// anything else.
private fun darkSchemeFor(variant: RadarThemeVariant): ColorScheme = when (variant) {
    RadarThemeVariant.DEFAULT -> DarkColorScheme
    RadarThemeVariant.OLIVIA -> darkColorScheme(
        primary = OliviaPalette.Primary,
        onPrimary = OliviaPalette.Background,
        secondary = OliviaPalette.Secondary,
        onSecondary = OliviaPalette.Background,
        tertiary = OliviaPalette.Tertiary,
        onTertiary = OliviaPalette.Background,
        background = OliviaPalette.Background,
        onBackground = OliviaPalette.OnColors,
        surface = OliviaPalette.Surface,
        onSurface = OliviaPalette.OnColors
    )
    RadarThemeVariant.WISHBONE -> darkColorScheme(
        primary = WishbonePalette.Primary,
        onPrimary = WishbonePalette.Background,
        secondary = WishbonePalette.Secondary,
        onSecondary = WishbonePalette.Background,
        tertiary = WishbonePalette.Tertiary,
        onTertiary = WishbonePalette.Background,
        background = WishbonePalette.Background,
        onBackground = WishbonePalette.OnColors,
        surface = WishbonePalette.Surface,
        onSurface = WishbonePalette.OnColors
    )
    RadarThemeVariant.GIRL_IN_RED -> darkColorScheme(
        primary = GirlInRedPalette.Primary,
        onPrimary = GirlInRedPalette.Background,
        secondary = GirlInRedPalette.Secondary,
        onSecondary = GirlInRedPalette.OnColors,
        tertiary = GirlInRedPalette.Tertiary,
        onTertiary = GirlInRedPalette.Background,
        background = GirlInRedPalette.Background,
        onBackground = GirlInRedPalette.OnColors,
        surface = GirlInRedPalette.Surface,
        onSurface = GirlInRedPalette.OnColors
    )
    RadarThemeVariant.TV_GIRL -> darkColorScheme(
        primary = TvGirlPalette.Primary,
        onPrimary = TvGirlPalette.Background,
        secondary = TvGirlPalette.Secondary,
        onSecondary = TvGirlPalette.Background,
        tertiary = TvGirlPalette.Tertiary,
        onTertiary = TvGirlPalette.Background,
        background = TvGirlPalette.Background,
        onBackground = TvGirlPalette.OnColors,
        surface = TvGirlPalette.Surface,
        onSurface = TvGirlPalette.OnColors
    )
}

// Persistence lives in the same "RadarPrefs" SharedPreferences file the rest
// of the app already uses (widget background, saved partner token, etc.) so
// there's only one prefs file to reason about.
private const val PREFS_NAME = "RadarPrefs"
private const val KEY_THEME_VARIANT = "app_theme_variant"

fun saveThemeVariant(context: Context, variant: RadarThemeVariant) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit { putString(KEY_THEME_VARIANT, variant.name) }
}

fun loadThemeVariant(context: Context): RadarThemeVariant {
    val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_VARIANT, null)
    return RadarThemeVariant.fromName(stored)
}

@Composable
fun RadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeVariant: RadarThemeVariant = RadarThemeVariant.DEFAULT,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    android.util.Log.d("RadarTheme", "Applying theme variant: ${themeVariant.name}")
    val colorScheme = when {
        themeVariant == RadarThemeVariant.DEFAULT && dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        themeVariant == RadarThemeVariant.DEFAULT && !darkTheme -> LightColorScheme
        else -> darkSchemeFor(themeVariant)
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

// Convenience composable: reads the saved variant on first composition and
// keeps it in Compose state, so a picker screen can call
// `rememberThemeVariantState()` and pass both the current value and the
// setter (which persists + updates state together) down to whatever picker
// UI you build. Example:
//
//   val (variant, setVariant) = rememberThemeVariantState()
//   RadarTheme(themeVariant = variant) { /* app content */ }
//   ...
//   ThemeOption(..., onClick = { setVariant(RadarThemeVariant.WISHBONE) })
@Composable
fun rememberThemeVariantState(): Pair<RadarThemeVariant, (RadarThemeVariant) -> Unit> {
    val context = LocalContext.current
    var variant by remember { mutableStateOf(loadThemeVariant(context)) }
    val setVariant: (RadarThemeVariant) -> Unit = { newVariant ->
        variant = newVariant
        saveThemeVariant(context, newVariant)
    }
    return variant to setVariant
}
