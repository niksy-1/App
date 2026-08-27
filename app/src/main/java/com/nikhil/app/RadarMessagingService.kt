package com.nikhil.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@Suppress("DEPRECATION")
class RadarMessagingService : FirebaseMessagingService() {

    private val db = FirebaseFirestore.getInstance()

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        Log.d("RadarService", "onMessageReceived triggered. Data: ${remoteMessage.data}")

        val action = remoteMessage.data["action"]
        if (action == "SEND_LOCATION") {
            Log.d("RadarService", "Action 'SEND_LOCATION' identified. Starting fetch...")
            fetchAndUploadLocation()
        } else {
            Log.d("RadarService", "Action not recognized or missing: $action")
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchAndUploadLocation() {
        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val cts = CancellationTokenSource()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.d("RadarService", "Attempting to get current location...")
                // Request a fresh, high-accuracy single location update
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cts.token
                ).await()

                if (location != null) {
                    Log.d("RadarService", "Location received: ${location.latitude}, ${location.longitude}")
                    val prefs = getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
                    var myToken = prefs.getString("fcm_token", null)

                    if (myToken.isNullOrEmpty()) {
                        Log.w("RadarService", "FCM token missing from SharedPreferences. Fetching directly from SDK...")
                        myToken = FirebaseMessaging.getInstance().token.await()
                        prefs.edit().putString("fcm_token", myToken).apply()
                    }

                    if (myToken.isNullOrEmpty()) {
                        Log.e("RadarService", "Could not retrieve an FCM token. Aborting Firestore write.")
                        return@launch
                    }

                    val (batteryPercent, isCharging) = getBatteryInfo()

                    val payload = hashMapOf(
                        "latitude" to location.latitude,
                        "longitude" to location.longitude,
                        "accuracy" to location.accuracy,
                        "timestamp" to System.currentTimeMillis(),
                        "batteryPercent" to batteryPercent,
                        "isCharging" to isCharging
                    )

                    Log.d("RadarService", "Writing to Firestore: locations/$myToken")
                    // Write to Firestore under the 'locations' collection using device token/ID
                    db.collection("locations")
                        .document(myToken)
                        .set(payload)
                        .await()

                    Log.d("RadarService", "Location successfully written to Firestore!")
                } else {
                    Log.w("RadarService", "Location fetched was null. GPS might be disabled or no fix available.")
                }
            } catch (e: Exception) {
                Log.e("RadarService", "Failed to retrieve or upload location. Exception: ${e.message}", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val prefs = getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        prefs.edit().putString("fcm_token", token).apply()
    }

    private fun getBatteryInfo(): Pair<Int, Boolean> {
        val batteryStatus: Intent? = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (scale > 0) (level * 100 / scale.toFloat()).toInt() else -1

        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        return Pair(batteryPct, isCharging)
    }
}