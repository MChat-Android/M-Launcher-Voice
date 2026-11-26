# Audit & Roadmap AI Offline M Asisten

Tanggal audit: 26 November 2026  
Repository: MChat-Android/M-Launcher-Voice  
Branch: main  
Commit yang diaudit: e24a72863111c3c64ec7af60a860ce3c96ea5a1a

## Keputusan produk

- Target perangkat: Android kelas menengah ke atas.
- AI percakapan: sepenuhnya on-device; tidak mengirim prompt ke API AI/cloud.
- Fungsi dasar asisten: tetap berjalan lokal.
- Tidak ada iklan sebagai sumber pendapatan utama.
- Firebase boleh dipertahankan untuk akun, lisensi, administrasi VIP+, dan sinkronisasi opsional. Koneksi internet untuk layanan tersebut tidak boleh menjadi syarat menjalankan mesin AI lokal setelah hak akses tersimpan secara aman.

## Temuan audit kode saat ini

1. Sumber Android tidak disimpan sebagai proyek Android biasa di root repository. Workflow `.github/workflows/blank.yml` menghasilkan `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`, manifest, dan source Kotlin melalui heredoc saat build.
2. Aplikasi sudah memakai `SpeechRecognizer.createOnDeviceSpeechRecognizer()` dan menolak fallback ke layanan speech cloud. Kode meminta paket bahasa perangkat dan dapat memicu pengunduhan model bahasa Android. Karena itu, pengenalan suara dapat offline setelah paket bahasa tersedia, tetapi ketersediaan Bahasa Indonesia bergantung pada implementasi speech service dan paket di masing-masing perangkat.
3. Aplikasi menggunakan Android `TextToSpeech`. Ini tidak otomatis menjamin suara offline: voice/language data harus tersedia dan terpasang di perangkat. Perlu pemeriksaan ketersediaan voice offline dan fallback yang jelas.
4. Logika perintah saat ini berupa pencocokan/perintah Kotlin melalui `executeCommand()`. Audit tidak menemukan runtime LLM lokal, model AI lokal, tokenizer, atau integrasi LiteRT-LM/llama.cpp. Dengan demikian, AI percakapan offline belum diimplementasikan.
5. Firebase Authentication dan Cloud Firestore digunakan untuk Google sign-in, profil, status VIP+, permintaan pembayaran, serta validasi akses aplikasi/widget. Sejumlah pemeriksaan VIP secara eksplisit menggunakan `Source.SERVER` dan `getIdToken(true)`; akibatnya, validasi VIP+ dan pemulihan mode VIP+ saat ini membutuhkan internet.
6. Workflow mengaktifkan Firebase hanya jika secret `GOOGLE_SERVICES_JSON_BASE64` tersedia. Firebase bukan mesin AI, tetapi masih menjadi ketergantungan akun/keanggotaan.
7. Dokumen integrasi pembayaran menyatakan belum ada payment gateway otomatis yang dipilih atau diaktifkan. Alur yang ada adalah permintaan pembayaran dan pemeriksaan admin. Harga yang tertulis di Firestore Rules saat audit: mingguan Rp15.000, bulanan Rp50.000, tahunan Rp250.000, Lifetime Rp500.000. Harga ini perlu keputusan bisnis terpisah sebelum diubah.
8. Belum ditemukan URL endpoint AI atau API key AI di source workflow yang diaudit. Ini bukan bukti bahwa seluruh komponen aplikasi bebas jaringan; Firebase tetap digunakan untuk fungsi akun dan lisensi.

## Arsitektur target

### A. Local command engine
Jalankan perintah perangkat yang deterministik tanpa LLM: buka aplikasi, alarm/pengingat yang diizinkan Android, senter, pengaturan, volume/brightness yang diizinkan, dan tindakan sistem lain. Validasi izin dan konfirmasi untuk tindakan sensitif.

### B. Offline speech input
- Gunakan speech recognizer on-device saja.
- Uji Android API 31+ dan daftar perangkat sasaran.
- Sediakan pemeriksaan bahasa Indonesia yang terpasang, status unduh, dan pesan jika tidak tersedia.
- Jangan diam-diam fallback ke cloud.
- Pertimbangkan engine ASR lokal yang dapat dibundel bila speech provider Android tidak konsisten, setelah uji ukuran APK, lisensi, performa, dan kualitas Bahasa Indonesia.

### C. Offline speech output
- Periksa apakah voice Bahasa Indonesia tersedia secara lokal.
- Tampilkan petunjuk instalasi paket suara jika belum tersedia.
- Jangan mengklaim TTS offline sebelum diuji dalam mode pesawat.

