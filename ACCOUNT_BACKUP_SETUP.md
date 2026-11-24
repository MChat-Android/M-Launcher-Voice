# Akun Google dan Keanggotaan VIP+ M Asisten

## Rancangan akun
- Aplikasi Android: `com.m.launchervoice`.
- Pengguna masuk dengan akun Google melalui Firebase Authentication.
- Firebase UID menjadi identitas akun yang stabil; jangan memakai ID acak lokal sebagai identitas keanggotaan.
- Profil anggota disimpan di Cloud Firestore pada `vip_members/{uid}`.
- Aplikasi hanya dapat membaca status VIP+. Aplikasi tidak boleh mengubah status aktif, masa berlaku, peran admin, atau bukti pembayaran.
- Status VIP+ hanya boleh diubah oleh proses tepercaya di sisi server setelah pembayaran diverifikasi.

## Persiapan Firebase Console (wajib sebelum login Google dapat dipakai)
1. Buka project Firebase yang dipakai oleh M Asisten.
2. Di **Project settings → General → Your apps**, pastikan aplikasi Android memiliki package name persis `com.m.launchervoice`.
3. Tambahkan SHA-1 dan SHA-256 sertifikat signing release aplikasi Android pada pengaturan aplikasi Firebase. Gunakan sertifikat signing yang sama dengan APK release.
4. Buka **Authentication → Sign-in method** lalu aktifkan provider **Google**.
5. Pastikan alamat email dukungan project diatur jika Firebase memintanya.
6. Buka **Project settings → Your apps → Android app**, unduh ulang `google-services.json` setelah konfigurasi Google selesai.
7. Ganti secret GitHub Actions `GOOGLE_SERVICES_JSON_BASE64` dengan Base64 dari file terbaru. Jangan mengunggah atau menempelkan file yang berisi konfigurasi privat ke percakapan publik.
8. Pastikan file baru memiliki OAuth client untuk aplikasi Android dan web. Gradle Google Services menghasilkan resource `default_web_client_id` yang dipakai proses login.

## Firestore
1. Buat database Cloud Firestore dalam **production mode**.
2. Pasang isi file `firestore.rules` pada tab **Firestore Database → Rules**, lalu terbitkan/publish.
3. Aturan `vip_members` mengizinkan pemilik akun Google terverifikasi membaca profil dan memperbarui data profil terbatas. Klien tidak dapat menulis `membershipStatus` atau data pembayaran.
4. Backend tepercaya (misalnya Cloud Functions/Admin SDK) harus mengelola status VIP+ setelah pembayaran dikonfirmasi. Jangan pernah memberikan hak admin atau kredensial service account ke aplikasi Android.
5. Jangan menggunakan Firestore test mode untuk aplikasi rilis.

## Status implementasi
- Login Google Android dan sinkronisasi profil VIP+ telah ditambahkan ke kode aplikasi.
- Aplikasi membaca status keanggotaan dari Firestore; status baru dianggap aktif jika dokumen server berisi `membershipStatus: "ACTIVE"`.
- Integrasi pembayaran QRIS/transfer, verifikasi otomatis, pengelolaan masa berlaku, dan panel admin belum diaktifkan.
- Build CI tidak dapat mengaktifkan provider Google di Firebase Console atau menerbitkan Firestore Rules secara otomatis. Dua langkah Console di atas perlu dilakukan oleh pemilik project.

## Keamanan sebelum rilis
- Aktifkan Firebase App Check dan batasi API key Google untuk aplikasi Android.
- Siapkan backend tepercaya untuk verifikasi pembayaran dan pembaruan status.
- Jangan menyimpan kata sandi, token Google, atau kredensial service account di Firestore maupun kode aplikasi.
- Siapkan kebijakan privasi, ketentuan VIP+, kebijakan pengembalian dana, dan saluran bantuan.
