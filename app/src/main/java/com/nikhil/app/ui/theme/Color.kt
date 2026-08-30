package com.nikhil.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================
// DEFAULT — the original palette. Moody, romantic, melancholy.
// ============================================================
val PlumBlack = Color(0xFF180F16)       // background
val PlumSurface = Color(0xFF241823)     // surface / cards
val WineGlow = Color(0xFFC97B8C)        // primary — soft glowing wine/rose
val LavenderMist = Color(0xFFA796C4)    // secondary — dreamy lavender
val DustyCoral = Color(0xFFE8A2A0)      // tertiary — warm coral accent
val MoonlightText = Color(0xFFF3E9E4)   // onBackground / onSurface

// Light theme — same mood, softened and legible for daylight.
val Parchment = Color(0xFFF5EDE6)       // background
val ParchmentSurface = Color(0xFFEFE3DD) // surface
val WineDeep = Color(0xFF7A2E42)        // primary
val LavenderDusk = Color(0xFF8B7BA8)    // secondary
val CoralWarm = Color(0xFFC97B7B)       // tertiary
val InkPlum = Color(0xFF2E1F24)         // onBackground / onSurface

// ============================================================
// OLIVIA RODRIGO — "you seem pretty sad for a girl so in love"
// (2026). Dreamy, teary-eyed, romantic pink. Softer and more
// wistful than the default's wine tones — closer to the album's
// pastel-melancholy artwork than GUTS' plaid burgundy or SOUR's
// grunge pastels.
// ============================================================
object OliviaPalette {
    val Background = Color(0xFF1A1017)   // near-black with a pink undertone
    val Surface = Color(0xFF2B1A24)
    val Primary = Color(0xFFF4A6C7)      // dreamy bubblegum-rose
    val Secondary = Color(0xFFD9A3D0)    // soft lilac-pink
    val Tertiary = Color(0xFFF6C6D0)     // pale blush accent
    val OnColors = Color(0xFFFBEEF3)     // near-white with a pink cast
}

// ============================================================
// CONAN GRAY — "Wishbone" (2025/26). Warm, sun-worn, a little
// western — driftwood Texas childhood, pajama-show intimacy.
// Terracotta and denim instead of anything neon or synth-pop.
// ============================================================
object WishbonePalette {
    val Background = Color(0xFF1C140F)   // warm near-black, like worn leather
    val Surface = Color(0xFF2B1F17)
    val Primary = Color(0xFFD98E5C)      // sun-baked terracotta
    val Secondary = Color(0xFF7A93A6)    // faded denim blue
    val Tertiary = Color(0xFFE8C77E)     // dusty wheat/gold
    val OnColors = Color(0xFFF5E9DA)     // warm cream
}

// ============================================================
// GIRL IN RED — stark, grainy, DIY punk-zine energy. True to
// the name: red is the whole point, set against near-black.
// ============================================================
object GirlInRedPalette {
    val Background = Color(0xFF120909)   // almost-black red
    val Surface = Color(0xFF1F0E0E)
    val Primary = Color(0xFFE8354A)      // bold, saturated red
    val Secondary = Color(0xFF4A4A4A)    // flat grainy grey
    val Tertiary = Color(0xFFFF6B7A)     // hot pink-red accent
    val OnColors = Color(0xFFF2E9E9)     // slightly warm off-white
}

// ============================================================
// TV GIRL — 60s girl-group nostalgia, sun-bleached film grain,
// pastel and warm at once. Softer / dustier than Olivia's, more
// vintage than modern.
// ============================================================
object TvGirlPalette {
    val Background = Color(0xFF1B1512)   // sepia-tinted near-black
    val Surface = Color(0xFF2A231F)
    val Primary = Color(0xFFF0A8A0)      // sun-faded coral-pink
    val Secondary = Color(0xFF9AC1C9)    // dusty vintage teal-blue
    val Tertiary = Color(0xFFF3D9A6)     // cream-gold
    val OnColors = Color(0xFFF7EEE3)     // warm vintage white
}
