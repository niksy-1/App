const {HttpsError} = require("firebase-functions/v2/https");

const validUid = (uid) => typeof uid === "string" &&
  /^[A-Za-z0-9_-]{1,128}$/.test(uid);
const validEmail = (email) => typeof email === "string" &&
  email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email);

/**
 * Creates handlers with explicit authentication for Admin SDK operations.
 * @param {object} db Firestore instance.
 * @param {object} messaging Messaging client.
 * @param {object} auth Firebase Auth Admin client.
 * @param {function(): number} now Millisecond clock.
 * @return {object} Callable handlers.
 */
function createHandlers(db, messaging, auth, now = Date.now) {
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
   * Requests a connection to an existing account by email.
   * @param {object} request Authenticated callable request.
   * @return {Promise<object>} Request status and partner name.
   */
  async function requestPartnerByEmail(request) {
    const uid = caller(request);
    const email = request.data?.email?.trim().toLowerCase();
    if (!validEmail(email)) {
      throw new HttpsError("invalid-argument", "Enter a valid email address.");
    }
    let target;
    try {
      target = await auth.getUserByEmail(email);
    } catch (error) {
      if (error.code === "auth/user-not-found") {
        throw new HttpsError("not-found", "No Radar account uses that email.");
      }
      throw new HttpsError("internal", "Could not look up that account.");
    }
    if (!validUid(target.uid) || target.uid === uid) {
      throw new HttpsError("invalid-argument", "Choose your partner's email.");
    }
    const requester = await auth.getUser(uid);
    const requesterName = typeof requester.displayName === "string" &&
      requester.displayName.trim() ? requester.displayName.trim().slice(0, 80) :
      "Your partner";
    const batch = db.batch();
    batch.set(db.doc(`pairingApprovals/${uid}`), {
      ownerUid: uid,
      partnerUid: target.uid,
      updatedAt: new Date(now()),
    });
    batch.set(db.doc(`pairingRequests/${target.uid}/requesters/${uid}`), {
      requesterUid: uid,
      targetUid: target.uid,
      requesterName,
      createdAt: new Date(now()),
    });
    await batch.commit();
    return {requested: true, partnerName: target.displayName || "your partner"};
  }

  /**
   * Lists pending connection requests for the caller.
   * @param {object} request Authenticated callable request.
   * @return {Promise<object>} Pending requests.
   */
  async function getIncomingPairingRequests(request) {
    const uid = caller(request);
    const snapshot = await db.collection(
        `pairingRequests/${uid}/requesters`).limit(10).get();
    const requests = await Promise.all(snapshot.docs.map(async (doc) => {
      const data = doc.data();
      if (data.targetUid !== uid || data.requesterUid !== doc.id ||
          !validUid(doc.id)) return null;
      let user;
      try {
        user = await auth.getUser(doc.id);
      } catch (error) {
        return null;
      }
      return {
        requesterUid: doc.id,
        displayName: user.displayName || data.requesterName || "Your partner",
        email: user.email || "",
      };
    }));
    return {requests: requests.filter(Boolean)};
  }

  /**
   * Accepts or declines a pending connection request.
   * @param {object} request Authenticated callable request.
   * @return {Promise<object>} Response status.
   */
  async function respondToPairingRequest(request) {
    const uid = caller(request);
    const requesterUid = request.data?.requesterUid;
    const accept = request.data?.accept;
    if (!validUid(requesterUid) || requesterUid === uid ||
        typeof accept !== "boolean") {
      throw new HttpsError("invalid-argument", "Invalid connection response.");
    }
    const requestRef = db.doc(
        `pairingRequests/${uid}/requesters/${requesterUid}`);
    await db.runTransaction(async (tx) => {
      const pending = await tx.get(requestRef);
      if (!pending.exists || pending.data()?.targetUid !== uid ||
          pending.data()?.requesterUid !== requesterUid) {
        throw new HttpsError("not-found", "This request is no longer active.");
      }
      const requesterApprovalRef = db.doc(`pairingApprovals/${requesterUid}`);
      const requesterApproval = await tx.get(requesterApprovalRef);
      if (accept) {
        if (requesterApproval.data()?.partnerUid !== uid) {
          throw new HttpsError("failed-precondition",
              "The sender withdrew this request.");
        }
        tx.set(db.doc(`pairingApprovals/${uid}`), {
          ownerUid: uid,
          partnerUid: requesterUid,
          updatedAt: new Date(now()),
        });
      } else if (requesterApproval.data()?.partnerUid === uid) {
        tx.delete(requesterApprovalRef);
      }
      tx.delete(requestRef);
    });
    return {accepted: accept};
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
    requestPartnerByEmail,
    getIncomingPairingRequests,
    respondToPairingRequest,
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
