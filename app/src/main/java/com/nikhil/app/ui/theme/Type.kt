package com.nikhil.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.unit.sp
import com.nikhil.app.R

// Bundle Caveat so Compose text and home-screen widget notes use the same font
// even when the phone is offline. License: assets/licenses/Caveat-OFL.txt.
val CaveatFontFamily = FontFamily(
    Font(R.font.caveat_regular, weight = FontWeight.Normal),
    Font(R.font.caveat_bold, weight = FontWeight.Bold)
)

val NoteTextStyle = TextStyle(
    fontFamily = CaveatFontFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 23.sp,
    lineHeight = 29.sp
)

// Headings and note text share Caveat; UI labels remain sans for readability.
val Typography = Typography(
    headlineLarge = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 31.sp,
        lineHeight = 36.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = CaveatFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        lineHeight = 32.sp
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
