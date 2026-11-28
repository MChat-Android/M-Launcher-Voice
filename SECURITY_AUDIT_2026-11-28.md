# Catatan Audit Keamanan M Asisten — 28 November 2026

## Lingkup
- Repository: `MChat-Android/M-Launcher-Voice`
- Branch: `admob/restore`
- Fokus: aturan Firestore, akun admin, verifikasi pembayaran VIP, Trial 72 jam, cache akses offline, dan pipeline APK release.
- Status distribusi: belum dirilis ke publik.

## Konfigurasi produk yang diperiksa
- VIP Bulanan (30 hari): Rp29.000; harga pembanding Rp60.000.
- VIP Permanen: Rp499.000; harga pembanding Rp720.000.
- Trial VIP: 72 jam, satu kali per akun dan satu kali per Android ID.
- Permintaan pembayaran dibuat sebagai `PENDING_REVIEW`; aktivasi berbayar dilakukan setelah admin memeriksa mutasi SeaBank.
- Klaim admin berasal dari Firebase Authentication custom claim, bukan dari data yang dapat ditulis pengguna biasa.

## Kontrol yang telah diperiksa
- Firestore membatasi pembuatan permintaan pembayaran pada pemilik akun dengan email terverifikasi, paket dan nominal yang tepat, serta status awal `PENDING_REVIEW`.
- Pengguna biasa tidak dapat mengubah status VIP, menyetujui pembayaran sendiri, mengubah metadata simulasi VIP, atau mengubah/menghapus catatan Trial.
- Catatan Trial dan klaim Android ID dibuat dalam satu batch dengan timestamp server. Aturan menolak penggunaan ulang akun/perangkat.
- Alat admin memeriksa custom claim admin dan memverifikasi permintaan dalam transaksi Firestore; kredensial service account tidak boleh dimasukkan ke APK atau repository.
- Cache akses VIP/admin offline telah diperketat dari 30 hari menjadi 72 jam pada commit `a551337446ea484975a0d85303d078c3695e2599`.
- Batas kedaluwarsa cache juga memakai jam monotonik Android (`elapsedRealtime`), bukan hanya jam kalender perangkat, pada commit `dd78df17942f80dab2343f02a8a5e41d1e51e77f`.
- CI memeriksa batas 72 jam dan keberadaan pemeriksaan kedaluwarsa monotonik pada commit `caa81bc8694f1ad23d52fc3fb5c24f03f362001a`.

## Hasil CI yang sudah terkonfirmasi
Pada commit dasar `6e534e92b87af6fab294880dc69c28c9dd2a5b1d`, GitHub Actions run #403 menyelesaikan:
- Pemeriksaan sintaks alat admin: berhasil.
- Audit dependensi admin: berhasil.
- Pengujian Firestore Security Rules: 42 pemeriksaan lulus.
- Build release: berhasil.
- Verifikasi tanda tangan APK: skema v1 dan v2 valid.
- Artefak `M-Asisten-APK` berhasil diunggah.

Catatan: hasil di atas adalah baseline sebelum perubahan cache offline 72 jam, pemeriksaan waktu monotonik, dan penambahan pemeriksaan CI. Perubahan tersebut memicu pipeline baru; status akhirnya harus dikonfirmasi dari run terbaru, bukan diasumsikan dari run #403.

## Batasan dan risiko tersisa
1. Android ID bukan identitas perangkat yang tidak dapat diubah. Reset perangkat, profil pengguna, atau modifikasi sistem dapat mengubahnya; pembatasan perangkat Trial adalah pencegah penyalahgunaan, bukan jaminan anti-fraud mutlak.
2. Pemeriksaan VIP di aplikasi Android tetap dapat dimodifikasi oleh pihak yang membongkar atau mem-patch APK. Jangan menempatkan operasi bernilai tinggi atau data rahasia hanya di balik pemeriksaan lokal; validasi hak akses harus dilakukan di server untuk fitur yang membutuhkan perlindungan kuat.
3. Minifikasi/obfuscation R8 saat ini belum diaktifkan (`isMinifyEnabled = false`). R8 dapat menaikkan biaya reverse engineering, tetapi bukan pengganti otorisasi server.
4. Cache offline 72 jam adalah kompromi agar aplikasi tetap dapat dipakai tanpa koneksi sementara. Pencabutan VIP/admin saat perangkat offline baru dipastikan setelah masa cache habis atau server tersambung kembali.
5. Kredensial service account, keystore, dan password penandatanganan harus tetap berada di penyimpanan privat/secret manager. Jangan pernah mengunggah file tersebut ke repository atau APK.

## Syarat sebelum rilis
- Pastikan GitHub Actions pada commit terbaru lulus untuk security checks dan build.
- Uji APK hasil build pada perangkat nyata: login, pembelian/pengajuan VIP, verifikasi admin, kedaluwarsa paket, Trial 72 jam, dan perilaku setelah offline lebih dari 72 jam.
- Jangan publikasikan APK sebelum pengujian perangkat dan pemeriksaan akhir selesai.
