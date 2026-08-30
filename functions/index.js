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

exports.sendTargetNotification = onCall(async (request) => {
  const targetToken = request.data?.targetToken;
  const action = request.data?.action;
  const rawMessage = request.data?.message;

  if (!targetToken) {
    throw new HttpsError(
      "invalid-argument",
      "Target FCM token is missing."
    );
  }

  if (action !== "REMIND_CHARGE") {
    throw new HttpsError(
      "invalid-argument",
      `Unsupported action: ${action}`
    );
  }

  const safeMessage =
    typeof rawMessage === "string" && rawMessage.trim().length > 0 ?
      rawMessage.trim().slice(0, 200) :
      "Please plug in your phone 🥺";

  const message = {
    data: {
      action: "REMIND_CHARGE",
      message: safeMessage,
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

    if (
      error.code === "messaging/registration-token-not-registered" ||
      error.code === "messaging/invalid-registration-token"
    ) {
      throw new HttpsError(
        "not-found",
        "The target's device token is no longer valid."
      );
    }

    throw new HttpsError("internal", "Failed to send FCM message.");
  }
});