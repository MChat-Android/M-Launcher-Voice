#!/usr/bin/env node
"use strict";

// Trusted operator tool. Run only on a private admin computer with a Firebase
// service-account credential. Never ship this file's credentials in the APK.
const admin = require("firebase-admin");

if (!process.env.GOOGLE_APPLICATION_CREDENTIALS) {
  console.error("Set GOOGLE_APPLICATION_CREDENTIALS to a private service-account JSON file.");
  process.exit(1);
}
admin.initializeApp({ credential: admin.credential.applicationDefault() });
const db = admin.firestore();
const auth = admin.auth();

const PLANS = {
  "VIP Bulanan (30 hari)": { amount: 29000, days: 30 },
  "VIP Permanen": { amount: 499000, days: null }
};

async function configuredAdminUid() {
  const uid = process.env.M_ASISTEN_ADMIN_UID;
  if (!uid || !/^[A-Za-z0-9:_-]{10,128}$/.test(uid)) {
    throw new Error("Set M_ASISTEN_ADMIN_UID to the one approved Firebase admin UID.");
  }
  return uid;
}

async function listAdminUids() {
  const found = [];
  let pageToken;
  do {
    const page = await auth.listUsers(1000, pageToken);
    for (const user of page.users) {
      if (user.customClaims?.admin === true) found.push(user.uid);
    }
    pageToken = page.pageToken;
  } while (pageToken);
  return found;
}

