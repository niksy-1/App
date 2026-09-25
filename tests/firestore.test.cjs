const {test, before, after, beforeEach} = require('node:test');
const {readFileSync} = require('node:fs');
const {initializeTestEnvironment, assertSucceeds, assertFails} = require('@firebase/rules-unit-testing');
const {doc, collection, collectionGroup, setDoc, updateDoc, deleteDoc, getDocFromServer,
  getDocs, query, orderBy, limit, serverTimestamp, Timestamp, deleteField,
  setLogLevel} = require('firebase/firestore');
setLogLevel('silent');
let env;
const db = (uid) => uid ? env.authenticatedContext(uid).firestore() : env.unauthenticatedContext().firestore();
const location = (uid) => ({ownerUid: uid, updatedAt: serverTimestamp(),
  latitude: 12.9, longitude: 77.6, accuracy: 10, timestamp: serverTimestamp(),
  batteryPercent: 70, isCharging: false});
const approval = (uid, partnerUid) => ({ownerUid: uid, partnerUid, updatedAt: serverTimestamp()});
const device = (uid) => ({ownerUid: uid, fcmToken: 'private-token', updatedAt: serverTimestamp()});
const note = (senderId = 'alice', targetId = 'bob') => ({senderId, targetId, text: 'Hello', timestamp: serverTimestamp()});
const notesPath = 'pairNotes/alice/partners/bob/entries';
const seedNote = async (path, data = note()) => env.withSecurityRulesDisabled(
    async (context) => setDoc(doc(context.firestore(), path), data));
async function pair() {
  await setDoc(doc(db('alice'), 'pairingApprovals/alice'), approval('alice', 'bob'));
  await setDoc(doc(db('bob'), 'pairingApprovals/bob'), approval('bob', 'alice'));
}
before(async () => {
  if (!process.env.FIRESTORE_EMULATOR_HOST) throw new Error('Run npm run test:rules; never use production.');
  env = await initializeTestEnvironment({projectId: 'demo-radar-security',
    firestore: {rules: readFileSync('firestore.rules', 'utf8')}});
});
beforeEach(async () => env.clearFirestore());
after(async () => env?.cleanup());

