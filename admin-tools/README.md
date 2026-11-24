# M Asisten — alat admin VIP+

Alat ini berjalan di komputer admin tepercaya, **bukan di aplikasi Android**. Firebase Admin SDK memakai kredensial server dan melewati Firestore Security Rules. Jangan pernah memasukkan service-account JSON ke APK, GitHub repository, atau membagikannya kepada pengguna.

## Persiapan satu kali

1. Di Firebase Console, buka **Project settings → Service accounts**.
2. Buat/unduh private service-account key hanya untuk komputer admin. Simpan di luar folder repository dan jangan unggah ke GitHub.
3. Pasang Node.js 18 atau lebih baru.
4. Dari folder `admin-tools`, jalankan `npm install`.
5. Atur variabel lingkungan `GOOGLE_APPLICATION_CREDENTIALS` ke lokasi file JSON tersebut.

Contoh Linux/macOS:

```bash
export GOOGLE_APPLICATION_CREDENTIALS="/lokasi-aman/firebase-admin-key.json"
npm install
```

Windows PowerShell:

```powershell
$env:GOOGLE_APPLICATION_CREDENTIALS="C:\lokasi-aman\firebase-admin-key.json"
npm install
```

## Jadikan akun sebagai admin

Pengguna harus sudah masuk ke M Asisten dan akun Firebase Authentication-nya sudah ada. Ambil UID dari Firebase Console → Authentication → Users, lalu:

```bash
node vip-admin.js set-admin UID_FIREBASE
```

Setelah claim dipasang, admin perlu keluar-masuk akun atau memperbarui token ID. Jangan memberi claim admin kepada akun pelanggan.

Untuk mencabut hak admin:

```bash
node vip-admin.js remove-admin UID_FIREBASE
```

## Verifikasi pembayaran

1. Periksa mutasi SeaBank secara manual dan cocokkan nominal, tanggal, serta nama pengirim/referensi dengan permintaan pembayaran di Firestore → `vip_payment_requests`.
2. Pastikan akun pelanggan sudah pernah masuk ke M Asisten sehingga dokumen `vip_members/{uid}` terbentuk.
3. Ambil ID dokumen permintaan pembayaran (bukan UID pengguna), lalu jalankan:

```bash
export M_ASISTEN_ADMIN_UID="UID_ADMIN_FIREBASE"\nnode vip-admin.js verify ID_DOKUMEN_PERMINTAAN "Transfer cocok dengan mutasi SeaBank"
```

Skrip memeriksa status harus `PENDING_REVIEW`, memvalidasi pasangan paket/nominal, lalu dalam satu transaksi mengubah permintaan menjadi `VERIFIED` dan mengaktifkan membership. Masa aktif dihitung sejak verifikasi: 7, 30, atau 365 hari; Lifetime tidak memiliki tanggal kedaluwarsa.

Jika pembayaran tidak cocok:

```bash
node vip-admin.js reject ID_DOKUMEN_PERMINTAAN "Nominal atau referensi transfer tidak cocok"
```

Skrip tidak menerima bukti transfer otomatis dan tidak memeriksa mutasi bank. Pemeriksaan rekening tetap dilakukan manusia. Jangan mengaktifkan VIP hanya berdasarkan klaim pelanggan.

## Batasan saat ini

- Ini adalah alat operasional admin, bukan panel admin di dalam aplikasi.
- Pengunggahan bukti transfer belum tersedia.
- Admin SDK tidak dapat dijalankan dari APK dan kredensialnya tidak boleh ditanam di aplikasi.
- Jalankan dari lingkungan privat dan hapus/rotasi key jika pernah terekspos.