async function main() {
  const [command, value, ...rest] = process.argv.slice(2);
  if (command === "audit-admins") {
    const approvedUid = await configuredAdminUid();
    const admins = await listAdminUids();
    console.log("Approved admin UID:", approvedUid);
    console.log("Admin claim UIDs:", admins.length ? admins.join(", ") : "(none)");
    const unexpected = admins.filter(uid => uid !== approvedUid);
    if (unexpected.length) {
      console.error("Unexpected admin claims detected. Revoke these UIDs manually after confirming ownership:", unexpected.join(", "));
      process.exitCode = 1;
    } else {
      console.log("No unexpected admin accounts found.");
    }
    return;
  }
  if (command === "set-admin") {
    const approvedUid = await configuredAdminUid();
    if (!value || value !== approvedUid) throw new Error("Only the UID configured in M_ASISTEN_ADMIN_UID can receive the admin claim.");
    const admins = await listAdminUids();
    const unexpected = admins.filter(uid => uid !== approvedUid);
    if (unexpected.length) throw new Error("Unexpected admin claims exist. Run audit-admins and resolve them before granting admin.");
    const user = await auth.getUser(approvedUid);
    await auth.setCustomUserClaims(approvedUid, { ...(user.customClaims || {}), admin: true });
    console.log("Admin claim enabled for the configured primary UID.");
    console.log("The admin must sign out/in or refresh the ID token before admin access is recognized.");
    return;
  }
  if (command === "remove-admin") {
    const approvedUid = await configuredAdminUid();
    if (!value || value !== approvedUid) throw new Error("This tool only removes the configured primary admin UID.");
    const user = await auth.getUser(approvedUid);
    const claims = { ...(user.customClaims || {}) };
    delete claims.admin;
    await auth.setCustomUserClaims(approvedUid, claims);
    console.log("Admin claim removed from the configured primary UID.");
    return;
  }
  if (command === "revoke-unexpected-admin") {
    const approvedUid = await configuredAdminUid();
    if (!value || value === approvedUid) throw new Error("Provide a non-primary UID to revoke; the approved primary UID is protected.");
    const user = await auth.getUser(value);
    if (user.customClaims?.admin !== true) throw new Error("The supplied UID does not have an admin claim.");
    const claims = { ...(user.customClaims || {}) };
    delete claims.admin;
    await auth.setCustomUserClaims(value, claims);
    console.log("Unexpected admin claim revoked from UID:", value);
    return;
  }
  if (command === "verify" || command === "reject") {
    const requestId = value;
    if (!requestId) throw new Error("Usage: node vip-admin.js verify|reject <payment-request-id> [admin-note]");
    const note = rest.join(" ").slice(0, 500);
    const operatorUid = await configuredAdminUid();
    const operator = await auth.getUser(operatorUid);
    if (operator.customClaims?.admin !== true) throw new Error("The configured operator UID does not have the admin custom claim.");
    const requestRef = db.collection("vip_payment_requests").doc(requestId);
    await db.runTransaction(async tx => {
      const requestSnap = await tx.get(requestRef);
      if (!requestSnap.exists) throw new Error("Payment request not found.");
      const payment = requestSnap.data();
      if (payment.status !== "PENDING_REVIEW") throw new Error("Request is not pending review.");
      const memberRef = db.collection("vip_members").doc(payment.uid);
      if (command === "reject") {
        tx.update(requestRef, {
          status: "REJECTED",
          adminUid: operatorUid,
          reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
          adminNote: note || "Pembayaran belum dapat diverifikasi."
        });
        return;
      }
      const plan = PLANS[payment.plan];
      if (!plan || ![plan.amount, ...(plan.legacyAmounts || [])].includes(payment.amountIdr)) {
        throw new Error("Plan/amount mismatch; refusing activation.");
      }
      const now = admin.firestore.Timestamp.now();
      const memberSnap = await tx.get(memberRef);
      if (!memberSnap.exists) throw new Error("VIP member profile does not exist. Ask the user to sign in to M Asisten first.");
      const member = memberSnap.data();
      const nowMillis = now.toMillis();
      const currentExpiry = member.expiresAt?.toMillis?.() ?? 0;
      const currentPlan = member.membershipPlan || "";
      if (member.membershipStatus === "ACTIVE" && currentPlan === "VIP Permanen" && currentExpiry > 0) {
        throw new Error("Permanent VIP record has an unexpected expiry date; review the account before changing it.");
      }
      const currentActive = member.membershipStatus === "ACTIVE" &&
        (currentExpiry === 0 ? currentPlan === "VIP Permanen" : currentExpiry > nowMillis);
      if (member.membershipStatus === "ACTIVE" && currentExpiry === 0 && currentPlan !== "VIP Permanen") {
        throw new Error("Active VIP record has no expiry but is not marked permanent; review the account before changing it.");
      }
      if (plan.days !== null && currentActive && currentExpiry === 0) {
        throw new Error("Account already has permanent VIP. Do not replace it with a monthly plan.");
      }
      const effectiveStart = currentActive && currentExpiry > nowMillis
        ? admin.firestore.Timestamp.fromMillis(currentExpiry)
        : now;
      const expiresAt = plan.days === null
        ? null
        : admin.firestore.Timestamp.fromMillis(effectiveStart.toMillis() + plan.days * 24 * 60 * 60 * 1000);
      const startedAt = currentActive ? (member.startedAt || now) : now;
      tx.update(memberRef, {
        membershipStatus: "ACTIVE",
        membershipPlan: payment.plan,
        startedAt,
        expiresAt,
        verifiedPaymentRequestId: requestId,
        updatedAt: now
      });
      tx.update(requestRef, {
        status: "VERIFIED",
        adminUid: operatorUid,
        reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
        adminNote: note || "Pembayaran diverifikasi."
      });
    });
    console.log(command === "verify" ? "Payment verified and VIP activated." : "Payment request rejected.");
    return;
  }
  console.log("Commands:");
  console.log("  node vip-admin.js audit-admins");
  console.log("  node vip-admin.js set-admin <approved-uid>");
  console.log("  node vip-admin.js remove-admin <approved-uid>");
  console.log("  node vip-admin.js revoke-unexpected-admin <other-uid>");
  console.log("  node vip-admin.js verify <request-id> [note]");
  console.log("  node vip-admin.js reject <request-id> [note]");
  process.exitCode = 2;
}
main().catch(error => { console.error("ERROR:", error.message); process.exit(1); });