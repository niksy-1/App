package com.nikhil.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import android.graphics.BitmapFactory
import androidx.glance.GlanceTheme
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class RadarWidget : GlanceAppWidget() {

    companion object {
        private val SMALL_SQUARE = DpSize(100.dp, 100.dp) // 2x2 approx
        private val HORIZONTAL_RECT = DpSize(200.dp, 50.dp) // 4x1 approx
        private val LARGE_RECT = DpSize(200.dp, 100.dp) // 4x2 approx
    }

    override val sizeMode: SizeMode = SizeMode.Responsive(
        setOf(SMALL_SQUARE, HORIZONTAL_RECT, LARGE_RECT)
    )

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent(context)
            }
        }
    }

    @Composable
    private fun WidgetContent(context: Context) {
        val size = LocalSize.current
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        
        // Dynamic Background
        val useBgImage = prefs.getBoolean("use_bg_image", false)
        val bgImagePath = prefs.getString("widget_bg_image_path", "")
        val bgColorInt = prefs.getInt("widget_bg_color", 0xFFFCE7F3.toInt())
        
        val isDark: Boolean
        val rootModifier: GlanceModifier
        
        if (useBgImage && !bgImagePath.isNullOrEmpty() && File(bgImagePath).exists()) {
            isDark = true
            val bitmap = BitmapFactory.decodeFile(bgImagePath)
            if (bitmap != null) {
                rootModifier = GlanceModifier.fillMaxSize().background(ImageProvider(bitmap))
            } else {
                rootModifier = GlanceModifier.fillMaxSize().background(Color(bgColorInt))
            }
        } else {
            val r = (bgColorInt shr 16 and 0xFF) / 255f
            val g = (bgColorInt shr 8 and 0xFF) / 255f
            val b = (bgColorInt and 0xFF) / 255f
            val luminance = 0.299f * r + 0.587f * g + 0.114f * b
            isDark = luminance < 0.5f
            rootModifier = GlanceModifier.fillMaxSize().background(Color(bgColorInt))
        }
        
        val secondaryColor = if (isDark) Color.LightGray else Color.DarkGray
        val primaryColor = if (isDark) Color(0xFFFBCFE8) else Color(0xFFDB2777)
        
        fun createColorProvider(color: Color) = object : ColorProvider {
            override fun getColor(context: Context): Color = color
        }
        
        val primaryProvider = createColorProvider(primaryColor)
        val secondaryProvider = createColorProvider(secondaryColor)
        
        val lastDist = prefs.getString("last_widget_distance", "-- m") ?: "-- m"
        val lastStatus = prefs.getString("last_widget_status", "Standby") ?: "Standby"
        val battery = prefs.getInt("last_widget_battery", -1)
        val isCharging = prefs.getBoolean("last_widget_is_charging", false)
        val targetLat = prefs.getString("last_widget_lat", "") ?: ""
        val targetLng = prefs.getString("last_widget_lng", "") ?: ""
        val lastTimestamp = prefs.getLong("last_success_timestamp", 0L)

        val batteryIcon = if (isCharging) "⚡" else "🔋"
        val batteryText = if (battery != -1) "$batteryIcon $battery%" else ""
        val timeText = if (lastTimestamp > 0) "Updated: ${formatRelativeTime(lastTimestamp)}" else ""

        Box(modifier = rootModifier) {
            if (useBgImage) {
                Box(modifier = GlanceModifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f))) {}
            }
            
            // Determine layout based on size
            if (size.width >= 200.dp && size.height < 80.dp) {
                // 4x1 - Compact Horizontal
                Row(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(text = lastDist, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                        if (batteryText.isNotEmpty()) {
                            Text(text = "$batteryText | $timeText", style = TextStyle(fontSize = 10.sp, color = secondaryProvider))
                        }
                    }
                    Spacer(modifier = GlanceModifier.width(8.dp))
                    Button(text = "Ping", onClick = actionRunCallback<RefreshWidgetCallback>())
                    if (targetLat.isNotEmpty()) {
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        Button(text = "Maps", onClick = actionStartActivity(getMapsIntent(targetLat, targetLng)))
                    }
                }
            } else if (size.width >= 200.dp && size.height >= 80.dp) {
                // 4x2 - Full Info
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = "Target Distance", style = TextStyle(fontSize = 12.sp, color = secondaryProvider))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = lastDist, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                        if (batteryText.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.width(12.dp))
                            Text(text = batteryText, style = TextStyle(fontSize = 16.sp, color = secondaryProvider))
                        }
                    }
                    Text(text = lastStatus, style = TextStyle(fontSize = 12.sp, color = secondaryProvider))
                    if (timeText.isNotEmpty()) {
                        Text(text = timeText, style = TextStyle(fontSize = 10.sp, color = secondaryProvider))
                    }
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Button(text = "Ping Target", onClick = actionRunCallback<RefreshWidgetCallback>(), modifier = GlanceModifier.defaultWeight())
                        if (targetLat.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.width(8.dp))
                            Button(text = "Open Maps", onClick = actionStartActivity(getMapsIntent(targetLat, targetLng)), modifier = GlanceModifier.defaultWeight())
                        }
                    }
                }
            } else {
                // 2x2 - Standard Square
                Column(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = lastDist, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                    if (batteryText.isNotEmpty()) {
                        Text(text = batteryText, style = TextStyle(fontSize = 12.sp, color = secondaryProvider))
                    }
                    Text(text = lastStatus, style = TextStyle(fontSize = 10.sp, color = secondaryProvider))
                    if (timeText.isNotEmpty()) {
                        Text(text = timeText, style = TextStyle(fontSize = 9.sp, color = secondaryProvider))
                    }
                    Spacer(modifier = GlanceModifier.height(8.dp))
                    Row(modifier = GlanceModifier.fillMaxWidth()) {
                        Button(text = "Ping", onClick = actionRunCallback<RefreshWidgetCallback>(), modifier = GlanceModifier.defaultWeight())
                        if (targetLat.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.width(4.dp))
                            Button(text = "Maps", onClick = actionStartActivity(getMapsIntent(targetLat, targetLng)), modifier = GlanceModifier.defaultWeight())
                        }
                    }
                }
            }
        }
    }

    private fun getMapsIntent(lat: String, lng: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("geo:$lat,$lng?q=$lat,$lng(Target)")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        
        return when {
            diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
            diff < TimeUnit.HOURS.toMillis(1) -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
            diff < TimeUnit.DAYS.toMillis(1) -> {
                val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
            else -> {
                val sdf = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                sdf.format(Date(timestamp))
            }
        }
    }
}

class RadarWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RadarWidget()
}

class RefreshWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d("RadarWidget", "Refresh button clicked on widget.")
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val targetToken = prefs.getString("PARTNER_FCM_TOKEN", "") ?: ""

        if (targetToken.isEmpty()) {
            Log.w("RadarWidget", "No partner token found.")
            prefs.edit().putString("last_widget_status", "No target saved").apply()
            RadarWidget().updateAll(context)
            return
        }

        // 1. Immediate UI update for feedback
        prefs.edit().putString("last_widget_status", "Pinging target...").apply()
        RadarWidget().updateAll(context)

        // 2. Trigger Expedited Work for reliability and survival
        // This starts almost instantly and bypasses OS throttling.
        RefreshWorker.enqueue(context)
    }
}
