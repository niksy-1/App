package com.nikhil.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

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

@Composable
fun RadarTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color (Material You) derives the whole palette from the user's
    // wallpaper on Android 12+, which silently overrides everything defined
    // above — that's what was making the app look like generic system
    // default rather than anything intentional. Defaulting this to false so
    // the custom palette actually renders.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}