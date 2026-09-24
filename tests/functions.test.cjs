const {test, before, after, beforeEach} = require('node:test');
const assert = require('node:assert/strict');
const admin = require('../functions/node_modules/firebase-admin');
const {createHandlers} = require('../functions/handlers');
let app, db, handlers, sent, clock;
const request = (uid = 'alice', data = {targetUid: 'bob'}) => ({auth: {uid}, data});
const code = (expected) => (error) => error.code === expected;
const users = new Map([
  ['alice', {uid: 'alice', email: 'alice@example.com', displayName: 'Alice'}],
  ['bob', {uid: 'bob', email: 'bob@example.com', displayName: 'Bob'}],
  ['carol', {uid: 'carol', email: 'carol@example.com', displayName: 'Carol'}],
]);
const auth = {
  getUser: async (uid) => {
    const user = users.get(uid);
    if (!user) throw Object.assign(new Error('Missing user'), {code: 'auth/user-not-found'});
    return user;
  },
  getUserByEmail: async (email) => {
    const user = [...users.values()].find((entry) => entry.email === email);
    if (!user) throw Object.assign(new Error('Missing user'), {code: 'auth/user-not-found'});
    return user;
  },
};
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
  handlers = createHandlers(db,
      {send: async (message) => {sent.push(message); return 'message-id';}},
      auth, () => clock);
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
test('email request can be reviewed and accepted without exposing a token', async () => {
  assert.deepEqual(await handlers.requestPartnerByEmail(
      request('alice', {email: ' BOB@EXAMPLE.COM '})),
  {requested: true, partnerName: 'Bob'});
  const approval = (await db.doc('pairingApprovals/alice').get()).data();
  assert.equal(approval.partnerUid, 'bob');
  const incoming = await handlers.getIncomingPairingRequests(request('bob', {}));
  assert.deepEqual(incoming, {requests: [{requesterUid: 'alice',
    displayName: 'Alice', email: 'alice@example.com'}]});
  await handlers.respondToPairingRequest(
      request('bob', {requesterUid: 'alice', accept: true}));
  assert.deepEqual(await handlers.getPairingStatus(request()),
      {approved: true, partnerUid: 'bob'});
  assert.equal((await db.doc('pairingRequests/bob/requesters/alice').get()).exists, false);
});
test('declining a request removes its pending approval', async () => {
  await handlers.requestPartnerByEmail(request('alice', {email: 'bob@example.com'}));
  await handlers.respondToPairingRequest(
      request('bob', {requesterUid: 'alice', accept: false}));
  assert.equal((await db.doc('pairingApprovals/alice').get()).exists, false);
  assert.deepEqual(await handlers.getPairingStatus(request()), {approved: false});
});
test('email pairing rejects invalid, unknown, self, forged, and stale requests', async () => {
  for (const email of ['', 'bad', 'x'.repeat(250) + '@x.com']) {
    await assert.rejects(
        handlers.requestPartnerByEmail(request('alice', {email})),
        code('invalid-argument'));
  }
  await assert.rejects(
      handlers.requestPartnerByEmail(request('alice', {email: 'missing@example.com'})),
      code('not-found'));
  await assert.rejects(
      handlers.requestPartnerByEmail(request('alice', {email: 'alice@example.com'})),
      code('invalid-argument'));
  await assert.rejects(
      handlers.respondToPairingRequest(
          request('bob', {requesterUid: '../alice', accept: true})),
      code('invalid-argument'));
  await assert.rejects(
      handlers.respondToPairingRequest(
          request('bob', {requesterUid: 'alice', accept: true})),
      code('not-found'));
});
test('posting a note updates location and blocks repeated text for 10 minutes', async () => {
  await pair();
  const first = await handlers.postNote(request('alice',
      {partnerUid: 'bob', text: '  Hello  '}));
  const entry = (await db.doc(`pairNotes/alice/partners/bob/entries/${first.noteId}`).get()).data();
  assert.equal(entry.text, 'Hello');
  assert.equal(entry.senderId, 'alice');
  assert.equal((await db.doc('locationsV2/alice').get()).data().note, 'Hello');
  await assert.rejects(handlers.postNote(request('alice',
      {partnerUid: 'bob', text: 'hello'})), code('already-exists'));
  clock += 1000;
  await handlers.postNote(request('alice', {partnerUid: 'bob', text: 'Different'}));
  await assert.rejects(handlers.postNote(request('alice',
      {partnerUid: 'bob', text: 'hello'})), code('already-exists'));
  await handlers.postNote(request('bob', {partnerUid: 'alice', text: 'Hello'}));
  clock += 10 * 60 * 1000;
  await handlers.postNote(request('alice', {partnerUid: 'bob', text: 'Hello'}));
});
test('concurrent duplicate posts create only one note', async () => {
  await pair();
  const data = {partnerUid: 'bob', text: 'Same text'};
  const results = await Promise.allSettled([
    handlers.postNote(request('alice', data)),
    handlers.postNote(request('alice', data)),
  ]);
  assert.equal(results.filter((result) => result.status === 'fulfilled').length, 1);
  const entries = await db.collection('pairNotes/alice/partners/bob/entries').get();
  assert.equal(entries.size, 1);
});
test('sender can edit during first 3 minutes and displayed note follows', async () => {
  await pair();
  const {noteId} = await handlers.postNote(request('alice',
      {partnerUid: 'bob', text: 'Original'}));
  clock += 2 * 60 * 1000;
  assert.deepEqual(await handlers.editNote(request('alice',
      {partnerUid: 'bob', noteId, text: 'Fixed'})), {edited: true});
  const entry = (await db.doc(`pairNotes/alice/partners/bob/entries/${noteId}`).get()).data();
  assert.equal(entry.text, 'Fixed');
  assert.equal(entry.timestamp.toMillis(), 100000);
  assert.equal((await db.doc('locationsV2/alice').get()).data().note, 'Fixed');
  await assert.rejects(handlers.editNote(request('bob',
      {partnerUid: 'alice', noteId, text: 'Hijacked'})), code('permission-denied'));
  clock += 60 * 1000;
  await assert.rejects(handlers.editNote(request('alice',
      {partnerUid: 'bob', noteId, text: 'Too late'})), code('failed-precondition'));
});
test('editing cannot duplicate another note or bypass pair approval', async () => {
  await pair();
  await handlers.postNote(request('alice', {partnerUid: 'bob', text: 'First'}));
  clock += 1000;
  const {noteId} = await handlers.postNote(request('alice',
      {partnerUid: 'bob', text: 'Second'}));
  await assert.rejects(handlers.editNote(request('alice',
      {partnerUid: 'bob', noteId, text: 'first'})), code('already-exists'));
  await db.doc('pairingApprovals/bob').delete();
  await assert.rejects(handlers.editNote(request('alice',
      {partnerUid: 'bob', noteId, text: 'Revoke bypass'})), code('permission-denied'));
  await assert.rejects(handlers.postNote(request('alice',
      {partnerUid: 'bob', text: 'New'})), code('permission-denied'));
});
test('note callables reject malformed and oversized text', async () => {
  await pair();
  for (const text of ['', ' ', 'x'.repeat(101), 42]) {
    await assert.rejects(handlers.postNote(request('alice',
        {partnerUid: 'bob', text})), code('invalid-argument'));
  }
  await assert.rejects(handlers.postNote(request('alice',
      {partnerUid: '../bob', text: 'Hello'})), code('invalid-argument'));
  await assert.rejects(handlers.editNote(request('alice',
      {partnerUid: 'bob', noteId: '../other', text: 'Hello'})),
  code('invalid-argument'));
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
