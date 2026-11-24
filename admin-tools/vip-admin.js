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
  "VIP+ Mingguan (7 hari)": { amount: 15000, days: 7 },
  "VIP+ Bulanan (30 hari)": { amount: 50000, days: 30 },
  "VIP+ Tahunan (365 hari)": { amount: 200000, days: 365 },
  "VIP+ Lifetime": { amount: 500000, days: null }
};

async function main() {
  const [command, value, ...rest] = process.argv.slice(2);
  if (command === "set-admin") {
    if (!value) throw new Error("Usage: node vip-admin.js set-admin <firebase-auth-uid>");
    const user = await auth.getUser(value);
    await auth.setCustomUserClaims(value, { ...(user.customClaims || {}), admin: true });
    console.log("Admin claim enabled for UID:", value);
    console.log("The admin must sign out/in or refresh the ID token before admin access is recognized.");
    return;
  }
  if (command === "remove-admin") {
    if (!value) throw new Error("Usage: node vip-admin.js remove-admin <firebase-auth-uid>");
    const user = await auth.getUser(value);
    const claims = { ...(user.customClaims || {}) };
    delete claims.admin;
    await auth.setCustomUserClaims(value, claims);
    console.log("Admin claim removed for UID:", value);
    return;
  }
  if (command === "verify" || command === "reject") {
    const requestId = value;
    if (!requestId) throw new Error("Usage: node vip-admin.js verify|reject <payment-request-id> [admin-note]");
    const note = rest.join(" ").slice(0, 500);
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
          adminUid: "trusted-admin-tool",
          reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
          adminNote: note || "Pembayaran belum dapat diverifikasi."
        });
        return;
      }
      const plan = PLANS[payment.plan];
      if (!plan || payment.amountIdr !== plan.amount) throw new Error("Plan/amount mismatch; refusing activation.");
      const now = admin.firestore.Timestamp.now();
      const expiresAt = plan.days === null
        ? null
        : admin.firestore.Timestamp.fromMillis(now.toMillis() + plan.days * 24 * 60 * 60 * 1000);
      const memberSnap = await tx.get(memberRef);
      if (!memberSnap.exists) throw new Error("VIP member profile does not exist. Ask the user to sign in to M Asisten first.");
      tx.update(memberRef, {
        membershipStatus: "ACTIVE",
        membershipPlan: payment.plan,
        startedAt: now,
        expiresAt,
        verifiedPaymentRequestId: requestId,
        updatedAt: now
      });
      tx.update(requestRef, {
        status: "VERIFIED",
        adminUid: "trusted-admin-tool",
        reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
        adminNote: note || "Pembayaran diverifikasi."
      });
    });
    console.log(command === "verify" ? "Payment verified and VIP+ activated." : "Payment request rejected.");
    return;
  }
  console.log("Commands:");
  console.log("  node vip-admin.js set-admin <uid>");
  console.log("  node vip-admin.js remove-admin <uid>");
  console.log("  node vip-admin.js verify <request-id> [note]");
  console.log("  node vip-admin.js reject <request-id> [note]");
  process.exitCode = 2;
}
main().catch(error => { console.error("ERROR:", error.message); process.exit(1); });
