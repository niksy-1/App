package com.nikhil.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.Font
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.unit.sp
import com.nikhil.app.R

// Downloadable Fonts: the font itself isn't bundled in the APK. Instead the
// system's Google Play Services Fonts provider is asked for "Caveat" at
// runtime (and caches it device-wide after the first fetch, so other apps
// using the same font don't re-download it either). This needs:
//   1. The "androidx.compose.ui:ui-text-google-fonts" dependency (see
//      build.gradle.kts).
//   2. res/values/font_certs.xml, which lists the certificates the system
//      uses to confirm requests are really going to Google Play Services and
//      not an impostor provider (see font_certs.xml).
private val fontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = R.array.com_google_android_gms_fonts_certs
)

private val caveat = GoogleFont("Caveat")

// Falls back to the system Serif automatically if the device has no Play
// Services (e.g. some tablets, emulators without Google APIs) or the fetch
// fails for any other reason — so this never leaves headlines blank.
val CaveatFontFamily = FontFamily(
    Font(googleFont = caveat, fontProvider = fontProvider, weight = FontWeight.Normal),
    Font(googleFont = caveat, fontProvider = fontProvider, weight = FontWeight.Medium),
    Font(googleFont = caveat, fontProvider = fontProvider, weight = FontWeight.Bold)
)

// Headline/title styles now use the handwritten Caveat font for a genuine
// diary-entry feel; body copy stays a clean sans for readability. Caveat
// runs visually smaller than a normal serif/sans at the same font-size value
// (it's a script font), so sizes here are bumped up a couple sp versus the
// old FontFamily.Serif versions to land at a similar apparent weight on
// screen — adjust to taste once you see it rendered on a device.
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 26.sp,
        lineHeight = 30.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 21.sp,
        lineHeight = 26.sp,
        letterSpacing = 0.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.4.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    )
)