Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
const {onCall} = require("firebase-functions/v2/https");
const admin = require("firebase-admin");
const {createHandlers} = require("./handlers");

admin.initializeApp();
const handlers = createHandlers(admin.firestore(), admin.messaging());
exports.requestLocation = onCall(handlers.requestLocation);
exports.sendTargetNotification = onCall(handlers.sendTargetNotification);
exports.getPairingStatus = onCall(handlers.getPairingStatus);
