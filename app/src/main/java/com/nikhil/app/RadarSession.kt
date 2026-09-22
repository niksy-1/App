Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
package com.nikhil.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/** Identity is a Firebase UID. Notification tokens never serve as sharing IDs. */
object RadarSession {
    private val signInMutex = Mutex()
    suspend fun clearPartnerCache(context: Context) {
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("last_widget_") || it == "last_success_timestamp" }
            .forEach { editor.remove(it) }
        editor.putString("last_widget_status", "Sharing unavailable").apply()
        RadarWidget().updateAll(context)
    }

    suspend fun uid(): String = signInMutex.withLock {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid
    }

    suspend fun registerDevice(): String {
        val uid = uid()
        saveToken(FirebaseMessaging.getInstance().token.await())
        return uid
    }

    suspend fun saveToken(token: String) {
        val uid = uid()
        FirebaseFirestore.getInstance().collection("deviceAccounts").document(uid).set(
            mapOf("ownerUid" to uid, "fcmToken" to token, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
    }

    suspend fun approve(partnerUid: String) {
        val uid = uid()
        require(partnerUid.matches(Regex("[A-Za-z0-9_-]{1,128}")) && partnerUid != uid) {
            "Enter your partner's new Beacon ID, not a notification token."
        }
        FirebaseFirestore.getInstance().collection("pairingApprovals").document(uid).set(
            mapOf("ownerUid" to uid, "partnerUid" to partnerUid, "updatedAt" to FieldValue.serverTimestamp())
        ).await()
    }

    suspend fun revoke() {
        FirebaseFirestore.getInstance().collection("pairingApprovals").document(uid()).delete().await()
    }

    suspend fun approvedPartner(): String? {
        uid()
        val result = FirebaseFunctions.getInstance().getHttpsCallable("getPairingStatus")
            .call().await().data as? Map<*, *>
        return if (result?.get("approved") == true) result["partnerUid"] as? String else null
    }
}
