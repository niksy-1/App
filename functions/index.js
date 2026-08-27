const { onCall, HttpsError } = require("firebase-functions/v2/https");
const admin = require("firebase-admin");

admin.initializeApp();

exports.requestLocation = onCall(async (request) => {
  const targetToken = request.data?.targetToken;

  if (!targetToken) {
    throw new HttpsError(
      "invalid-argument",
      "Target FCM token is missing."
    );
  }

  const message = {
    data: {
      action: "SEND_LOCATION",
    },
    token: targetToken,
    android: {
      priority: "high",
    },
  };

  try {
    const response = await admin.messaging().send(message);
    return { success: true, messageId: response };
  } catch (error) {
    console.error("FCM send error:", error);
    throw new HttpsError("internal", "Failed to send FCM message.");
  }
});