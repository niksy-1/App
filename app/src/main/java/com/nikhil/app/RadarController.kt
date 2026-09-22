Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
package com.nikhil.app
import android.location.Location
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
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
    suspend fun requestTargetLocation(targetUid: String): Boolean {
        val data = hashMapOf("targetUid" to targetUid)
        return try {
            RadarSession.uid()
            Log.d("RadarController", "Calling requestLocation Cloud Function for: $targetUid")
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
    fun requestTargetLocation(targetUid: String, onComplete: (Boolean) -> Unit) {
        val data = hashMapOf("targetUid" to targetUid)

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
    suspend fun sendBatteryReminder(targetUid: String, message: String): Boolean {
        val data = hashMapOf(
            "targetUid" to targetUid,
            "action" to "REMIND_CHARGE",
            "message" to message
        )
        return try {
            RadarSession.uid()
            Log.d("RadarController", "Calling sendTargetNotification Cloud Function for: $targetUid")
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
    fun observeTargetLocation(targetUid: String, minTimestamp: Long = 0L): Flow<TargetLocation?> = callbackFlow {
        RadarSession.uid()
        Log.d("RadarController", "Registering Firestore listener for locations/$targetUid (minTimestamp=$minTimestamp) at ${System.currentTimeMillis()}")
        val registration = db.collection("locationsV2")
            .document(targetUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e("RadarController", "Firestore listen failed.", error)
                    close(error)
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.metadata.isFromCache && snapshot.exists()) {
                    val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                    val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                    val accuracy = snapshot.getDouble("accuracy")?.toFloat() ?: 0f
                    val timestamp = snapshot.getTimestamp("timestamp")?.toDate()?.time ?: 0L
                    val batteryPercent = snapshot.getLong("batteryPercent")?.toInt() ?: -1
                    val isCharging = snapshot.getBoolean("isCharging") ?: false
                    val note = snapshot.getString("note")

                    if (timestamp < minTimestamp) {
                        Log.d("RadarController", "Ignoring stale snapshot (ts=$timestamp < minTimestamp=$minTimestamp, diff=${minTimestamp - timestamp}ms)")
                        return@addSnapshotListener
                    }

                    Log.d("RadarController", "Target updated at ${System.currentTimeMillis()} (doc ts=$timestamp): Lat=$lat, Lng=$lng, Battery=$batteryPercent%, Note=$note")
                    trySend(TargetLocation(lat, lng, accuracy, timestamp, batteryPercent, isCharging, note))
                }
            }
        awaitClose {
            Log.d("RadarController", "Removing Firestore listener for locations/$targetUid")
            registration.remove()
        }
    }

    data class TargetLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long,
        val batteryPercent: Int = -1,
        val isCharging: Boolean = false,
        val note: String? = null
    )

    // 2. Start Real-Time Firestore Listener
    fun listenToTargetLocation(
        targetUid: String,
        onLocationUpdate: (targetLat: Double, targetLng: Double, accuracy: Float, timestamp: Long) -> Unit
    ) {
        // Stop any active listener before starting a new one
        stopListening()

        listenerRegistration = db.collection("locationsV2")
            .document(targetUid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    Log.e("RadarController", "Firestore listen failed.", error)
                    return@addSnapshotListener
                }

                if (snapshot != null && !snapshot.metadata.isFromCache && snapshot.exists()) {
                    val lat = snapshot.getDouble("latitude") ?: return@addSnapshotListener
                    val lng = snapshot.getDouble("longitude") ?: return@addSnapshotListener
                    val accuracy = snapshot.getDouble("accuracy")?.toFloat() ?: 0f
                    val timestamp = snapshot.getTimestamp("timestamp")?.toDate()?.time ?: 0L
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

    suspend fun updateMyNote(myUid: String, partnerUid: String, note: String) {
        val uid = RadarSession.uid()
        require(myUid == uid && note.isNotBlank())
        require(RadarSession.approvedPartner() == partnerUid) { "Both partners must approve sharing first." }
        val batch = db.batch()
        batch.set(db.collection("locationsV2").document(uid), mapOf(
            "ownerUid" to uid, "note" to note.take(100),
            "updatedAt" to FieldValue.serverTimestamp()
        ), com.google.firebase.firestore.SetOptions.merge())
        batch.set(noteCollection(uid, partnerUid).document(), mapOf(
            "senderId" to uid, "targetId" to partnerUid,
            "text" to note.take(100), "timestamp" to FieldValue.serverTimestamp()
        ))
        batch.commit().await()
    }

    private fun noteCollection(a: String, b: String): com.google.firebase.firestore.CollectionReference {
        val pair = listOf(a, b).sorted()
        return db.collection("pairNotes").document(pair[0]).collection("partners")
            .document(pair[1]).collection("entries")
    }

    fun observeNoteHistory(myUid: String, partnerUid: String): Flow<List<NoteRecord>> = callbackFlow {
        val uid = RadarSession.uid()
        if (myUid != uid || partnerUid.isBlank()) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }
        val registration = noteCollection(uid, partnerUid)
            .orderBy("timestamp", Query.Direction.DESCENDING).limit(100)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    trySend(emptyList())
                    close(error)
                } else if (snapshot != null && !snapshot.metadata.isFromCache) {
                    trySend(snapshot.documents.mapNotNull { it.toNoteRecord() })
                }
            }
        awaitClose { registration.remove() }
    }

    private fun com.google.firebase.firestore.DocumentSnapshot.toNoteRecord(): NoteRecord? {
        val text = getString("text") ?: return null
        val senderId = getString("senderId") ?: return null
        val timestamp = when (val ts = get("timestamp")) {
            is Long -> ts
            is com.google.firebase.Timestamp -> ts.toDate().time
            is Number -> ts.toLong()
            else -> null
        } ?: return null
        return NoteRecord(id, senderId, text, timestamp)
    }

    data class NoteRecord(
        val id: String,
        val senderId: String,
        val text: String,
        val timestamp: Long
    )

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