package com.nikhil.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.media.AudioAttributes
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await

@Suppress("DEPRECATION")
class RadarMessagingService : FirebaseMessagingService() {

    private val db = FirebaseFirestore.getInstance()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("RadarService", "onMessageReceived triggered at ${System.currentTimeMillis()}. Data: ${remoteMessage.data}")

        val action = remoteMessage.data["action"]
        when (action) {
            "SEND_LOCATION" -> {
                Log.d("RadarService", "Action 'SEND_LOCATION' identified. Starting fetch...")
                fetchAndUploadLocation()
            }
            "REMIND_CHARGE" -> {
                Log.d("RadarService", "Action 'REMIND_CHARGE' identified. Showing alert...")
                val message = remoteMessage.data["message"]?.takeIf { it.isNotBlank() }
                    ?: "Please plug in your phone 🥺"
                showChargeReminderNotification(message)
            }
            else -> {
                Log.d("RadarService", "Action not recognized or missing: $action")
            }
        }
    }

    // High-priority, heads-up notification on a dedicated alerts channel. Runs
    // synchronously (no goAsync needed) — posting a notification is a fast local
    // call, unlike fetchAndUploadLocation()'s network round trip.
    private fun showChargeReminderNotification(message: String) {
        val channelId = "radar_alerts_v3" // Updated ID to force system to register new sound settings
        val soundUri = Uri.parse("android.resource://${packageName}/${R.raw.strum}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .build()

            val channel = NotificationChannel(
                channelId,
                "Radar Alerts",
                NotificationManager.IMPORTANCE_HIGH // required for heads-up display
            ).apply {
                description = "Nudges to plug in your phone, etc."
                setSound(soundUri, audioAttributes)
                enableVibration(true)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        // POST_NOTIFICATIONS is a runtime permission on API 33+. If the user hasn't
        // granted it, NotificationManagerCompat.notify() would either throw or
        // silently drop the notification depending on OS version — check first and
        // just log rather than crash the FCM callback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w("RadarService", "POST_NOTIFICATIONS not granted — cannot show charge reminder.")
            return
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Low Battery Reminder ⚡")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH) // pre-O heads-up equivalent
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setSound(soundUri)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID_CHARGE_REMINDER, notification)
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndUploadLocation() {
        // NOTE: goAsync() is a BroadcastReceiver API, not a Service one — it doesn't
        // exist on FirebaseMessagingService, which extends Service. The FCM SDK
        // already invokes onMessageReceived on a background thread it owns (not the
        // main thread), so instead of firing an unscoped coroutine and returning
        // immediately, we block that worker thread with runBlocking until the work
        // finishes. That keeps the service (and process) alive for the duration
        // without needing any extra lifecycle API.
        val appContext = applicationContext
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(appContext)
        val startTime = System.currentTimeMillis()

        fun elapsed() = "${System.currentTimeMillis() - startTime}ms"

        runBlocking(Dispatchers.IO) {
            try {
                if (ContextCompat.checkSelfPermission(appContext, android.Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED
                ) {
                    Log.e("RadarService", "[${elapsed()}] ACCESS_FINE_LOCATION not granted — cannot fetch location.")
                    return@runBlocking
                }

                val prefs = appContext.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
                var myToken = prefs.getString("fcm_token", null)

                if (myToken.isNullOrEmpty()) {
                    Log.w("RadarService", "[${elapsed()}] FCM token missing from SharedPreferences. Fetching directly from SDK...")
                    myToken = FirebaseMessaging.getInstance().token.await()
                    prefs.edit().putString("fcm_token", myToken).apply()
                    Log.d("RadarService", "[${elapsed()}] FCM token fetched from SDK.")
                }

                if (myToken.isNullOrEmpty()) {
                    Log.e("RadarService", "[${elapsed()}] Could not retrieve an FCM token. Aborting Firestore write.")
                    return@runBlocking
                }

                // Fast path: lastLocation is typically served from cache in well under
                // a second, vs. a fresh high-accuracy fix which can take 10-30s+ on a
                // cold GPS lock. Writing this immediately gives the requester's
                // Firestore listener something to resolve on right away, mirroring the
                // same optimization RefreshWorker already uses on the requester side.
                Log.d("RadarService", "[${elapsed()}] Requesting lastLocation (fast path)...")
                val lastLoc = fusedLocationClient.lastLocation.await()
                if (lastLoc != null) {
                    Log.d("RadarService", "[${elapsed()}] lastLocation available (age unknown to us): " +
                            "${lastLoc.latitude}, ${lastLoc.longitude}. Writing to Firestore immediately.")
                    uploadLocation(appContext, myToken, lastLoc, elapsed = { elapsed() })
                } else {
                    Log.w("RadarService", "[${elapsed()}] lastLocation was null — no cached fix available, waiting on fresh fix only.")
                }

                // Now get a fresh, high-accuracy fix and overwrite with the better
                // value once it arrives. This is the slow step — logged distinctly so
                // you can see in logcat exactly how long the GPS fix itself takes.
                Log.d("RadarService", "[${elapsed()}] Requesting fresh high-accuracy location...")
                val cts = CancellationTokenSource()
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()

                if (location != null) {
                    Log.d("RadarService", "[${elapsed()}] Fresh high-accuracy location received: " +
                            "${location.latitude}, ${location.longitude} (accuracy=${location.accuracy}m). Overwriting Firestore.")
                    uploadLocation(appContext, myToken, location, elapsed = { elapsed() })
                    Log.d("RadarService", "[${elapsed()}] Location successfully written to Firestore!")
                } else {
                    Log.w("RadarService", "[${elapsed()}] Fresh location fetch returned null. " +
                            "GPS might be disabled, or no fix available. " +
                            if (lastLoc != null) "Requester already has the lastLocation fallback written above."
                            else "No fallback was available either — requester's ping will time out.")
                }
            } catch (e: Exception) {
                Log.e("RadarService", "[${elapsed()}] Failed to retrieve or upload location. Exception: ${e.message}", e)
            }
        }

        Log.d("RadarService", "[${elapsed()}] fetchAndUploadLocation() finished.")
    }

    private suspend fun uploadLocation(
        context: Context,
        myToken: String,
        location: android.location.Location,
        elapsed: () -> String
    ) {
        val (batteryPercent, isCharging) = getBatteryInfo(context)

        val payload = hashMapOf(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracy" to location.accuracy,
            "timestamp" to System.currentTimeMillis(),
            "batteryPercent" to batteryPercent,
            "isCharging" to isCharging
        )

        Log.d("RadarService", "[${elapsed()}] Writing to Firestore: locations/$myToken")
        db.collection("locations")
            .document(myToken)
            .set(payload, SetOptions.merge())
            .await()
        Log.d("RadarService", "[${elapsed()}] Firestore write confirmed for locations/$myToken")
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }

    private fun getBatteryInfo(context: Context): Pair<Int, Boolean> {
        val batteryStatus: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }

    companion object {
        private const val NOTIFICATION_ID_CHARGE_REMINDER = 5501
    }
}