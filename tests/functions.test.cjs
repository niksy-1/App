Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
Failed to create stream fd: Operation not permitted
const {test, before, after, beforeEach} = require('node:test');
const assert = require('node:assert/strict');
const admin = require('../functions/node_modules/firebase-admin');
const {createHandlers} = require('../functions/handlers');
let app, db, handlers, sent, clock;
const request = (uid = 'alice', data = {targetUid: 'bob'}) => ({auth: {uid}, data});
const code = (expected) => (error) => error.code === expected;
before(() => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Emulator required.');
  app = admin.initializeApp({projectId: 'demo-radar-security'}, 'security-tests');
  db = app.firestore();
});
beforeEach(async () => {
  const response = await fetch(`http://${process.env.FIRESTORE_EMULATOR_HOST}/emulator/v1/projects/demo-radar-security/databases/(default)/documents`, {method: 'DELETE'});
  assert.equal(response.ok, true);
  sent = [];
  clock = 100000;
  handlers = createHandlers(db, {send: async (message) => {sent.push(message); return 'message-id';}}, () => clock);
});
after(async () => app?.delete());
async function pair() {
  await db.doc('pairingApprovals/alice').set({partnerUid: 'bob'});
  await db.doc('pairingApprovals/bob').set({partnerUid: 'alice'});
  await db.doc('deviceAccounts/bob').set({fcmToken: 'private-bob-token'});
}
test('all callables require authentication', async () => {
  for (const handler of Object.values(handlers)) {
    await assert.rejects(async () => handler({data: {targetUid: 'bob', action: 'REMIND_CHARGE'}}), code('unauthenticated'));
  }
  assert.equal(sent.length, 0);
});
test('callables reject raw token targets, self-targets, invalid IDs, and one-sided approval', async () => {
  for (const data of [{targetToken: 'stolen-token'}, {targetUid: 'alice'}, {targetUid: '../bob'}, {targetUid: 4}]) {
    await assert.rejects(handlers.requestLocation(request('alice', data)), code('invalid-argument'));
  }
  await db.doc('pairingApprovals/alice').set({partnerUid: 'bob'});
  await assert.rejects(handlers.requestLocation(request()), code('permission-denied'));
  assert.deepEqual(await handlers.getPairingStatus(request()), {approved: false});
  assert.equal(sent.length, 0);
});
test('approved sender uses server-side token and recipient identity, then rate limit applies', async () => {
  await pair();
  assert.deepEqual(await handlers.getPairingStatus(request()), {approved: true, partnerUid: 'bob'});
  await handlers.requestLocation(request('alice', {targetUid: 'bob', targetToken: 'attacker-token'}));
  assert.equal(sent[0].token, 'private-bob-token');
  assert.deepEqual(sent[0].data, {action: 'SEND_LOCATION', senderUid: 'alice', targetUid: 'bob'});
  await assert.rejects(handlers.requestLocation(request()), code('resource-exhausted'));
  clock += 10000;
  await handlers.sendTargetNotification(request('alice', {targetUid: 'bob', action: 'REMIND_CHARGE', message: 'Charge soon'}));
  assert.equal(sent[1].data.message, 'Charge soon');
});
test('concurrent requests cannot bypass transaction rate limit', async () => {
  await pair();
  const results = await Promise.allSettled([handlers.requestLocation(request()), handlers.requestLocation(request())]);
  assert.equal(results.filter((result) => result.status === 'fulfilled').length, 1);
  assert.equal(sent.length, 1);
});
test('revocation blocks both callables and clears reported pairing status', async () => {
  await pair();
  await db.doc('pairingApprovals/bob').delete();
  await assert.rejects(handlers.requestLocation(request()), code('permission-denied'));
  await assert.rejects(async () => handlers.sendTargetNotification(request('alice', {targetUid: 'bob', action: 'REMIND_CHARGE'})), code('permission-denied'));
  assert.deepEqual(await handlers.getPairingStatus(request()), {approved: false});
  assert.equal(sent.length, 0);
});
test('notification input validation and missing device do not send messages', async () => {
  await pair();
  for (const data of [{action: 'OTHER'}, {action: 'REMIND_CHARGE', message: 'x'.repeat(201)},
    {action: 'REMIND_CHARGE', message: []}]) {
    await assert.rejects(async () => handlers.sendTargetNotification(request('alice', {targetUid: 'bob', ...data})), code('invalid-argument'));
  }
  await db.doc('deviceAccounts/bob').delete();
  await assert.rejects(handlers.requestLocation(request()), code('failed-precondition'));
  assert.equal(sent.length, 0);
});
