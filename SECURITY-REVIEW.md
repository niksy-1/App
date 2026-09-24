# Firestore security review

Project: `widget-33ff3`. Scope: the new rules, three callable handlers, and matching
Android identity, pairing, query, and notification changes. See SECURITY.md for
data models, rollout requirements, and limitations.

## Validation

- 34 Firestore emulator tests passed, including both valid client access and denials.
- 6 callable-handler tests passed against the emulator with mocked FCM delivery.
- Functions ESLint passed.
- Firestore production compiler/dry-run passed.
- Functions and Authentication deployment dry-run passed.
- Android compilation and deployment verification are recorded separately below
  when completed; emulator tests do not establish on-device behavior.

## Adversarial checks

| Attack | Observed outcome |
| --- | --- |
| Public collection listing | Denied for anonymous unauthenticated clients and signed-in users |
| Unauthorized get/create/update/delete | Denied for outsiders and for partners attempting owner-only writes |
| Update bypass / oversized payload | Invalid creates and updates denied, including a 1,000,000-character note |
| Ownership hijack on create | Path ownership and ownerUid must match auth UID |
| Ownership hijack on update | Owner UID changes denied |
| Immutable field modification | Notes cannot be updated or deleted; owner UID immutable |
| Type juggling | Invalid scalar, list, boolean and timestamp types denied |
| Create/update validation mismatch | Both routes exercise the same domain validators |
| Resource exhaustion | Text/token/UID bounds and query limits enforced; no arbitrary lists/maps allowed |
| Required field omission | Omission at create and deletion at update denied |
| Privilege escalation | No client-admin roles; injected isAdmin/role fields denied |
| Schema pollution | Extra document fields denied |
| Invalid state transition | One-sided approval gives no access; revoke and switch-partner flows tested |
| Path scoping | Reversed pair path, unknown nested path and collection-group access denied |
| Timestamp manipulation | Non-server create/update times rejected; location refresh must use server time |
| Numeric overflow/negative values | Invalid coordinates, accuracy, battery, NaN and infinity denied |
| Mixed-content leak | FCM tokens and approval settings remain owner-only, including between partners |
| Replay/counters | No client counters; callable rate-limit replay and simultaneous calls tested |
| Orphaned subcollections | Pair parents intentionally namespaces; entries still require both live approvals |
| Query mismatch | The app's pair-scoped timestamp-descending limit-100 history query succeeds |
| Validator pattern | Every allowed update calls its domain validator and owner/identity checks |

## Limits

These checks did not find an authorization or schema bypass in the tested rules.
They are not a guarantee against all vulnerabilities. Trusted Admin SDK callers
and IAM permissions bypass rules. Anonymous account abuse, broad distribution,
on-device background delivery and recovery after reinstall require further
operational review. Revocation prevents future access, not retention of data
already downloaded. A notification already accepted can be in flight during
revocation; the recipient checks its current pairing before acting.

The original rules were public until September 26, 2026. The private local backup
is `.security-local/original-firestore.rules`. Legacy records are retained but
not assigned to new identities without proof of ownership.
