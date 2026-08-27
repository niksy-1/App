package com.nikhil.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class RadarMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.w("FCM_TOKEN", "Your token changed. You will need to update the other device.")
    }
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        if (remoteMessage.data.isNotEmpty()) {
            val action = remoteMessage.data["action"]
            Log.d("RadarService", "Received an FCM ping with action: $action")
            if (action == "SEND_LOCATION") {
                Log.d("RadarService", "Target acquired. Preparing to fetch location...")
            }
        }
    }
}