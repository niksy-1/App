package com.nikhil.app
import android.location.Location
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

class RadarController {

    private val functions = FirebaseFunctions.getInstance()
    private val db = FirebaseFirestore.getInstance()
    private var listenerRegistration: ListenerRegistration? = null

    // 1. Request Location Ping via Cloud Function (Suspending version)
    suspend fun requestTargetLocation(targetToken: String): Boolean {
        val data = hashMapOf("targetToken" to targetToken)
        return try {
            Log.d("RadarController", "Calling requestLocation Cloud Function for: $targetToken")
            functions
                .getHttpsCallable("requestLocation")
                .call(data)
                .await()
            Log.d("RadarController", "Ping sent successfully via Cloud Function.")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RadarController", "Failed to trigger Cloud Function", e)
            false
        }
    }

    // 1. Request Location Ping via Cloud Function (Callback version for legacy/non-coroutine)
    fun requestTargetLocation(targetToken: String, onComplete: (Boolean) -> Unit) {
        val data = hashMapOf("targetToken" to targetToken)

        functions
            .getHttpsCallable("requestLocation")
            .call(data)
            .addOnSuccessListener {
                Log.d("RadarController", "Ping sent successfully via Cloud Function.")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e("RadarController", "Failed to trigger Cloud Function", e)
                onComplete(false)
            }
    }

    // 1b. Send a "Remind to Charge" nudge via Cloud Function (sendTargetNotification)
    suspend fun sendBatteryReminder(targetToken: String, message: String): Boolean {
        val data = hashMapOf(
            "targetToken" to targetToken,
            "action" to "REMIND_CHARGE",
            "message" to message
        )
        return try {
            Log.d("RadarController", "Calling sendTargetNotification Cloud Function for: $targetToken")
            functions
                .getHttpsCallable("sendTargetNotification")
                .call(data)
                .await()
            Log.d("RadarController", "Reminder sent successfully via Cloud Function.")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("RadarController", "Failed to trigger sendTargetNotification Cloud Function", e)
            false
        }
    }

    // 2. Start Real-Time Firestore Listener (Flow version)
    // minTimestamp: when set (e.g. to the moment a ping was sent), snapshots whose
    // "timestamp" field predates it are ignored client-side. This is what prevents
    // a stale cached/local document from satisfying a caller's `.first()` before the
    // freshly-uploaded location has actually arrived.
    fun observeTargetLocation(targetToken: String, minTimestamp: Long = 0L): Flow<TargetLocation?> = callbackFlow {
        Log.d("RadarController", "Registering Firestore listener for locations/$targetToken (minTimestamp=$minTimestamp) at ${System.currentTimeMillis()}")
        val registration = db.collection("locations")
            .document(targetToken)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("RadarController", "Firestore listen failed.", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                    val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                    val accuracy = snapshot.getDouble("accuracy")?.toFloat() ?: 0f
                    val timestamp = snapshot.getLong("timestamp") ?: 0L
                    val batteryPercent = snapshot.getLong("batteryPercent")?.toInt() ?: -1
                    val isCharging = snapshot.getBoolean("isCharging") ?: false

                    if (timestamp < minTimestamp) {
                        Log.d("RadarController", "Ignoring stale snapshot (ts=$timestamp < minTimestamp=$minTimestamp, diff=${minTimestamp - timestamp}ms)")
                        return@addSnapshotListener
                    }

                    Log.d("RadarController", "Target updated at ${System.currentTimeMillis()} (doc ts=$timestamp): Lat=$lat, Lng=$lng, Battery=$batteryPercent%")
                    trySend(TargetLocation(lat, lng, accuracy, timestamp, batteryPercent, isCharging))
                }
            }
        awaitClose {
            Log.d("RadarController", "Removing Firestore listener for locations/$targetToken")
            registration.remove()
        }
    }

    data class TargetLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val batteryPercent: Int = -1,
        val isCharging: Boolean = false
    )

    // 2. Start Real-Time Firestore Listener
    fun listenToTargetLocation(
        targetToken: String,
        onLocationUpdate: (targetLat: Double, targetLng: Double, accuracy: Float, timestamp: Long) -> Unit
    ) {
        // Stop any active listener before starting a new one
        stopListening()

        listenerRegistration = db.collection("locations")
            .document(targetToken)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e("RadarController", "Firestore listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && snapshot.exists()) {
                    val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                    val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                    val accuracy = snapshot.getDouble("accuracy")?.toFloat() ?: 0f
                    val timestamp = snapshot.getLong("timestamp") ?: 0L
                    val batteryPercent = snapshot.getLong("batteryPercent")?.toInt() ?: -1
                    val isCharging = snapshot.getBoolean("isCharging") ?: false

                    Log.d("RadarController", "Target updated: Lat=$lat, Lng=$lng")
                    onLocationUpdate(lat, lng, accuracy, timestamp)
                }
            }
    }

    fun stopListening() {
        listenerRegistration?.remove()
        listenerRegistration = null
    }

    // 3. Compute Distance & Bearing
    companion object {
        fun computeRelativeBearingAndDistance(
            currentLocation: Location,
            targetLat: Double,
            targetLng: Double,
            currentDeviceAzimuth: Float // Device compass heading (0-360)
        ): Pair<Float, Float> {
            val targetLocation = Location("target").apply {
                latitude = targetLat
                longitude = targetLng
            }

            val distanceMeters = currentLocation.distanceTo(targetLocation)
            val absoluteBearing = currentLocation.bearingTo(targetLocation) // -180 to 180

            // Normalize bearing to 0-360
            val normalizedBearing = (absoluteBearing + 360) % 360

            // Relative bearing = Angle arrow needs to turn relative to device heading
            val relativeBearing = (normalizedBearing - currentDeviceAzimuth + 360) % 360

            return Pair(distanceMeters, relativeBearing)
        }
    }
}