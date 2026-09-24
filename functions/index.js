const {onCall} = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const {createHandlers} = require("./handlers");

admin.initializeApp();
const handlers = createHandlers(
    admin.firestore(), admin.messaging(), admin.auth());
exports.requestLocation = onCall(handlers.requestLocation);
exports.sendTargetNotification = onCall(handlers.sendTargetNotification);
exports.getPairingStatus = onCall(handlers.getPairingStatus);
exports.requestPartnerByEmail = onCall(handlers.requestPartnerByEmail);
exports.getIncomingPairingRequests = onCall(
    handlers.getIncomingPairingRequests);
exports.respondToPairingRequest = onCall(handlers.respondToPairingRequest);
exports.postNote = onCall(handlers.postNote);
exports.editNote = onCall(handlers.editNote);
