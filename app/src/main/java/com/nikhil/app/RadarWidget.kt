Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
package com.nikhil.app

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
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
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider
import java.io.File

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
        val bgColorInt = prefs.getInt("widget_bg_color", 0xFF180F16.toInt()) // plum-black default

        val isDark: Boolean
        val rootModifier: GlanceModifier

        if (useBgImage && !bgImagePath.isNullOrEmpty() && File(bgImagePath).exists()) {
            isDark = true
            val bitmap = decodeSampledBitmap(bgImagePath)
            if (bitmap != null) {
                rootModifier = GlanceModifier.fillMaxSize().background(ImageProvider(bitmap))
            } else {
                Log.e("RadarWidget", "Failed to decode widget background from $bgImagePath — falling back to color.")
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

        // ColorProvider(color: Color) is restricted to androidx.glance's own internal
        // use (@RestrictTo LIBRARY_GROUP) — it compiles from source but fails the
        // RestrictedApi lint check, which AGP treats as a build error. The public,
        // sanctioned way to get a ColorProvider for a specific fixed color is via a
        // color resource: see res/values/colors.xml for widget_text_primary_dark /
        // widget_text_primary_light / widget_text_secondary_dark /
        // widget_text_secondary_light (#C97B8C / #7A2E42 / #B8A8B0 / #6B5560).
        val primaryProvider = ColorProvider(if (isDark) R.color.widget_text_primary_dark else R.color.widget_text_primary_light)
        val secondaryProvider = ColorProvider(if (isDark) R.color.widget_text_secondary_dark else R.color.widget_text_secondary_light)

        val lastDist = prefs.getString("last_widget_distance", "-- m") ?: "-- m"
        val lastStatus = prefs.getString("last_widget_status", "Standby") ?: "Standby"
        val battery = prefs.getInt("last_widget_battery", -1)
        val isCharging = prefs.getBoolean("last_widget_is_charging", false)
        val targetLat = prefs.getString("last_widget_lat", "") ?: ""
        val targetLng = prefs.getString("last_widget_lng", "") ?: ""
        val lastNote = prefs.getString("last_widget_note", "") ?: ""
        val showDist = prefs.getBoolean("show_distance", true)
        val batteryIcon = if (isCharging) "⚡" else "🔋"
        val batteryText = if (battery != -1) "$batteryIcon $battery%" else ""

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
                        if (showDist) {
                            Text(text = lastDist, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                        }
                        if (batteryText.isNotEmpty()) {
                            Text(
                                text = batteryText,
                                style = TextStyle(fontSize = 10.sp, color = secondaryProvider),
                                modifier = GlanceModifier.clickable(actionRunCallback<RemindWidgetCallback>())
                            )
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
                    if (showDist) {
                        Text(text = "Target Distance", style = TextStyle(fontSize = 12.sp, color = secondaryProvider))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (showDist) {
                            Text(text = lastDist, style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                        }
                        if (batteryText.isNotEmpty()) {
                            Spacer(modifier = GlanceModifier.width(12.dp))
                            Text(
                                text = batteryText,
                                style = TextStyle(fontSize = 16.sp, color = secondaryProvider),
                                modifier = GlanceModifier.clickable(actionRunCallback<RemindWidgetCallback>())
                            )
                        }
                    }
                    Text(text = lastStatus, style = TextStyle(fontSize = 12.sp, color = secondaryProvider))
                    NoteViewport(
                        note = lastNote,
                        height = 24.dp,
                        textColor = primaryProvider,
                        textSize = 13.sp,
                        modifier = GlanceModifier.padding(top = 4.dp)
                    )
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
                    if (showDist) {
                        Text(text = lastDist, style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = primaryProvider))
                    }
                    if (batteryText.isNotEmpty()) {
                        Text(
                            text = batteryText,
                            style = TextStyle(fontSize = 12.sp, color = secondaryProvider),
                            modifier = GlanceModifier.clickable(actionRunCallback<RemindWidgetCallback>())
                        )
                    }
                    Text(text = lastStatus, style = TextStyle(fontSize = 10.sp, color = secondaryProvider))
                    NoteViewport(
                        note = lastNote,
                        height = 20.dp,
                        textColor = primaryProvider,
                        textSize = 11.sp,
                        modifier = GlanceModifier.padding(top = 4.dp)
                    )
                    Spacer(modifier = GlanceModifier.height(4.dp))
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

    /** Keeps long notes inside a fixed, scrollable viewport above the buttons. */
    @Composable
    private fun NoteViewport(
        note: String,
        height: Dp,
        textColor: ColorProvider,
        textSize: androidx.compose.ui.unit.TextUnit,
        modifier: GlanceModifier = GlanceModifier
    ) {
        if (note.isEmpty()) return

        LazyColumn(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(height)
                .then(modifier),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(note.chunked(28)) { chunk ->
                Text(
                    text = "\"$chunk\"",
                    style = TextStyle(fontSize = textSize, color = textColor)
                )
            }
        }
    }

    // The widget's largest actual render size (LARGE_RECT = 200dp x 100dp) never needs
    // a source image anywhere near 1000x500px. Decoding at full resolution produces an
    // ARGB_8888 bitmap of ~2MB, which — once embedded in the RemoteViews Bundle Glance
    // sends to the launcher over Binder — can exceed the shared ~1MB transaction limit
    // and throw TransactionTooLargeException. That failure is silent from the caller's
    // perspective: the widget update simply doesn't take effect, so the background
    // looks like it "doesn't update" when you toggle to an image. Downsampling here
    // keeps the payload small and reliable.
    private fun decodeSampledBitmap(path: String): android.graphics.Bitmap? {
        val maxDimensionPx = 480
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, boundsOptions)

            var inSampleSize = 1
            val largestSide = maxOf(boundsOptions.outWidth, boundsOptions.outHeight)
            if (largestSide > maxDimensionPx) {
                while (largestSide / (inSampleSize * 2) >= maxDimensionPx) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (e: Exception) {
            Log.e("RadarWidget", "Exception decoding widget background from $path", e)
            null
        }
    }

    private fun getMapsIntent(lat: String, lng: String): Intent {
        return Intent(Intent.ACTION_VIEW).apply {
            data = "geo:$lat,$lng?q=$lat,$lng(Target)".toUri()
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
        val targetUid = prefs.getString("PARTNER_UID", "") ?: ""

        if (targetUid.isEmpty()) {
            Log.w("RadarWidget", "No partner token found.")
            prefs.edit { putString("last_widget_status", "No target saved") }
            RadarWidget().updateAll(context)
            return
        }

        // 1. Immediate UI update for feedback
        prefs.edit { putString("last_widget_status", "Pinging target...") }
        RadarWidget().updateAll(context)

        // 2. Trigger Expedited Work for reliability and survival
        // This starts almost instantly and bypasses OS throttling.
        RefreshWorker.enqueue(context)
    }
}

// Tapping the battery readout on the widget sends a "Remind to Charge" nudge to
// the target directly, without opening the app. This is a single Cloud Function
// call (no location fetch, no worker needed), so it's handled inline here rather
// than delegated to RefreshWorker.
class RemindWidgetCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        Log.d("RadarWidget", "Battery reminder tapped on widget.")
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val targetUid = prefs.getString("PARTNER_UID", "") ?: ""

        if (targetUid.isEmpty()) {
            Log.w("RadarWidget", "No partner token found; can't send reminder.")
            prefs.edit { putString("last_widget_status", "No target saved") }
            RadarWidget().updateAll(context)
            return
        }

        prefs.edit { putString("last_widget_status", "Sending reminder...") }
        RadarWidget().updateAll(context)

        val sent = RadarController().sendBatteryReminder(
            targetUid,
            "Please plug in your phone 🥺"
        )

        prefs.edit { putString("last_widget_status", if (sent) "Reminder sent." else "Reminder failed.") }
        RadarWidget().updateAll(context)
    }
}
