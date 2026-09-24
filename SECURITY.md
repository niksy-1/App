# Radar security and rollout

Firebase project: `widget-33ff3`. These are prototype Security Rules validated
against the checked-in emulator tests; review them before broadly sharing the app.

## Using the updated app

1. Install the updated Android app on both phones. Opening it creates or restores
   an anonymous Firebase account and registers its private notification token.
2. Open the menu and exchange **Your Beacon ID** values. These are authenticated
   account IDs, not the old FCM tokens.
3. Each person enters the other's ID and taps **Approve Partner**. Sharing becomes
   active only when both approvals match. The foreground app checks this status
   every ten seconds.
4. **Stop Sharing** removes your approval. The backend immediately denies future
   partner location/history reads and notifications. Previously downloaded data,
   screenshots, and notifications already in flight cannot be recalled.

An anonymous identity belongs to this app installation. Clearing app data or
reinstalling can lose it; exchange new IDs and approve again. Backup/device
transfer of the app's private state is disabled. There is no account-recovery
flow in this version.

## Access model

| Path | Read | Write |
| --- | --- | --- |
| `deviceAccounts/{uid}` | Owner only; backend resolves token | Owner only, validated token and server timestamp |
| `pairingApprovals/{uid}` | Owner only | Owner approves one other UID, or deletes approval |
| `locationsV2/{uid}` | Owner or mutually approved partner; single-document reads | Owner only, bounded fields and server timestamps |
| `pairNotes/{a}/partners/{b}/entries/{id}` | Both approved partners; history queries limited to 100 | Authenticated sender creates immutable notes for the pair |
| `notificationLimits/{uid}` | Backend only | Backend transaction only |
| Legacy `locations`, `notes`, and every other path | Denied | Denied |

Pair IDs are sorted lexicographically by the app. The pair parent paths are
namespaces, not documents; independent approval documents provide authorization.
Switching or removing either approval ends access to the previous pair's notes.
Reapproving the same pair restores access to that pair's previous notes.

The app queries notes within one pair's path using timestamp descending and a
limit of 100; it no longer downloads other users' notes for local filtering.
Reads ignore cache-only snapshots. Location notes may be written before a GPS
fix exists. Once any GPS field is present, the entire location field group is
required and validated on every write.

The callable functions authenticate callers, check both approvals, resolve the
recipient's private FCM token on the server, and enforce a per-sender ten-second
notification interval in a transaction. The receiving app checks the intended
UID and its current approved partner before acting on the message. Firestore
rules alone cannot protect Admin SDK operations, so these checks are explicit.

## Migration

The original live rules allowed all reads and writes until September 26, 2026.
Their local backup is `.security-local/original-firestore.rules` (git-ignored).
Do not restore those public rules as a routine rollback.

Old app builds do not authenticate and are incompatible with the new rules and
callable API. Both devices must upgrade and pair again. Legacy data is preserved
on the server but denied to clients. It is not automatically assigned to a new
account: possession of an old device token is not proof of ownership. Any future
history migration must establish ownership through a trusted process.

## Reproduce validation

Use Node.js 24 and Java 21 or newer:

```sh
npm ci
npm --prefix functions ci
npm --prefix functions run lint
npm run test:rules
npx -y firebase-tools@latest deploy --only firestore:rules --dry-run --project widget-33ff3
bash gradlew :app:compileDebugKotlin --no-daemon
```

The tests use `demo-radar-security`, require the local emulator, and do not send
real FCM notifications. They cover legitimate owner/pair access, outsiders,
one-sided approval, revocation, forged ownership, strict schemas, missing fields,
type confusion, large strings, range violations, timestamp manipulation,
private token isolation, collection queries, legacy paths, and callable abuse.

For a coordinated backend rollout after validation:

```sh
npx -y firebase-tools@latest deploy --only auth,firestore:rules,functions --project widget-33ff3
```

Before distributing widely, verify the two-phone flow: register, pair on both
phones, ping, post/read notes, revoke on either phone, and confirm that the other
phone can no longer fetch shared data or send requests. Background execution,
notification permissions, and token refresh also need device testing.

References: [anonymous Android authentication](https://firebase.google.com/docs/auth/android/anonymous-auth),
[rules emulator testing](https://firebase.google.com/docs/firestore/security/test-rules-emulator),
[query authorization](https://firebase.google.com/docs/firestore/security/rules-query).
