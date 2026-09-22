Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
const {HttpsError} = require("firebase-functions/v2/https");

const validUid = (uid) => typeof uid === "string" &&
  /^[A-Za-z0-9_-]{1,128}$/.test(uid);

/**
 * Creates handlers with explicit authentication for Admin SDK operations.
 * @param {object} db Firestore instance.
 * @param {object} messaging Messaging client.
 * @param {function(): number} now Millisecond clock.
 * @return {object} Callable handlers.
 */
function createHandlers(db, messaging, now = Date.now) {
  const caller = (request) => {
    if (!validUid(request.auth?.uid)) {
      throw new HttpsError("unauthenticated", "Sign in first.");
    }
    return request.auth.uid;
  };

  /**
   * @param {object} request Authenticated callable request.
   * @return {Promise<object>} Mutual approval status for the caller.
   */
  async function getPairingStatus(request) {
    const uid = caller(request);
    return db.runTransaction(async (tx) => {
      const own = await tx.get(db.doc(`pairingApprovals/${uid}`));
      const partnerUid = own.data()?.partnerUid;
      if (!validUid(partnerUid) || partnerUid === uid) return {approved: false};
      const other = await tx.get(db.doc(`pairingApprovals/${partnerUid}`));
      return other.data()?.partnerUid === uid ?
        {approved: true, partnerUid} : {approved: false};
    }, {readOnly: true});
  }

  /**
   * @param {object} request Authenticated callable request.
   * @param {string} action Notification action.
   * @param {string=} message Optional reminder text.
   * @return {Promise<object>} Delivery result.
   */
  async function send(request, action, message) {
    const uid = caller(request);
    const targetUid = request.data?.targetUid;
    if (!validUid(targetUid) || targetUid === uid) {
      throw new HttpsError(
          "invalid-argument", "A partner Beacon ID is required.");
    }
    // Resolve a private token only after checking both independent approvals.
    const token = await db.runTransaction(async (tx) => {
      const own = await tx.get(db.doc(`pairingApprovals/${uid}`));
      const other = await tx.get(db.doc(`pairingApprovals/${targetUid}`));
      if (own.data()?.partnerUid !== targetUid ||
          other.data()?.partnerUid !== uid) {
        throw new HttpsError(
            "permission-denied", "Both partners must approve sharing.");
      }
      const device = await tx.get(db.doc(`deviceAccounts/${targetUid}`));
      const fcmToken = device.data()?.fcmToken;
      if (typeof fcmToken !== "string" || !fcmToken.length ||
          fcmToken.length > 4096) {
        throw new HttpsError(
            "failed-precondition", "Partner must open the updated app first.");
      }
      const limitRef = db.doc(`notificationLimits/${uid}`);
      const limit = await tx.get(limitRef);
      const time = now();
      if (time - (limit.data()?.lastSentAt ?? 0) < 10000) {
        throw new HttpsError(
            "resource-exhausted", "Wait before sending another request.");
      }
      tx.set(limitRef, {lastSentAt: time});
      return fcmToken;
    });
    try {
      const messageId = await messaging.send({
        token, android: {priority: "high"},
        data: {action, senderUid: uid, targetUid,
          ...(message ? {message} : {})},
      });
      return {success: true, messageId};
    } catch (error) {
      if (["messaging/registration-token-not-registered",
        "messaging/invalid-registration-token"].includes(error.code)) {
        throw new HttpsError(
            "not-found", "Partner must reopen the app for notifications.");
      }
      throw new HttpsError("internal", "Failed to deliver notification.");
    }
  }

  return {
    getPairingStatus,
    requestLocation: (request) => send(request, "SEND_LOCATION"),
    sendTargetNotification: (request) => {
      caller(request);
      if (request.data?.action !== "REMIND_CHARGE") {
        throw new HttpsError(
            "invalid-argument", "Unsupported notification action.");
      }
      const raw = request.data?.message;
      if (raw !== undefined && (typeof raw !== "string" || raw.length > 200)) {
        throw new HttpsError(
            "invalid-argument", "Message must be at most 200 characters.");
      }
      return send(request, "REMIND_CHARGE",
          raw?.trim() || "Please plug in your phone 🥺");
    },
  };
}

module.exports = {createHandlers};
