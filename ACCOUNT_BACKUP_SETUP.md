# Akun Google, Keanggotaan, dan Keamanan M Asisten

## Identitas aplikasi

- Android package: `com.m.launchervoice`
- Firebase project: `m-messenger-9d903`
- Identitas akun: Firebase Authentication UID dari login Google.
- Profil dan status keanggotaan: Cloud Firestore.

## Koleksi Firestore

- `vip_members/{uid}`: status dan masa aktif VIP.
- `vip_payment_requests/{requestId}`: permintaan transfer SeaBank dan status pemeriksaan admin.
- `trial_entitlements/{uid}`: hak Trial 72 jam yang terikat pada akun.
- `trial_devices/{deviceId}`: penguncian klaim Trial pada ID perangkat Android.
- `trial_clock/{uid}`: timestamp server tepercaya untuk perhitungan Trial dan cache VIP.

Klien Android tidak boleh mengubah status keanggotaan, masa berlaku, hasil verifikasi pembayaran, atau hak admin. Field keanggotaan hanya dapat diubah oleh akun yang memiliki custom claim admin tepercaya.

## Admin utama

- Hanya satu UID utama yang boleh memiliki custom claim `admin: true`.
- UID yang diizinkan ditetapkan melalui environment variable `M_ASISTEN_ADMIN_UID`.
- Gunakan `node vip-admin.js audit-admins` untuk memeriksa semua pengguna yang memiliki claim admin.
- Gunakan `node vip-admin.js revoke-unexpected-admin UID_LAIN` untuk mencabut claim dari UID selain UID utama setelah kepemilikannya diperiksa.
- Service-account JSON harus tetap berada di lingkungan admin pribadi, tidak boleh masuk Git, APK, atau file publik.

## Trial dan cache offline

- Trial berlangsung 72 jam dan memakai waktu server Firestore, bukan jam perangkat.
- Firestore mengikat klaim Trial pada UID dan ID perangkat Android. Ini mencegah penggunaan ulang normal, tetapi bukan perlindungan hardware yang tidak dapat dipalsukan; factory reset atau perangkat yang dimodifikasi dapat melemahkan penguncian.
- Cache VIP berjangka menggunakan timestamp server tepercaya dan jam monotonic perangkat. Cache hanya berlaku pada boot yang sama; jika waktu server atau penghitung boot tidak dapat diverifikasi, paket berjangka tidak disimpan sebagai akses offline. Android API 23 tetap bisa memakai VIP saat online, tetapi harus memverifikasi ulang setelah offline.
- VIP permanen masih memiliki grace offline hingga 30 hari sejak verifikasi terakhir.
- Pencabutan VIP oleh admin tidak dapat diterima perangkat yang sedang offline sampai perangkat tersambung kembali.

## Firestore Rules

File sumber aturan berada di `firestore.rules`. Pengujian emulator pada branch hardening mencakup Trial, VIP, pembayaran, admin, dan backup. Lulusnya pengujian tidak otomatis menerbitkan aturan ke Firebase.

Sebelum rilis:
1. Bandingkan isi `firestore.rules` dengan aturan di Firebase Console.
2. Terbitkan aturan yang sudah diperiksa ke project `m-messenger-9d903`.
3. Pastikan project menggunakan production rules, bukan test mode.
4. Jangan mengaktifkan enforcement sebelum uji akses pengguna, admin, Trial, dan pembayaran selesai.

## Firebase App Check

App Check belum diintegrasikan atau diberlakukan. Untuk distribusi APK langsung melalui Netlify, Play Integrity memiliki konfigurasi off-Play yang dapat dipakai, tetapi memerlukan pengaitan project Firebase dengan Play Integrity melalui Play Console dan pendaftaran SHA-256 sertifikat APK. Ikuti checklist pada `SECURITY_REVIEW.md`; jangan mengaktifkan enforcement sebelum uji perangkat asli berhasil.

## Status rilis

Aplikasi belum disetujui untuk rilis publik. Sebelum rilis, selesaikan semua release gate pada `SECURITY_REVIEW.md`, uji APK di perangkat asli, dan minta persetujuan pemilik project.
