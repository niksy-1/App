package com.nikhil.app

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.tasks.await

/** Identity is a Firebase UID. Notification tokens never serve as sharing IDs. */
object RadarSession {
    data class PairingRequest(
        val requesterUid: String,
        val displayName: String,
        val email: String,
    )

    suspend fun clearPartnerCache(context: Context) {
        val prefs = context.getSharedPreferences("RadarPrefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("last_widget_") || it == "last_success_timestamp" }
            .forEach { editor.remove(it) }
        editor.putString("last_widget_status", "Sharing unavailable").apply()
        RadarWidget().updateAll(context)
    }

    suspend fun uid(): String = requireNotNull(FirebaseAuth.getInstance().currentUser?.uid) {
        "Sign in to your account first."
    }

    suspend fun signIn(email: String, password: String): String =
        requireNotNull(FirebaseAuth.getInstance()
            .signInWithEmailAndPassword(email.trim(), password).await().user).uid

    suspend fun sendPasswordReset(email: String) {
        FirebaseAuth.getInstance().sendPasswordResetEmail(email.trim()).await()
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

    suspend fun requestPartner(email: String): String {
        uid()
        val result = FirebaseFunctions.getInstance().getHttpsCallable("requestPartnerByEmail")
            .call(mapOf("email" to email.trim())).await().data as? Map<*, *>
        return result?.get("partnerName") as? String ?: "your partner"
    }

    suspend fun incomingRequests(): List<PairingRequest> {
        uid()
        val result = FirebaseFunctions.getInstance().getHttpsCallable("getIncomingPairingRequests")
            .call().await().data as? Map<*, *>
        val requests = result?.get("requests") as? List<*> ?: return emptyList()
        return requests.mapNotNull { raw ->
            val item = raw as? Map<*, *> ?: return@mapNotNull null
            val requesterUid = item["requesterUid"] as? String ?: return@mapNotNull null
            PairingRequest(
                requesterUid = requesterUid,
                displayName = item["displayName"] as? String ?: "Your partner",
                email = item["email"] as? String ?: "",
            )
        }
    }

    suspend fun respondToRequest(requesterUid: String, accept: Boolean) {
        uid()
        FirebaseFunctions.getInstance().getHttpsCallable("respondToPairingRequest")
            .call(mapOf("requesterUid" to requesterUid, "accept" to accept)).await()
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
