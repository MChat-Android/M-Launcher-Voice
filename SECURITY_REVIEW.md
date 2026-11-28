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
- Admin and CI test-tool dependency trees use patched transitive overrides. CI regenerates the admin lockfile before installing and fails on moderate-or-higher audit findings for both dependency sets.
- CI checks for accidentally tracked service-account keys, keystores, and environment secret files.
- GitHub Actions are pinned to full commit SHAs rather than floating major-version tags.
- Android backup is disabled, cleartext traffic is explicitly disabled, and the boot receiver is not exported.
- Offline VIP expiry for time-based packages uses monotonic elapsed time, preventing a simple device-clock rollback from extending the cached entitlement.
- Cached admin claims no longer grant admin-only privileges offline.
- Monthly renewals extend from the existing future expiry; a monthly purchase cannot overwrite a permanent membership.
- Active VIP records are checked for consistent plan/expiry data; a permanent plan must have no expiry timestamp.
- Firebase Android dependencies use BoM 34.19.0 and Google Services plugin 4.6.0. BoM 35.2.0 was tested but requires Firebase Auth minSdk 24, so the app stays on the newest compatible line without dropping Android API 23.

## Security limitations that remain

1. **Firebase App Check is not integrated/enforced yet.** The built-in Play Integrity provider supports apps distributed through Google Play and outside Google Play. For a Netlify-only APK, Firebase documents an off-Play configuration: do not require `PLAY_RECOGNIZED` or `LICENSED`, but require device integrity. This still requires linking the Firebase Cloud project through the app entry in Play Console, registering the APK signing certificate SHA-256 in Firebase App Check, adding the Android provider SDK, and testing real installs before enabling Firestore enforcement. Do not enforce until both legitimate off-Play installs and tampered clients have been tested.
2. **Trial device binding is not hardware-proof.** Android ID can change after a factory reset and can be spoofed on rooted or modified devices. Firestore prevents ordinary account/device reuse, but a determined modified client may bypass local checks without App Check and server-side abuse controls.
3. **Offline revocation has a grace window.** A paid VIP cached while online can remain available offline for up to 30 days, unless its own paid expiry arrives sooner. An administrator's early revocation cannot reach a device that stays offline.
4. **Firestore rules deployment is separate.** Passing emulator tests does not publish rules to Firebase. The production Firebase project must be checked to ensure the deployed rules exactly match this repository.
5. **Payment confirmation remains manual.** The admin must match each request against the actual SeaBank transaction before verification. A screenshot or transfer reference alone is not proof of payment.
6. **Privileged service-account protection is operational.** Keep the JSON key outside the repository and APK, restrict its file permissions, and rotate it immediately if it may have been exposed.
7. **Repository controls still need owner review.** Enable branch protection and require the security/build checks on the release branch. Keep GitHub Actions secrets limited to the repository and rotate signing credentials if exposed.

## Required checks before public release

- [ ] Latest `security/hardening` commit has a green security job and signed APK build.
- [ ] Admin and CI test-tool dependency audits report zero known vulnerabilities at the moderate-or-higher threshold.
- [ ] Recheck Firebase Android SDK versions for security updates while preserving minSdk 23, or explicitly approve raising minSdk to 24 after reviewing device compatibility.
- [ ] All Firestore emulator tests pass.
- [ ] Run `node vip-admin.js audit-admins` from the trusted admin environment and confirm only the approved primary UID has `admin: true`.
- [ ] Publish and independently review the Firestore rules in Firebase Console.
- [ ] Link the Firebase project to Play Integrity in Play Console, register the signing SHA-256, integrate App Check Play Integrity, and test the Netlify/off-Play settings before enforcing Firestore App Check.
- [ ] Test normal, expired, renewed, permanent, revoked, and offline VIP states on real devices.
- [ ] Test Trial reuse after reinstall, account change, and device reset; document the expected limits.
- [ ] Verify no service-account JSON, signing key, or private key is present in Git history or release artifacts.
- [ ] Do not publish the APK until the owner explicitly approves release.

## Important

A green build proves that the automated checks passed; it does not prove that every possible attack is prevented. Security must be maintained through updates, monitoring, credential rotation, and real-device testing.
