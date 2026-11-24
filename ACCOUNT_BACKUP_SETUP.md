# Akun dan Pemulihan M Asisten

## Tujuan
- Satu akun M Asisten hanya memiliki satu perangkat aktif pada satu waktu.
- Akun menggunakan email dan kata sandi melalui Firebase Authentication.
- Perangkat baru dapat mengambil alih akun setelah pemilik masuk dan memverifikasi email.
- Data aplikasi dicadangkan ke Cloud Firestore dan dipulihkan pada perangkat baru.
- Perangkat lama kehilangan hak baca/tulis backup setelah perangkat aktif berpindah.

## Identitas aplikasi
- Android applicationId: `com.m.launchervoice`
- Firebase products: Authentication (Email/Password), Cloud Firestore.
- Firebase Android SDK harus memakai Firebase BoM; jangan menyimpan kata sandi sendiri.

## Menyiapkan Firebase
1. Buat project Firebase milik pengelola M Asisten di https://console.firebase.google.com/.
2. Tambahkan aplikasi Android dengan package name persis `com.m.launchervoice`.
3. Unduh `google-services.json` dan unggah ke direktori utama repository dengan nama persis `google-services.json`. Workflow CI perlu menyalinnya ke `app/google-services.json` sebelum build.
4. Authentication → Sign-in method → aktifkan Email/Password.
5. Authentication → Settings → pastikan verifikasi email diwajibkan oleh alur aplikasi.
6. Buat Cloud Firestore dalam production mode.
7. Pasang aturan dari file `firestore.rules` melalui tab Rules.
8. Jangan mengaktifkan Firestore test mode untuk aplikasi yang digunakan pengguna.

## Alur satu perangkat
1. Pendaftaran membuat akun Firebase Auth. Aplikasi mengirim email verifikasi.
2. Setelah email diverifikasi, aplikasi membuat profil Firestore dengan `activeDeviceId`.
3. Setiap instalasi membuat ID perangkat acak yang disimpan lokal; bukan IMEI atau nomor seri perangkat.
4. Perangkat aktif mendengarkan dokumen profil. Jika `activeDeviceId` berubah, perangkat lama harus mengunci fitur akun dan menghentikan sinkronisasi.
5. Saat pindah HP, pengguna masuk di HP baru, memverifikasi email, lalu memilih **Pindahkan akun ke HP ini**.
6. HP baru mengganti `activeDeviceId`, kemudian membaca backup terakhir. Aturan Firestore otomatis menolak akses backup dari HP lama.

## Bentuk data backup
Simpan hanya data aplikasi yang memang diperlukan, misalnya nama profil dan preferensi M Asisten. Jangan pernah mencadangkan kata sandi, token autentikasi, rekaman mikrofon mentah, atau data sensitif yang tidak diperlukan. Data VIP+ harus menjadi data terverifikasi dan tidak boleh dapat diubah melalui backup pengguna.

## Penting sebelum rilis
- Integrasi Android belum aktif sampai Firebase project dibuat, `google-services.json` ditambahkan, dan SDK dihubungkan ke aplikasi.
- Uji registrasi, verifikasi email, reset kata sandi, login, perpindahan perangkat, pemulihan backup, serta penolakan perangkat lama.
- Aktifkan Firebase App Check sebelum rilis publik dan batasi API key Google untuk aplikasi Android ini.
- Buat kebijakan privasi dan jelaskan data yang dicadangkan serta cara menghapus akun/data.