test('owner creates location, merges a note, refreshes location and deletes it', async () => {
  const ref = doc(db('alice'), 'locationsV2/alice');
  await assertSucceeds(setDoc(ref, location('alice')));
  await assertSucceeds(setDoc(ref, {note: 'Hi', updatedAt: serverTimestamp()}, {merge: true}));
  await assertSucceeds(updateDoc(ref, {latitude: -90, longitude: 180, batteryPercent: -1,
    timestamp: serverTimestamp(), updatedAt: serverTimestamp()}));
  await assertSucceeds(getDocFromServer(ref));
  await assertSucceeds(deleteDoc(ref));
});
test('note-only location may be created before the first location fix', async () => {
  const ref = doc(db('alice'), 'locationsV2/alice');
  await assertSucceeds(setDoc(ref, {ownerUid: 'alice', note: 'Hi', updatedAt: serverTimestamp()}));
  await assertSucceeds(setDoc(ref, location('alice'), {merge: true}));
});
test('owner can create and update a 500-character note', async () => {
  const ref = doc(db('alice'), 'locationsV2/alice');
  await assertSucceeds(setDoc(ref, {ownerUid: 'alice', note: 'a'.repeat(500),
    updatedAt: serverTimestamp()}));
  await assertSucceeds(updateDoc(ref, {note: 'b'.repeat(500),
    updatedAt: serverTimestamp()}));
});
test('only mutual approval permits partner location reads; revoke immediately denies reads', async () => {
  await setDoc(doc(db('bob'), 'locationsV2/bob'), location('bob'));
  await assertFails(getDocFromServer(doc(db('alice'), 'locationsV2/bob')));
  await setDoc(doc(db('alice'), 'pairingApprovals/alice'), approval('alice', 'bob'));
  await assertFails(getDocFromServer(doc(db('alice'), 'locationsV2/bob')));
  await setDoc(doc(db('bob'), 'pairingApprovals/bob'), approval('bob', 'alice'));
  await assertSucceeds(getDocFromServer(doc(db('alice'), 'locationsV2/bob')));
  await assertFails(getDocFromServer(doc(db('mallory'), 'locationsV2/bob')));
  await deleteDoc(doc(db('bob'), 'pairingApprovals/bob'));
  await assertFails(getDocFromServer(doc(db('alice'), 'locationsV2/bob')));
});
for (const actor of [null, 'mallory', 'bob']) {
  test(`${actor ?? 'unauthenticated'} cannot create, overwrite, update, or delete another user's location`, async () => {
    await pair();
    const ref = doc(db(actor), 'locationsV2/alice');
    await assertFails(setDoc(ref, location('alice')));
    await setDoc(doc(db('alice'), 'locationsV2/alice'), location('alice'));
    await assertFails(setDoc(ref, location('alice')));
    await assertFails(updateDoc(ref, {note: 'Injected', updatedAt: serverTimestamp()}));
    await assertFails(deleteDoc(ref));
  });
}
test('tokens and approval settings stay private even between partners; owners can rotate tokens', async () => {
  await pair();
  const ref = doc(db('alice'), 'deviceAccounts/alice');
  await assertSucceeds(setDoc(ref, device('alice')));
  await assertSucceeds(updateDoc(ref, {fcmToken: 'rotated', updatedAt: serverTimestamp()}));
  await assertSucceeds(getDocFromServer(ref));
  for (const actor of [null, 'bob', 'mallory']) {
    await assertFails(getDocFromServer(doc(db(actor), 'deviceAccounts/alice')));
    await assertFails(getDocFromServer(doc(db(actor), 'pairingApprovals/alice')));
    await assertFails(setDoc(doc(db(actor), 'pairingApprovals/alice'), approval('alice', 'mallory')));
    await assertFails(deleteDoc(doc(db(actor), 'pairingApprovals/alice')));
  }
});
test('no public lists, collection group queries or unknown/legacy paths', async () => {
  for (const uid of [null, 'alice']) {
    for (const path of ['locationsV2', 'deviceAccounts', 'pairingApprovals', 'locations', 'notes', 'notificationLimits']) {
      await assertFails(getDocs(query(collection(db(uid), path), limit(100))));
    }
    for (const path of ['locations/legacyToken', 'notes/legacyNote', 'users/alice',
      'notificationLimits/alice', 'locationsV2/alice/private/secret', 'pairNotes/alice']) {
      await assertFails(getDocFromServer(doc(db(uid), path)));
      await assertFails(setDoc(doc(db(uid), path), {ownerUid: 'alice', isAdmin: true}));
    }
    await assertFails(getDocFromServer(doc(db(uid), 'pairingRequests/alice/requesters/bob')));
    await assertFails(setDoc(doc(db(uid), 'pairingRequests/alice/requesters/bob'), {
      requesterUid: 'bob', targetUid: 'alice', requesterName: 'Bob',
      createdAt: serverTimestamp(),
    }));
    await assertFails(getDocs(query(collectionGroup(db(uid), 'entries'), limit(100))));
  }
});
const invalidLocation = {
  'ownership hijacking': {ownerUid: 'bob'},
  'privilege escalation': {isAdmin: true},
  'arbitrary schema': {extraData: 'x'},
  'oversize note': {note: 'x'.repeat(501)},
  '1MB update bypass': {note: 'x'.repeat(1000000)},
  'note type juggling': {note: 4},
  'latitude out of range': {latitude: 91},
  'longitude out of range': {longitude: -181},
  'negative accuracy': {accuracy: -1},
  'huge accuracy': {accuracy: 100001},
  'NaN latitude': {latitude: NaN},
  'infinite longitude': {longitude: Infinity},
  'fractional battery': {batteryPercent: 1.5},
  'battery overflow': {batteryPercent: 101},
  'negative battery': {batteryPercent: -2},
  'boolean type juggling': {isCharging: 'true'},
  'timestamp type juggling': {timestamp: 123},
  'backdated timestamp': {timestamp: Timestamp.fromMillis(0)},
  'future timestamp': {timestamp: Timestamp.fromMillis(4102444800000)},
  'forged update time': {updatedAt: Timestamp.fromMillis(0)},
};
for (const [name, patch] of Object.entries(invalidLocation)) {
  test(`location rejects ${name} on create AND update`, async () => {
    const ref = doc(db('alice'), 'locationsV2/alice');
    await assertFails(setDoc(ref, {...location('alice'), ...patch}));
    await setDoc(ref, location('alice'));
    await assertFails(updateDoc(ref, {...patch, ...(patch.updatedAt ? {} : {updatedAt: serverTimestamp()})}));
  });
}
test('required fields and location group cannot be omitted or removed', async () => {
  for (const field of Object.keys(location('alice'))) {
    const ref = doc(db('alice'), 'locationsV2/alice');
    const data = location('alice'); delete data[field];
    await assertFails(setDoc(ref, data));
    await setDoc(ref, location('alice'));
    await assertFails(updateDoc(ref, {[field]: deleteField(), ...(field === 'updatedAt' ? {} : {updatedAt: serverTimestamp()})}));
    await deleteDoc(ref);
  }
});
test('device and approval validators run on both create and update', async () => {
  for (const [path, valid, patches] of [
    ['deviceAccounts/alice', device('alice'), [{fcmToken: ''}, {fcmToken: 'x'.repeat(4097)},
      {fcmToken: []}, {ownerUid: 'bob'}, {role: 'admin'}, {updatedAt: 1}]],
    ['pairingApprovals/alice', approval('alice', 'bob'), [{partnerUid: 'alice'},
      {partnerUid: ''}, {partnerUid: 'x'.repeat(129)}, {partnerUid: 'a/b'},
      {partnerUid: ['bob']}, {ownerUid: 'bob'}, {isAdmin: true}, {updatedAt: 1}]],
  ]) {
    const ref = doc(db('alice'), path);
    for (const patch of patches) {
      await assertFails(setDoc(ref, {...valid, ...patch}));
      await setDoc(ref, valid);
      await assertFails(updateDoc(ref, {...patch, ...(patch.updatedAt ? {} : {updatedAt: serverTimestamp()})}));
      await deleteDoc(ref);
    }
    for (const key of Object.keys(valid)) {
      const data = {...valid}; delete data[key];
      await assertFails(setDoc(ref, data));
      await setDoc(ref, valid);
      await assertFails(updateDoc(ref, {[key]: deleteField()}));
      await deleteDoc(ref);
    }
  }
});
test('backend-created notes remain readable with the pair-scoped history query', async () => {
  await pair();
  await seedNote(`${notesPath}/one`);
  await seedNote(`${notesPath}/two`, note('bob', 'alice'));
  for (const uid of ['alice', 'bob']) {
    await assertSucceeds(getDocs(query(collection(db(uid), notesPath), orderBy('timestamp', 'desc'), limit(100))));
    await assertSucceeds(getDocFromServer(doc(db(uid), `${notesPath}/one`)));
  }
});
test('note reads and writes fail without mutual approval, for outsiders and after revocation', async () => {
  await assertFails(setDoc(doc(db('alice'), `${notesPath}/one`), note()));
  await pair();
  await seedNote(`${notesPath}/one`);
  for (const uid of [null, 'mallory']) {
    await assertFails(getDocFromServer(doc(db(uid), `${notesPath}/one`)));
    await assertFails(getDocs(query(collection(db(uid), notesPath), limit(100))));
    await assertFails(setDoc(doc(db(uid), `${notesPath}/two`), note()));
  }
  await deleteDoc(doc(db('bob'), 'pairingApprovals/bob'));
  await assertFails(getDocFromServer(doc(db('alice'), `${notesPath}/one`)));
  await assertFails(getDocs(query(collection(db('alice'), notesPath), limit(100))));
  await assertFails(setDoc(doc(db('alice'), `${notesPath}/two`), note()));
});
test('clients cannot bypass duplicate and edit windows with direct note writes', async () => {
  await pair();
  const ref = doc(db('alice'), `${notesPath}/one`);
  for (const patch of [{senderId: 'bob'}, {targetId: 'mallory'}, {text: ''}, {text: 'x'.repeat(101)},
    {text: []}, {isAdmin: true}, {timestamp: 123}, {timestamp: Timestamp.fromMillis(0)}]) {
    await assertFails(setDoc(ref, {...note(), ...patch}));
  }
  for (const key of Object.keys(note())) {
    const data = note(); delete data[key]; await assertFails(setDoc(ref, data));
  }
  await assertFails(setDoc(ref, note()));
  await seedNote(`${notesPath}/one`);
  await assertFails(updateDoc(ref, {text: 'Edited'}));
  await assertFails(deleteDoc(ref));
  await assertFails(setDoc(doc(db('alice'),
      'pairNotes/alice/partners/bob/noteGuards/alice_hash'),
  {senderId: 'alice', noteId: 'one', lastPostedAt: serverTimestamp()}));
  await assertFails(setDoc(doc(db('alice'), 'pairNotes/bob/partners/alice/entries/one'), note()));
  await assertFails(getDocs(query(collection(db('alice'), notesPath), limit(101))));
  await assertFails(getDocs(collection(db('alice'), notesPath)));
});
test('switching partners does not retain access to the previous partner', async () => {
  await pair();
  await setDoc(doc(db('bob'), 'locationsV2/bob'), location('bob'));
  await setDoc(doc(db('alice'), 'pairingApprovals/alice'), approval('alice', 'carol'));
  await assertFails(getDocFromServer(doc(db('alice'), 'locationsV2/bob')));
  await assertFails(setDoc(doc(db('alice'), `${notesPath}/one`), note()));
});