### D. On-device LLM
- Evaluasi runtime Android on-device yang aktif dipelihara (misalnya LiteRT-LM) dan alternatif yang sesuai.
- Pilih model yang lisensinya mengizinkan penggunaan komersial serta distribusi melalui aplikasi.
- Mulai dengan model kecil terkuantisasi; unduh model setelah persetujuan pengguna melalui Wi-Fi, verifikasi checksum dan tanda tangan, simpan di storage privat, serta dukung hapus/ganti model.
- Model harus bisa berjalan tanpa koneksi setelah pemasangan.
- Tetapkan batas RAM, ruang disk, suhu, baterai, waktu respons, dan panjang konteks berdasarkan pengujian nyata.
- Model tidak diberi akses bebas menjalankan perintah. Ia hanya mengeluarkan intent terstruktur dari daftar tool yang diizinkan; aplikasi memvalidasi parameter dan meminta konfirmasi jika perlu.

### E. Membership offline yang aman
- Jangan mempercayai flag VIP lokal yang dapat diedit pengguna.
- Saat online, backend menerbitkan entitlement bertanda tangan berisi UID, plan, fitur, waktu terbit, expiry, dan versi skema.
- Aplikasi memverifikasi tanda tangan dengan public key yang ditanam di APK, lalu menyimpan entitlement secara lokal.
- Tetapkan kebijakan grace/offline: akses offline sampai expiry yang ditandatangani; sinkronisasi ulang saat online. Untuk Lifetime, entitlement tidak kedaluwarsa, tetapi tetap dapat dicabut hanya melalui kebijakan yang diumumkan dan mekanisme revocation yang proporsional.
- Jangan menyimpan private signing key atau kredensial Admin SDK di APK.
- Hindari mengandalkan jam perangkat saja untuk keputusan keamanan; rancang mitigasi rollback jam dan perangkat yang di-root secara proporsional.
- Fungsi AI offline harus tetap bisa digunakan tanpa login bila termasuk fitur Gratis. Pemeriksaan server tidak boleh memblokir fitur gratis lokal.

## Tahapan implementasi dan kriteria lulus

1. **Pemetaan & baseline (dokumen ini):** inventaris dependensi, alur suara, login, VIP, dan workflow. Jangan ubah logika produksi.
2. **Pemisahan source dari workflow:** pindahkan source Kotlin/Gradle ke file proyek normal secara bertahap; pastikan hasil APK dan identitas aplikasi tidak berubah. Pertahankan workflow build yang dapat direproduksi.
3. **Perkuat voice offline:** uji SpeechRecognizer dan TTS Bahasa Indonesia pada beberapa perangkat target, termasuk mode pesawat setelah model bahasa/suara terpasang.
4. **Proof of concept LLM lokal:** buat branch eksperimen terpisah, uji runtime dan model berlisensi komersial; ukur RAM, ukuran unduhan, latency, suhu, konsumsi baterai, dan kualitas Bahasa Indonesia.
5. **Tool routing aman:** sambungkan LLM ke daftar perintah lokal yang diizinkan, bukan eksekusi teks bebas.
6. **Entitlement offline:** implementasikan lisensi bertanda tangan dan grace policy; uji akun gratis, VIP aktif, kedaluwarsa, Lifetime, logout, ganti perangkat, jam perangkat berubah, dan tanpa internet.
7. **Rilis bertahap:** internal test, closed test, kompatibilitas perangkat, uji mode pesawat, lalu rilis publik setelah seluruh kriteria lulus.

## Model biaya bisnis

- Biaya inference AI cloud: target Rp0 karena tidak ada panggilan AI cloud.
- Biaya server GPU: target Rp0 karena inferensi di perangkat pengguna.
- Biaya yang tetap ada: Firebase/Auth/Firestore untuk akun dan lisensi, hosting backend kecil, distribusi/pembaruan model, biaya pembayaran, dukungan pelanggan, keamanan, dan pengembangan.
- Model AI yang diunduh pengguna dapat mengurangi biaya bandwidth pengembang jika didistribusikan dengan hosting/CDN yang tepat, tetapi unduhan besar tetap perlu dihitung.
- Jangan tetapkan laba bersih final sebelum mengukur biaya backend, unduhan model, rasio konversi berbayar, biaya payment gateway, refund, pajak, dan jam pemeliharaan.

## Batasan audit

