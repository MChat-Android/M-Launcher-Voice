# M Asisten — Security Review and Release Gates

This document tracks the security work for the Android app and its Firebase/admin tooling. It is not a guarantee that the app is impossible to compromise.

## Current hardening branch

- Branch: `security/hardening`
- Base release candidate: `admob/restore`
- Public release: **not approved**
- The security branch must pass its own Android build and all security checks before any merge.

## Implemented in this branch

- Firestore rules restrict VIP entitlement writes to trusted admin custom claims and restrict Trial creation to server timestamps plus an atomic account/device claim.
- Firestore emulator tests cover Trial, VIP membership, payment requests, admin operations, and backup ownership.
- Admin CLI is bound to the one UID configured by `M_ASISTEN_ADMIN_UID`; `audit-admins` lists all custom-claim admins and `revoke-unexpected-admin` can remove an unexpected claim.
- Admin dependency audit uses a patched `uuid@11.1.1` override. CI regenerates the admin lockfile before installing and fails on moderate-or-higher audit findings.
- CI checks for accidentally tracked service-account keys, keystores, and environment secret files.
- GitHub Actions are pinned to full commit SHAs rather than floating major-version tags.
- Android backup is disabled, cleartext traffic is explicitly disabled, and the boot receiver is not exported.
- Offline VIP expiry for time-based packages uses monotonic elapsed time, preventing a simple device-clock rollback from extending the cached entitlement.
- Cached admin claims no longer grant admin-only privileges offline.
- Monthly renewals extend from the existing future expiry; a monthly purchase cannot overwrite a permanent membership.

## Security limitations that remain

1. **Firebase App Check is not integrated/enforced yet.** Before public release, choose and test an App Check provider compatible with the actual distribution channel. Play Integrity enforcement may behave differently for APKs distributed directly through Netlify than for Play Store installs; do not enable enforcement until both legitimate installs and blocked tampered clients have been tested.
2. **Trial device binding is not hardware-proof.** Android ID can change after a factory reset and can be spoofed on rooted or modified devices. Firestore prevents ordinary account/device reuse, but a determined modified client may bypass local checks without App Check and server-side abuse controls.
3. **Offline revocation has a grace window.** A paid VIP cached while online can remain available offline for up to 30 days, unless its own paid expiry arrives sooner. An administrator's early revocation cannot reach a device that stays offline.
4. **Firestore rules deployment is separate.** Passing emulator tests does not publish rules to Firebase. The production Firebase project must be checked to ensure the deployed rules exactly match this repository.
5. **Payment confirmation remains manual.** The admin must match each request against the actual SeaBank transaction before verification. A screenshot or transfer reference alone is not proof of payment.
6. **Privileged service-account protection is operational.** Keep the JSON key outside the repository and APK, restrict its file permissions, and rotate it immediately if it may have been exposed.
7. **Repository controls still need owner review.** Enable branch protection and require the security/build checks on the release branch. Keep GitHub Actions secrets limited to the repository and rotate signing credentials if exposed.

## Required checks before public release

- [ ] Latest `security/hardening` commit has a green security job and signed APK build.
- [ ] Admin and CI dependency audits report zero known vulnerabilities at the selected threshold.
- [ ] All Firestore emulator tests pass.
- [ ] Run `node vip-admin.js audit-admins` from the trusted admin environment and confirm only the approved primary UID has `admin: true`.
- [ ] Publish and independently review the Firestore rules in Firebase Console.
- [ ] Configure and test Firebase App Check for the chosen distribution channel.
- [ ] Test normal, expired, renewed, permanent, revoked, and offline VIP states on real devices.
- [ ] Test Trial reuse after reinstall, account change, and device reset; document the expected limits.
- [ ] Verify no service-account JSON, signing key, or private key is present in Git history or release artifacts.
- [ ] Do not publish the APK until the owner explicitly approves release.

## Important

A green build proves that the automated checks passed; it does not prove that every possible attack is prevented. Security must be maintained through updates, monitoring, credential rotation, and real-device testing.
