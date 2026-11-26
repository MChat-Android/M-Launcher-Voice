# M Asisten — Audit Sumber Offline AI

Tanggal audit: 26 November 2026  
Branch: `main`  
Workflow sumber aplikasi: `.github/workflows/blank.yml`  
Audit berdasarkan isi workflow yang benar-benar menghasilkan proyek Android. Dokumen ini tidak mengubah kode aplikasi.

## Ringkasan temuan yang terverifikasi

1. **Belum ada LLM lokal.** Sumber saat ini menggunakan mesin perintah deterministik Kotlin melalui `executeCommand()`. Audit workflow tidak menemukan integrasi runtime LLM, tokenizer, berkas model, llama.cpp, atau LiteRT-LM. Jadi aplikasi belum boleh dipasarkan sebagai AI generatif offline.
2. **Pengenalan suara diarahkan ke on-device.** Kode menggunakan `SpeechRecognizer.isOnDeviceRecognitionAvailable()` dan `SpeechRecognizer.createOnDeviceSpeechRecognizer()`; tidak membuat fallback ke recognizer cloud. Namun ketersediaan bahasa bergantung pada layanan/paket bahasa yang didukung perangkat.
3. **Bahasa ASR mengikuti bahasa utama perangkat.** Jika bahasa itu tidak tersedia, implementasi Android 13+ mencoba English; bukan selalu Bahasa Indonesia. Jika paket belum terpasang, aplikasi meminta pengguna menyiapkan/mengunduh paket bahasa melalui layanan Android.
4. **TTS belum dijamin offline Bahasa Indonesia.** Kode memilih bahasa utama perangkat, lalu fallback ke English (US). Ketersediaan suara dan kemampuan offline bergantung pada mesin TTS dan data suara yang terpasang.
5. **Firebase masih menjadi layanan online.** Firebase Auth/Firestore dipakai untuk akun, profil, status VIP/VIP+, dan permintaan pembayaran. Karena itu fitur akun/validasi keanggotaan tertentu tidak dapat dianggap offline penuh.
6. **Belum ada kebijakan pemuatan model berdasarkan RAM.** Belum ditemukan pemilih model, deteksi memori untuk inferensi, pengelolaan lifecycle model, atau fallback LLM khusus perangkat RAM 4 GB.
7. Identitas aplikasi pada manifest yang dihasilkan masih `M Asisten`. Jangan ubah label, nama APK, sistem VIP/VIP+, tampilan, atau alur akun sebagai bagian dari pekerjaan AI tanpa kebutuhan dan pengujian terpisah.

## Implikasi untuk persyaratan RAM 4 GB

RAM 4 GB adalah persyaratan kompatibilitas aplikasi, bukan janji bahwa setiap ponsel 4 GB dapat menjalankan LLM generatif dengan lancar. Pada perangkat tersebut:
- Mesin perintah lokal yang sudah ada harus tetap tersedia meski model AI tidak dapat dimuat.
- Model generatif harus opsional, dimuat hanya setelah pemeriksaan kemampuan/perkiraan memori, dan dapat dilepas saat aplikasi masuk latar belakang atau tekanan memori.
- Jangan menjalankan inferensi LLM bersamaan dengan proses suara berat jika berisiko OOM.
- Gunakan model kecil terkuantisasi hanya sebagai kandidat uji; ukuran 0,5B–1B bukan jaminan kompatibilitas.
- Jangan pernah mengirim prompt/transkrip ke server sebagai fallback otomatis. Kegagalan model harus menghasilkan fallback lokal yang jelas.
- Catat perangkat, versi Android, chipset, RAM tersedia, ukuran model, peak PSS/RSS, latensi token pertama, token/detik, crash/OOM, suhu, dan konsumsi baterai pada uji perangkat nyata.

## Urutan implementasi yang aman

### Tahap 1 — Amankan dan rapikan sumber build
- Pertahankan workflow yang saat ini membangkitkan project melalui heredoc sampai seluruh sumber berhasil diekstrak dan dibandingkan.
- Ekstrak file sumber yang dihasilkan ke struktur proyek biasa dalam branch kerja terpisah.
- Bandingkan hasil ekstraksi dengan hasil generator lama; pastikan manifest, resource, versi, signing, Firebase, VIP/VIP+, widget, izin, dan nama APK tidak berubah.
- Jalankan security checks dan build APK sebelum menggabungkan perubahan.

### Tahap 2 — Pisahkan mesin perintah dari lapisan suara
- Pisahkan pemrosesan perintah deterministik dari service mikrofon/TTS tanpa mengubah perilaku yang terlihat pengguna.
- Pastikan perintah inti tetap bekerja tanpa Firebase dan tanpa model LLM, selama izin Android yang dibutuhkan tersedia.
- Tambahkan status yang jujur untuk bahasa ASR/TTS offline: tersedia, perlu paket bahasa, atau tidak didukung.

### Tahap 3 — Tambahkan fondasi runtime AI lokal
- Evaluasi runtime Android yang benar-benar didukung, ukuran dependensi APK, lisensi, ABI, dan dukungan perangkat.
- Buat antarmuka model lokal yang dapat dinonaktifkan; jangan mengunci UI atau mesin perintah pada satu runtime.
- LLM hanya menghasilkan intent/parameter terstruktur. Validasi allowlist dan konfirmasi untuk tindakan sensitif tetap dilakukan oleh aplikasi.
- Model didistribusikan terpisah dari APK, dengan checksum/verifikasi integritas dan opsi hapus/unduh ulang.

### Tahap 4 — Profil perangkat dan uji 4 GB
- Buat profil kemampuan berdasarkan RAM yang tersedia, versi Android, ABI, dan hasil benchmark; jangan hanya mengandalkan angka RAM pemasaran.
- Profil 4 GB mengutamakan kestabilan dan fitur perintah lokal. Model generatif diaktifkan hanya jika lolos pemeriksaan dan benchmark.
- Uji pada perangkat nyata lintas chipset/versi Android, termasuk kondisi aplikasi lain berjalan dan suhu tinggi.
- Publikasikan daftar perangkat yang benar-benar diuji; jangan mengklaim semua ponsel 4 GB kompatibel untuk generasi teks.

## Batas audit ini

Audit ini membaca workflow sumber dan potongan implementasi terkait. Belum ada perubahan kode AI, belum ada runtime/model yang dipilih, belum ada benchmark perangkat, dan belum ada bukti bahwa LLM offline sudah berjalan. Build yang berhasil saja tidak membuktikan kompatibilitas RAM 4 GB atau kualitas model.