Audit ini membaca workflow, aturan Firestore, dan dokumen rencana yang ada di branch main. Karena source Android dibuat secara dinamis di workflow, semua perubahan implementasi berikutnya harus dilakukan dengan hati-hati pada heredoc terkait atau setelah source dipisahkan ke struktur proyek normal. Audit statis belum menggantikan uji APK pada perangkat fisik.


## Persyaratan tambahan: dukungan HP RAM 4 GB

Keputusan produk: M Asisten tetap harus dapat digunakan pada HP Android dengan RAM fisik 4 GB. RAM 4 GB tidak boleh otomatis membuat aplikasi menolak berjalan atau menutup seluruh fungsi asisten.

### Mode kompatibilitas berdasarkan kemampuan perangkat

1. **Mode 4 GB (kompatibilitas utama)**
   - Fungsi perintah Android lokal, antarmuka, pengaturan, dan TTS/ASR lokal yang tersedia harus tetap dapat digunakan.
   - AI generatif menggunakan model sangat kecil dan terkuantisasi (mulai uji kelas sekitar 0,5B–1B parameter, 4-bit; ukuran final ditentukan benchmark, bukan asumsi).
   - Batasi panjang konteks dan jumlah token keluaran; gunakan streaming; jangan memuat model besar di latar belakang.
   - Model AI boleh menjadi unduhan opsional. Jika tidak kompatibel atau memori sedang rendah, aplikasi otomatis beralih ke mode perintah lokal dan menjelaskan bahwa jawaban AI lanjutan tidak tersedia pada kondisi tersebut.
   - Jangan mengklaim semua perangkat RAM 4 GB pasti mampu menjalankan LLM dengan lancar. Android, chipset, versi OS, memori tersedia, dan thermal throttling sangat memengaruhi hasil.

2. **Mode 6–8 GB**
   - Uji model lokal kelas kecil-menengah yang lebih mampu, dengan batas memori dan konteks berdasarkan hasil pengukuran.

3. **Mode 8 GB ke atas**
   - Uji model yang lebih besar jika kualitas, lisensi, konsumsi baterai, suhu, dan stabilitas memenuhi target.

### Kebijakan pemilihan model dan memori

- Deteksi RAM total dan memori yang tersedia saat runtime; jangan memakai RAM total sebagai satu-satunya indikator.
- Gunakan Android memory callbacks/trim handling; hentikan atau lepaskan model saat aplikasi masuk background atau sistem meminta memori.
- Model 4 GB harus diuji dengan kondisi realistis: perangkat baru boot, aplikasi lain berjalan, panggilan masuk, pergantian aplikasi, dan sesi penggunaan berulang.
- Ukur peak RSS/PSS, waktu token pertama, token/detik, crash/OOM, suhu, baterai, dan ruang penyimpanan.
- Sediakan fallback deterministik tanpa LLM untuk perintah inti. Hindari memuat dua model berat sekaligus (ASR dan LLM); gunakan pipeline bergantian atau komponen ringan.
- Jika pengenalan suara offline sistem tidak tersedia, jangan diam-diam mengirim audio ke cloud. Beri pilihan mengetik atau instruksi pemasangan paket bahasa; evaluasi ASR lokal ringan terpisah.

### Kriteria penerimaan minimum RAM 4 GB

- Instalasi dan pembukaan aplikasi berhasil pada perangkat uji RAM 4 GB yang ditetapkan.
- Perintah inti lokal berjalan dalam mode pesawat.
- Tidak ada permintaan jaringan untuk prompt, transkrip, atau inferensi AI.
- Jika model kecil tidak muat, aplikasi tetap dapat menjalankan fungsi asisten non-generatif tanpa crash.
- Tidak ada klaim “AI generatif lancar di semua HP 4 GB” sebelum pengujian pada beberapa perangkat nyata.
- Tetapkan daftar perangkat/chipset yang lulus; tampilkan mode kompatibilitas dan rekomendasi model berdasarkan hasil uji.

### Dampak bisnis RAM 4 GB

- Mendukung RAM 4 GB memperluas pasar dan jumlah calon pengguna, tetapi kualitas AI generatif di kelas ini harus diposisikan sebagai versi ringan.
- Jangan membebankan biaya inference cloud sebagai fallback otomatis; itu akan melanggar keputusan offline-only dan mengubah struktur biaya.
- Model yang lebih besar untuk perangkat kuat dapat menjadi peningkatan opsional, bukan syarat menggunakan fungsi utama.
- Anggaran bisnis tetap perlu memasukkan pengujian perangkat kelas bawah/menengah, optimasi memori, dan dukungan kompatibilitas.
