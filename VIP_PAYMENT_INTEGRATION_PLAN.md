# Proses Pembayaran VIP M Asisten

## Status dan paket aktif

- Aplikasi: `com.m.launchervoice`
- Firebase project: `m-messenger-9d903`
- Metode saat ini: transfer manual ke rekening SeaBank.
- Verifikasi: admin mencocokkan transfer dengan mutasi SeaBank sebelum menyetujui permintaan.
- Pembayaran otomatis melalui payment gateway belum diintegrasikan.

| Paket | Harga normal | Harga sekarang | Masa aktif |
|---|---:|---:|---|
| VIP Bulanan | Rp60.000 | Rp29.000 | 30 hari |
| VIP Permanen | Rp720.000 | Rp499.000 | Tidak kedaluwarsa |

Tidak ada paket mingguan atau tahunan yang ditawarkan pada versi ini.

## Alur pembayaran saat ini

1. Pengguna masuk dengan akun Google.
2. Pengguna memilih VIP Bulanan atau VIP Permanen.
3. Aplikasi membuat permintaan di `vip_payment_requests` dengan nominal yang ditentukan aplikasi dan divalidasi ulang oleh Firestore Rules.
4. Pengguna melakukan transfer SeaBank dan mengisi nama pengirim serta referensi/bukti transfer.
5. Status permintaan menjadi `PENDING_REVIEW`.
6. Admin membuka daftar permintaan melalui panel admin M Asisten dan mencocokkan nama, nominal, referensi, serta mutasi SeaBank.
7. Admin memilih verifikasi atau tolak. Aplikasi menggunakan transaksi Firestore untuk memperbarui permintaan dan keanggotaan secara atomik.
8. Status keanggotaan disimpan pada `vip_members/{uid}`; aplikasi membaca status dari server.

**Penting:** bukti gambar atau nomor referensi saja bukan bukti pembayaran final. Admin harus memastikan dana benar-benar masuk pada mutasi SeaBank.

## Struktur data penting

### `vip_payment_requests/{requestId}`

Permintaan pembayaran milik pengguna berisi UID, email, nama paket, nominal IDR, metode SeaBank, nama pengirim, referensi transfer, status, dan waktu pembuatan. Pengguna hanya dapat membuat dan membaca permintaannya sendiri. Pengguna tidak dapat menyetujui pembayaran sendiri.

Nominal aktif yang diterima:
- `VIP Bulanan (30 hari)`: `29000`
- `VIP Permanen`: `499000`

Paket lama dan nominal yang tidak sesuai harus ditolak oleh Firestore Rules.

### `vip_members/{uid}`

- VIP Bulanan harus memiliki `membershipStatus: "ACTIVE"`, `membershipPlan: "VIP Bulanan (30 hari)"`, `startedAt` timestamp, dan `expiresAt` timestamp.
- VIP Permanen harus memiliki `membershipStatus: "ACTIVE"`, `membershipPlan: "VIP Permanen"`, `startedAt` timestamp, dan `expiresAt: null`.
- Hak menulis field keanggotaan hanya diberikan kepada custom claim admin tepercaya.
- Pembelian bulanan memperpanjang tanggal kedaluwarsa dari tanggal kedaluwarsa aktif yang masih tersisa.
- Paket bulanan tidak boleh menggantikan keanggotaan permanen.

## Keamanan operasional

- Jangan menaruh service-account JSON, private key, atau kredensial Firebase Admin di APK atau repository.
- Hanya UID utama yang ditetapkan melalui `M_ASISTEN_ADMIN_UID` yang boleh memiliki custom claim `admin: true`.
- Jalankan `node vip-admin.js audit-admins` secara berkala dari lingkungan admin tepercaya.
- Admin harus mencocokkan pembayaran dengan mutasi bank; jangan mengandalkan nilai yang dikirim klien.
- Firestore Rules pada repository sudah diuji dengan emulator, tetapi pemilik project tetap harus menerbitkan rules yang sama ke Firebase Console.
- Firebase App Check belum diintegrasikan/enforced; lihat `SECURITY_REVIEW.md`.

## Batasan

Sistem ini adalah verifikasi transfer manual, bukan payment gateway otomatis. Pengaktifan VIP bergantung pada pemeriksaan admin. Perubahan paket atau harga harus disamakan di UI Android, panel admin, Firestore Rules, dan pengujian keamanan sebelum build baru dibuat.
