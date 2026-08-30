package com.nikhil.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Each variant's four swatch dots, in the same primary/secondary/tertiary/
// background order used to build its ColorScheme in Theme.kt — this is
// purely for the little preview dots below, so it's fine that it duplicates
// those values rather than reading them back out of a ColorScheme.
private fun swatchesFor(variant: RadarThemeVariant): List<Color> = when (variant) {
    RadarThemeVariant.DEFAULT -> listOf(WineGlow, LavenderMist, DustyCoral, PlumBlack)
    RadarThemeVariant.OLIVIA -> listOf(
        OliviaPalette.Primary, OliviaPalette.Secondary, OliviaPalette.Tertiary, OliviaPalette.Background
    )
    RadarThemeVariant.WISHBONE -> listOf(
        WishbonePalette.Primary, WishbonePalette.Secondary, WishbonePalette.Tertiary, WishbonePalette.Background
    )
    RadarThemeVariant.GIRL_IN_RED -> listOf(
        GirlInRedPalette.Primary, GirlInRedPalette.Secondary, GirlInRedPalette.Tertiary, GirlInRedPalette.Background
    )
    RadarThemeVariant.TV_GIRL -> listOf(
        TvGirlPalette.Primary, TvGirlPalette.Secondary, TvGirlPalette.Tertiary, TvGirlPalette.Background
    )
}

/**
 * Drop this in wherever your settings navigation lives. It reads/writes the
 * saved theme itself via [rememberThemeVariantState], so wiring it up is
 * just:
 *
 *   NavHost(...) {
 *       composable("settings/theme") { ThemePickerScreen() }
 *   }
 *
 * and making sure the RadarTheme(...) call that wraps your app content reads
 * from the same rememberThemeVariantState() at the top level (e.g. in
 * MainActivity) so a change here actually recolors the app immediately.
 */
@Composable
fun ThemePickerScreen(
    modifier: Modifier = Modifier,
    selected: RadarThemeVariant,
    onVariantSelected: (RadarThemeVariant) -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(20.dp)
    ) {
        Text(
            text = "Theme",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "Pick a look — yours, not just mine.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
        )

        LazyColumn(
            modifier = Modifier.padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(RadarThemeVariant.entries) { variant ->
                ThemeOptionRow(
                    variant = variant,
                    isSelected = variant == selected,
                    onClick = { onVariantSelected(variant) }
                )
            }
        }
    }
}

@Composable
private fun ThemeOptionRow(
    variant: RadarThemeVariant,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row {
            swatchesFor(variant).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(color)
                        .padding(end = 4.dp)
                )
            }
        }

        Text(
            text = variant.displayName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .padding(start = 12.dp)
                .weight(1f)
        )

        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary
            )
        }
    }
}
