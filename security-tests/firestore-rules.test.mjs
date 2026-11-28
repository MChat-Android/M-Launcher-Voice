import fs from "node:fs";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment
} from "@firebase/rules-unit-testing";
import {
  Timestamp,
  addDoc,
  collection,
  doc,
  getDoc,
  getDocs,
  query,
  serverTimestamp,
  setDoc,
  updateDoc,
  where,
  writeBatch,
  deleteDoc
} from "firebase/firestore";

const projectId = "demo-m-launcher-voice";
const rules = fs.readFileSync("firestore.rules", "utf8");
const env = await initializeTestEnvironment({
  projectId,
  firestore: { rules }
});

let passed = 0;
async function allow(label, operation) {
  await assertSucceeds(operation);
  passed += 1;
  console.log("PASS:", label);
}
async function deny(label, operation) {
  await assertFails(operation);
  passed += 1;
  console.log("PASS:", label);
}

try {
  const alice = env.authenticatedContext("alice", {
    email: "alice@example.com",
    email_verified: true
  }).firestore();
  const bob = env.authenticatedContext("bob", {
    email: "bob@example.com",
    email_verified: true
  }).firestore();
  const admin = env.authenticatedContext("admin", {
    email: "admin@example.com",
    email_verified: true,
    admin: true
  }).firestore();
  const guest = env.unauthenticatedContext().firestore();

  await env.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    await setDoc(doc(db, "vip_members/alice"), {
      uid: "alice",
      email: "alice@example.com",
      displayName: "Alice",
      provider: "google",
      lastLoginAt: Timestamp.now(),
      updatedAt: Timestamp.now(),
      membershipStatus: "ACTIVE",
      membershipPlan: "VIP Bulanan (30 hari)",
      startedAt: Timestamp.now(),
      expiresAt: Timestamp.fromMillis(Date.now() + 86400000)
    });
  });

  await allow("akun dapat menulis jam tepercaya dengan timestamp server", setDoc(doc(alice, "trial_clock/alice"), {
    serverTime: serverTimestamp()
  }));
  await allow("akun dapat memperbarui jam tepercaya miliknya", setDoc(doc(alice, "trial_clock/alice"), {
    serverTime: serverTimestamp()
  }));
  await deny("akun lain tidak dapat membaca jam tepercaya", getDoc(doc(bob, "trial_clock/alice")));
  await deny("akun tidak dapat mengatur jam tepercaya memakai waktu buatan", setDoc(doc(alice, "trial_clock/alice"), {
    serverTime: Timestamp.fromMillis(Date.now() + 86400000)
  }));
  await deny("akun tidak dapat menghapus jam tepercaya", deleteDoc(doc(alice, "trial_clock/alice")));

  const trialDeviceId = "device_1234567890123456";
  await allow("pengguna dapat memeriksa Android ID yang belum diklaim", getDoc(doc(alice, "trial_devices/" + trialDeviceId)));
  const trialBatch = writeBatch(alice);
  trialBatch.set(doc(alice, "trial_devices/" + trialDeviceId), {
    uid: "alice",
    deviceId: trialDeviceId,
    startedAt: serverTimestamp()
  });
  trialBatch.set(doc(alice, "trial_entitlements/alice"), {
    uid: "alice",
    deviceId: trialDeviceId,
    startedAt: serverTimestamp()
  });
  await allow("akun dapat memulai Trial satu kali dengan waktu server", trialBatch.commit());
  await allow("akun dapat membaca data Trial miliknya", getDoc(doc(alice, "trial_entitlements/alice")));
  await deny("akun lain tidak dapat membaca data Trial pengguna", getDoc(doc(bob, "trial_entitlements/alice")));
  await deny("akun lain tidak dapat membaca data perangkat Trial", getDoc(doc(bob, "trial_devices/" + trialDeviceId)));
  await deny("akun tidak dapat mengklaim perangkat tanpa entitlement dalam batch yang sama", setDoc(
    doc(bob, "trial_devices/device_unpaired_123456"), {
      uid: "bob",
      deviceId: "device_unpaired_123456",
      startedAt: serverTimestamp()
    }
  ));
  await deny("akun tidak dapat memperpanjang atau mengubah Trial", updateDoc(doc(alice, "trial_entitlements/alice"), {
    startedAt: serverTimestamp()
  }));
  await deny("akun tidak dapat menghapus catatan Trial", deleteDoc(doc(alice, "trial_entitlements/alice")));
  await deny("akun lain tidak dapat memakai Android ID yang sudah diklaim", setDoc(
    doc(bob, "trial_devices/" + trialDeviceId), {
      uid: "bob",
      deviceId: trialDeviceId,
      startedAt: serverTimestamp()
    }
  ));
  const secondDeviceBatch = writeBatch(alice);
  secondDeviceBatch.set(doc(alice, "trial_devices/device_abcdefghijklmnop"), {
    uid: "alice",
    deviceId: "device_abcdefghijklmnop",
    startedAt: serverTimestamp()
  });
  secondDeviceBatch.set(doc(alice, "trial_entitlements/alice"), {
    uid: "alice",
    deviceId: "device_abcdefghijklmnop",
    startedAt: serverTimestamp()
  });
  await deny("akun tidak dapat mengulang Trial pada perangkat kedua", secondDeviceBatch.commit());

  await allow("anggota dapat membaca status VIP miliknya", getDoc(doc(alice, "vip_members/alice")));
  await deny("anggota tidak dapat membaca status VIP pengguna lain", getDoc(doc(bob, "vip_members/alice")));
  await deny("pengunjung tanpa login tidak dapat membaca profil VIP", getDoc(doc(guest, "vip_members/alice")));
  await allow("admin dapat membaca profil VIP", getDoc(doc(admin, "vip_members/alice")));
  await deny("pengguna biasa tidak dapat mengubah status VIP", updateDoc(doc(alice, "vip_members/alice"), {
    membershipStatus: "FREE",
    updatedAt: serverTimestamp()
  }));
  await deny("pengguna tidak dapat membuat profil VIP aktif sendiri", setDoc(doc(bob, "vip_members/bob"), {
    uid: "bob",
    email: "bob@example.com",
    displayName: "Bob",
    provider: "google",
    lastLoginAt: serverTimestamp(),
    updatedAt: serverTimestamp(),
    membershipStatus: "ACTIVE"
  }));
  await allow("admin dapat mengubah status VIP dengan claim admin", updateDoc(doc(admin, "vip_members/alice"), {
    membershipStatus: "FREE",
    updatedAt: serverTimestamp()
  }));

  const requestRef = doc(collection(alice, "vip_payment_requests"));
  await allow("pengguna dapat mengirim permintaan VIP Bulanan Rp29.000", setDoc(requestRef, {
    uid: "alice",
    email: "alice@example.com",
    displayName: "Alice",
    plan: "VIP Bulanan (30 hari)",
    amountIdr: 29000,
    paymentMethod: "SEABANK_TRANSFER",
    bankName: "SeaBank",
    accountNumber: "901023118338",
    accountBrandName: "M Asisten",
    registeredAccountHolder: "Muhamad Soleh",
    payerName: "Alice Example",
    transferReference: "TRX-TEST-001",
    status: "PENDING_REVIEW",
    createdAt: serverTimestamp()
  }));
  await allow("pemilik dapat membaca permintaan pembayarannya", getDoc(doc(alice, requestRef.path)));
  await deny("pengguna lain tidak dapat membaca permintaan pembayaran", getDoc(doc(bob, requestRef.path)));
  await deny("pengguna tidak dapat mengirim nominal yang tidak sesuai paket", addDoc(collection(bob, "vip_payment_requests"), {
    uid: "bob",
    email: "bob@example.com",
    displayName: "Bob",
    plan: "VIP Bulanan (30 hari)",
    amountIdr: 1,
    paymentMethod: "SEABANK_TRANSFER",
    bankName: "SeaBank",
    accountNumber: "901023118338",
    accountBrandName: "M Asisten",
    registeredAccountHolder: "Muhamad Soleh",
    payerName: "Bob Example",
    transferReference: "TRX-TEST-002",
    status: "PENDING_REVIEW",
    createdAt: serverTimestamp()
  }));
  await deny("paket VIP Mingguan yang sudah dihapus tidak dapat diajukan", addDoc(collection(bob, "vip_payment_requests"), {
    uid: "bob",
    email: "bob@example.com",
    displayName: "Bob",
    plan: "VIP Mingguan (7 hari)",
    amountIdr: 15000,
    paymentMethod: "SEABANK_TRANSFER",
    bankName: "SeaBank",
    accountNumber: "901023118338",
    accountBrandName: "M Asisten",
    registeredAccountHolder: "Muhamad Soleh",
    payerName: "Bob Example",
    transferReference: "TRX-TEST-WEEKLY-RETIRED",
    status: "PENDING_REVIEW",
    createdAt: serverTimestamp()
  }));

  await deny("pengguna tidak dapat menyetujui pembayaran sendiri", updateDoc(doc(alice, requestRef.path), {
    status: "VERIFIED",
    adminUid: "alice",
    reviewedAt: serverTimestamp()
  }));
  await allow("admin dapat memuat antrean pembayaran tertunda", getDocs(query(
    collection(admin, "vip_payment_requests"),
    where("status", "==", "PENDING_REVIEW")
  )));
  await allow("admin dapat memverifikasi permintaan pembayaran", updateDoc(doc(admin, requestRef.path), {
    status: "VERIFIED",
    adminUid: "admin",
    reviewedAt: serverTimestamp(),
    adminNote: "Uji aturan Firestore"
  }));

  const permanentRequestRef = doc(collection(alice, "vip_payment_requests"));
  await allow("pengguna dapat mengajukan VIP Permanen Rp499.000", setDoc(permanentRequestRef, {
    uid: "alice",
    email: "alice@example.com",
    displayName: "Alice",
    plan: "VIP Permanen",
    amountIdr: 499000,
    paymentMethod: "SEABANK_TRANSFER",
    bankName: "SeaBank",
    accountNumber: "901023118338",
    accountBrandName: "M Asisten",
    registeredAccountHolder: "Muhamad Soleh",
    payerName: "Alice Example",
    transferReference: "TRX-TEST-PERMANEN",
    status: "PENDING_REVIEW",
    createdAt: serverTimestamp()
  }));
  await deny("paket permanen dengan nominal lama Rp720.000 ditolak", setDoc(
    doc(collection(bob, "vip_payment_requests")), {
      uid: "bob",
      email: "bob@example.com",
      displayName: "Bob",
      plan: "VIP Permanen",
      amountIdr: 720000,
      paymentMethod: "SEABANK_TRANSFER",
      bankName: "SeaBank",
      accountNumber: "901023118338",
      accountBrandName: "M Asisten",
      registeredAccountHolder: "Muhamad Soleh",
      payerName: "Bob Example",
      transferReference: "TRX-TEST-PERMANEN-OLD",
      status: "PENDING_REVIEW",
      createdAt: serverTimestamp()
    }
  ));
  await deny("nominal permanen Rp500.000 yang bukan harga aktif ditolak", setDoc(
    doc(collection(bob, "vip_payment_requests")), {
      uid: "bob",
      email: "bob@example.com",
      displayName: "Bob",
      plan: "VIP Permanen",
      amountIdr: 500000,
      paymentMethod: "SEABANK_TRANSFER",
      bankName: "SeaBank",
      accountNumber: "901023118338",
      accountBrandName: "M Asisten",
      registeredAccountHolder: "Muhamad Soleh",
      payerName: "Bob Example",
      transferReference: "TRX-TEST-PERMANEN-500",
      status: "PENDING_REVIEW",
      createdAt: serverTimestamp()
    }
  ));

  await allow("admin dapat menyimpan metadata simulasi aktivasi VIP", updateDoc(doc(admin, "vip_members/alice"), {
    membershipStatus: "ACTIVE",
    membershipPlan: "VIP Bulanan (30 hari)",
    startedAt: Timestamp.now(),
    expiresAt: Timestamp.fromMillis(Date.now() + 30 * 86400000),
    testMode: true,
    testPaymentRequestId: "SIM-TEST-001",
    updatedAt: serverTimestamp()
  }));

  await deny("admin tidak dapat mengaktifkan paket bulanan tanpa tanggal kedaluwarsa", updateDoc(doc(admin, "vip_members/alice"), {
    membershipStatus: "ACTIVE",
    membershipPlan: "VIP Bulanan (30 hari)",
    startedAt: Timestamp.now(),
    expiresAt: null,
    updatedAt: serverTimestamp()
  }));

  await allow("admin dapat mengaktifkan VIP Permanen tanpa tanggal kedaluwarsa", updateDoc(doc(admin, "vip_members/alice"), {
    membershipStatus: "ACTIVE",
    membershipPlan: "VIP Permanen",
    startedAt: Timestamp.now(),
    expiresAt: null,
    updatedAt: serverTimestamp()
  }));
  await deny("pengguna biasa tidak dapat menulis metadata simulasi VIP", updateDoc(doc(alice, "vip_members/alice"), {
    testMode: true,
    testPaymentRequestId: "SIM-TEST-USER",
    updatedAt: serverTimestamp()
  }));
  await deny("admin tidak boleh mengubah data pengirim saat memverifikasi", updateDoc(doc(admin, requestRef.path), {
    payerName: "Nama Diubah",
    status: "REJECTED",
    adminUid: "admin",
    reviewedAt: serverTimestamp()
  }));

  const deviceId = "device_1234567890123456";
  await allow("pengguna email terverifikasi dapat membuat profil perangkat", setDoc(doc(alice, "users/alice"), {
    memberId: "MA-AB12CD34EF",
    displayName: "Alice",
    activeDeviceId: deviceId,
    deviceChangedAt: serverTimestamp(),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  }));
  const backupRef = doc(alice, "users/alice/backups/backup-1");
  await allow("perangkat aktif dapat menyimpan backup", setDoc(backupRef, {
    deviceId,
    schemaVersion: 1,
    payload: {
      displayName: "Alice",
      preferences: { theme: "dark" }
    },
    updatedAt: serverTimestamp()
  }));
  await allow("perangkat aktif dapat membaca backup miliknya", getDoc(backupRef));
  await deny("perangkat lain tidak dapat menulis backup", setDoc(doc(alice, "users/alice/backups/backup-2"), {
    deviceId: "different_device_123456789",
    schemaVersion: 1,
    payload: { displayName: "Alice", preferences: {} },
    updatedAt: serverTimestamp()
  }));
  await deny("pengguna lain tidak dapat membaca backup", getDoc(doc(bob, "users/alice/backups/backup-1")));
  await deny("akun email belum terverifikasi tidak dapat membuat profil", setDoc(doc(
    env.authenticatedContext("unverified", {
      email: "unverified@example.com",
      email_verified: false
    }).firestore(),
    "users/unverified"
  ), {
    memberId: "MA-AB12CD34EF",
    displayName: "Unverified",
    activeDeviceId: deviceId,
    deviceChangedAt: serverTimestamp(),
    createdAt: serverTimestamp(),
    updatedAt: serverTimestamp()
  }));

  console.log(`\nAll ${passed} Firestore security-rule checks passed.`);
} finally {
  await env.cleanup();
}
