package com.m.launchervoice

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

private val Navy = Color(0xFF07111F)
private val Panel = Color(0xFF111F32)
private val Cyan = Color(0xFF35D6E8)
private val Blue = Color(0xFF397BFF)
private val Muted = Color(0xFF9DB2C9)

class MainActivity : ComponentActivity() {

    private var message by mutableStateOf("Widget mikrofon siap diaktifkan")
    private var googleAccountName by mutableStateOf("")
    private var googleAccountEmail by mutableStateOf("")
    private var vipMembershipStatus by mutableStateOf("BELUM AKTIF")
    private var isAdminAccount by mutableStateOf(false)

    private val voiceMessageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            message = intent?.getStringExtra("message").orEmpty().ifBlank { message }
        }
    }

    private val googleSignInLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
                val token = account.idToken
                if (token.isNullOrBlank()) {
                    message = "Token Google tidak tersedia. Periksa konfigurasi OAuth Firebase."
                } else {
                    FirebaseAuth.getInstance().signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                        .addOnSuccessListener { authResult ->
                            val user = authResult.user
                            if (user == null) {
                                message = "Login Google gagal membaca akun."
                            } else {
                                refreshAdminClaim(user)
                                googleAccountName = user.displayName ?: account.displayName ?: "Pengguna M Asisten"
                                googleAccountEmail = user.email ?: account.email.orEmpty()
                                val memberRef = FirebaseFirestore.getInstance().collection("vip_members").document(user.uid)
                                memberRef.get()
                                    .addOnSuccessListener { existing ->
                                        val profileData = hashMapOf<String, Any>(
                                            "uid" to user.uid, "email" to googleAccountEmail,
                                            "displayName" to googleAccountName, "provider" to "google",
                                            "lastLoginAt" to FieldValue.serverTimestamp(),
                                            "updatedAt" to FieldValue.serverTimestamp()
                                        )
                                        if (existing.exists()) {
                                            memberRef.update(profileData as Map<String, Any>)
                                                .addOnSuccessListener {
                                                    message = "Akun Google berhasil terhubung."
                                                    loadVipMembership(user.uid)
                                                }
                                                .addOnFailureListener { err -> message = "Pembaruan akun gagal: " + err.localizedMessage }
                                        } else {
                                            // Status keanggotaan hanya boleh ditetapkan oleh server.
                                            // Dokumen awal hanya berisi profil Google yang diizinkan Firestore Rules.
                                            memberRef.set(profileData)
                                                .addOnSuccessListener {
                                                    message = "Akun Google berhasil terhubung."
                                                    loadVipMembership(user.uid)
                                                }
                                                .addOnFailureListener { err -> message = "Pembuatan profil VIP gagal: " + err.localizedMessage }
                                        }
                                    }
                                    .addOnFailureListener { err -> message = "Sinkronisasi VIP gagal: " + err.localizedMessage }
                            }
                        }
                        .addOnFailureListener { err -> message = "Login Firebase gagal: " + err.localizedMessage }
                }
            } catch (err: ApiException) {
                message = "Login Google gagal (kode " + err.statusCode + ")."
            } catch (err: Exception) {
                message = "Gagal memproses akun Google: " + err.localizedMessage
            }
        } else {
            message = "Login Google dibatalkan."
        }
    }

    private fun startGoogleSignIn() {
        val id = resources.getIdentifier("default_web_client_id", "string", packageName)
        if (id == 0) {
            message = "OAuth Google belum dikonfigurasi di Firebase."
            return
        }
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail().requestIdToken(getString(id)).build()
        googleSignInLauncher.launch(GoogleSignIn.getClient(this, options).signInIntent)
    }

    private fun refreshAdminClaim(user: com.google.firebase.auth.FirebaseUser) {
        user.getIdToken(true)
            .addOnSuccessListener { token ->
                isAdminAccount = token.claims["admin"] == true
                if (isAdminAccount) vipMembershipStatus = "VIP+ AKTIF • ADMIN"
            }
            .addOnFailureListener { isAdminAccount = false }
    }

    private fun loadVipMembership(uid: String) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != uid) {
            vipMembershipStatus = "BELUM AKTIF"
            isAdminAccount = false
            return
        }

        user.getIdToken(true)
            .addOnSuccessListener { token ->
                val admin = token.claims["admin"] == true
                isAdminAccount = admin
                if (admin) {
                    // Hak VIP+ admin berasal dari Custom Claims, tidak dari dokumen pembayaran.
                    vipMembershipStatus = "VIP+ AKTIF • ADMIN"
                } else {
                    loadRegularVipMembership(uid)
                }
            }
            .addOnFailureListener {
                isAdminAccount = false
                loadRegularVipMembership(uid)
            }
    }

    private fun loadRegularVipMembership(uid: String) {
        FirebaseFirestore.getInstance().collection("vip_members").document(uid)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { snap ->
                val status = snap.getString("membershipStatus") ?: "FREE"
                val expiresAt = snap.getTimestamp("expiresAt")?.toDate()?.time
                val active = status == "ACTIVE" && (expiresAt == null || expiresAt > System.currentTimeMillis())
                vipMembershipStatus = if (active) "VIP+ AKTIF" else "BELUM AKTIF"
            }
            .addOnFailureListener { vipMembershipStatus = "STATUS BELUM TERSINKRON" }
    }

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { grants ->
            if (grants[Manifest.permission.RECORD_AUDIO] == false ||
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                message = "Izin mikrofon diperlukan untuk menjalankan widget Standar maupun VIP+."
            } else {
                continueSetup()
            }
        }

    private val overlayLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            if (Settings.canDrawOverlays(this)) {
                startFloatingService()
            } else {
                message = "Izin tampil di atas aplikasi belum diberikan"
            }
        }

    override fun onStart() {
        super.onStart()
        val filter = android.content.IntentFilter("com.m.launchervoice.VOICE_MESSAGE")
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                voiceMessageReceiver,
                filter,
                android.content.Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(voiceMessageReceiver, filter)
        }
        message = getSharedPreferences("m_voice_status", MODE_PRIVATE)
            .getString("latest_message", message) ?: message
    }

    override fun onStop() {
        try {
            unregisterReceiver(voiceMessageReceiver)
        } catch (_: Exception) {}
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        FirebaseAuth.getInstance().currentUser?.let { user ->
            googleAccountName = user.displayName ?: "Pengguna M Asisten"
            googleAccountEmail = user.email.orEmpty()
            refreshAdminClaim(user)
            loadVipMembership(user.uid)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    background = Navy,
                    surface = Panel,
                    onPrimary = Navy,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                MLauncherHome(
                    message = message,
                    onActivate = { beginSetup() },
                    googleAccountName = googleAccountName,
                    googleAccountEmail = googleAccountEmail,
                    vipMembershipStatus = vipMembershipStatus,
                    isAdminAccount = isAdminAccount,
                    onGoogleSignIn = { startGoogleSignIn() },
                    onLogout = { signOutAccount() }
                )
            }
        }
    }

    private fun signOutAccount() {
        // Keluar dari Firebase dan sesi Google agar pengguna bisa memilih akun kedua.
        FirebaseAuth.getInstance().signOut()
        val webClientId = resources.getIdentifier("default_web_client_id", "string", packageName)
        val optionsBuilder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
        if (webClientId != 0) optionsBuilder.requestIdToken(getString(webClientId))
        GoogleSignIn.getClient(this, optionsBuilder.build()).signOut()
        googleAccountName = ""
        googleAccountEmail = ""
        vipMembershipStatus = "BELUM AKTIF"
        isAdminAccount = false
        message = "Akun berhasil keluar. Silakan masuk dengan akun lain."
    }

    private fun beginSetup() {
        // Kedua paket memakai mikrofon setelah widget diketuk.
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            continueSetup()
        }
    }

    private fun continueSetup() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            message = "Izinkan akses mikrofon untuk mengaktifkan widget."
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            message = "Izinkan M Asisten tampil di atas aplikasi lain"
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayLauncher.launch(intent)
        } else {
            startFloatingService()
        }
    }

    private fun startFloatingService() {
        fun launchWidget(mode: String) {
            try {
                val intent = Intent(this, FloatingVoiceService::class.java)
                    .putExtra(FloatingVoiceService.EXTRA_MODE, mode)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    // Layanan mikrofon harus dimulai sebagai foreground service pada kedua paket.
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
                message = if (mode == FloatingVoiceService.MODE_VIP) {
                    "Widget VIP+ aktif. Ketuk untuk sapaan dan perintah suara."
                } else {
                    "Widget Standar aktif. Ketuk lalu ucapkan perintah untuk aplikasi bawaan."
                }
            } catch (e: Exception) {
                message = "Tidak dapat mengaktifkan widget: ${e.message}"
            }
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            launchWidget(FloatingVoiceService.MODE_STANDARD)
            return
        }

        fun activateVerifiedVip(isVip: Boolean) {
            if (isVip &&
                checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
            ) {
                message = "VIP aktif. Izinkan mikrofon untuk memakai asisten suara."
                permissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                return
            }
            launchWidget(
                if (isVip) FloatingVoiceService.MODE_VIP
                else FloatingVoiceService.MODE_STANDARD
            )
        }

        // Periksa Custom Claims terbaru agar akun admin otomatis mendapat VIP+.
        user.getIdToken(true)
            .addOnSuccessListener { token ->
                val admin = token.claims["admin"] == true
                isAdminAccount = admin
                if (admin) {
                    vipMembershipStatus = "VIP+ AKTIF • ADMIN"
                    activateVerifiedVip(true)
                } else {
                    FirebaseFirestore.getInstance()
                        .collection("vip_members")
                        .document(user.uid)
                        .get(com.google.firebase.firestore.Source.SERVER)
                        .addOnSuccessListener { member ->
                            val expiresAt = member.getTimestamp("expiresAt")?.toDate()?.time
                            val vipActive = member.getString("membershipStatus") == "ACTIVE" &&
                                (expiresAt == null || expiresAt > System.currentTimeMillis())
                            activateVerifiedVip(vipActive)
                        }
                        .addOnFailureListener { activateVerifiedVip(false) }
                }
            }
            .addOnFailureListener {
                isAdminAccount = false
                // Klaim admin yang gagal diverifikasi tidak pernah dianggap aktif.
                FirebaseFirestore.getInstance()
                    .collection("vip_members")
                    .document(user.uid)
                    .get(com.google.firebase.firestore.Source.SERVER)
                    .addOnSuccessListener { member ->
                        val expiresAt = member.getTimestamp("expiresAt")?.toDate()?.time
                        val vipActive = member.getString("membershipStatus") == "ACTIVE" &&
                            (expiresAt == null || expiresAt > System.currentTimeMillis())
                        activateVerifiedVip(vipActive)
                    }
                    .addOnFailureListener { activateVerifiedVip(false) }
            }
    }
}

@Composable
private fun MLauncherHome(
    message: String,
    onActivate: () -> Unit,
    googleAccountName: String,
    googleAccountEmail: String,
    vipMembershipStatus: String,
    isAdminAccount: Boolean,
    onGoogleSignIn: () -> Unit,
    onLogout: () -> Unit
) {
    var showVipPlus by remember { mutableStateOf(false) }
    var selectedVipPlan by remember { mutableStateOf("VIP+ Bulanan (30 hari)") }
    var payerName by remember { mutableStateOf("") }
    var transferReference by remember { mutableStateOf("") }
    var paymentSubmitting by remember { mutableStateOf(false) }
    var paymentSubmitMessage by remember { mutableStateOf("") }
    var paymentHistory by remember { mutableStateOf(listOf<String>()) }
    var historyLoading by remember { mutableStateOf(false) }
    var adminItems by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var adminBusy by remember { mutableStateOf(false) }
    var adminMessage by remember { mutableStateOf("") }
    var simulationMessage by remember { mutableStateOf("") }
    val Navy = Color(0xFF07111F)
    val Panel = Color(0xFF111F32)
    val Cyan = Color(0xFF35D6E8)
    val Muted = Color(0xFF9DB2C9)
    val isVipPlus = vipMembershipStatus.startsWith("VIP+ AKTIF")
    val premiumGold = Color(0xFFFFD778)
    val premiumBorder = Color(0xFFB98A3B)
    val premiumPanel = Color(0xFF151D30)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isVipPlus) {
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFF050914),
                            Color(0xFF10182A),
                            Color(0xFF1B1B2B),
                            Color(0xFF080D18)
                        )
                    )
                } else {
                    Brush.verticalGradient(listOf(Navy, Panel, Navy))
                }
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        val compactWidth = maxWidth < 360.dp
        val pageHorizontalPadding = if (compactWidth) 14.dp else 22.dp
        val pageVerticalPadding = if (maxHeight < 650.dp) 16.dp else 28.dp
        val heroImageSize = (maxWidth * 0.46f).coerceIn(128.dp, 176.dp)
        val contentWidth = maxWidth.coerceAtMost(600.dp)

        Column(
            modifier = Modifier
                .width(contentWidth)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = pageHorizontalPadding, vertical = pageVerticalPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = R.mipmap.ic_launcher),
                    contentDescription = "Ikon M Asisten",
                    modifier = Modifier.size(if (compactWidth) 44.dp else 52.dp).clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.width(13.dp))
                Column {
                    Text("M ASISTEN", color = Color.White, fontSize = if (compactWidth) 15.sp else 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text("DIBUAT DI INDONESIA", color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.1.sp)
                }
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(34.dp))

            Box(
                modifier = Modifier.size(heroImageSize),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.launcher_home),
                    contentDescription = "Ikon resmi M Asisten",
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(38.dp)),
                    contentScale = ContentScale.Fit
                )

                // Lencana VIP+ menempel pada garis bingkai kanan-atas logo.
                if (isVipPlus) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 9.dp, y = (-8).dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    listOf(
                                        Color(0xFFFFE9A6),
                                        Color(0xFFFFC94A),
                                        Color(0xFFB87916)
                                    )
                                ),
                                shape = RoundedCornerShape(11.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "VIP+",
                            color = Color(0xFF382306),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 1.2.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(25.dp))
            Text(
                "Semua jadi lebih mudah.",
                color = if (isVipPlus) Color(0xFFFFF1D0) else Color.White,
                fontSize = if (compactWidth) 21.sp else 25.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Kendalikan ponselmu cukup dengan suara.",
                color = if (isVipPlus) Color(0xFFC7B995) else Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(26.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVipPlus) premiumPanel else Panel
                ),
                border = if (isVipPlus) androidx.compose.foundation.BorderStroke(1.dp, premiumBorder) else null
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("MULAI GUNAKAN ASISTEN", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.3.sp)
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = onActivate,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(17.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVipPlus) premiumGold else Cyan,
                            contentColor = Navy
                        )
                    ) {
                        Text("🎙   AKTIFKAN WIDGET", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(message, color = if (message.contains("diaktifkan")) Cyan else Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showVipPlus = true },
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVipPlus) premiumPanel else Color(0xFF123344)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    if (isVipPlus) 1.5.dp else 1.dp,
                    if (isVipPlus) premiumBorder else Cyan
                )
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("TINGKATKAN KE VIP+", color = if (isVipPlus) premiumGold else Cyan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            Spacer(Modifier.height(5.dp))
                            Text("Buka semua aplikasi dengan perintah suara", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("Mulai Rp15.000 / 7 hari", color = Muted, fontSize = 11.sp)
                        }
                        Text("VIP+", color = if (isVipPlus) premiumGold else Cyan, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { showVipPlus = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVipPlus) premiumGold else Cyan,
                            contentColor = Navy
                        ),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text("LIHAT PAKET VIP+", fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVipPlus) premiumPanel else Color(0xFF102238)
                ),
                border = if (isVipPlus) androidx.compose.foundation.BorderStroke(1.dp, premiumBorder)
                    else androidx.compose.foundation.BorderStroke(1.dp, Cyan)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("APLIKASI PAKET STANDAR", color = if (isVipPlus) premiumGold else Cyan, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Perintah suara untuk fitur dan aplikasi bawaan ponsel:", color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    listOf(
                        "Telepon dan kontak",
                        "Pesan dan SMS",
                        "Kamera dan perekam video",
                        "Galeri dan foto",
                        "Peramban internet",
                        "Peta dan navigasi",
                        "Kalender dan alarm",
                        "Senter dan lampu kilat",
                        "Wi-Fi, Bluetooth, dan hotspot",
                        "Volume, kecerahan, dan pengaturan ponsel"
                    ).forEach { app ->
                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                            Text("•", color = if (isVipPlus) premiumGold else Cyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                            Text(app, color = Color.White, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Paket Standar berfokus pada aplikasi bawaan dan kendali dasar ponsel.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVipPlus) Color(0xFF1C2030) else Color(0xFF123344)
                ),
                border = androidx.compose.foundation.BorderStroke(
                    if (isVipPlus) 1.5.dp else 1.dp,
                    if (isVipPlus) premiumBorder else Cyan
                )
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("APLIKASI PAKET VIP+", color = if (isVipPlus) premiumGold else Cyan, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text("Dapat membuka semua aplikasi terpasang yang memiliki ikon aplikasi dan dapat dijalankan Android.", color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("Contoh aplikasi:", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(5.dp))
                    Text("WhatsApp, Instagram, Facebook, YouTube, Chrome, Google Maps, Gmail, TikTok, Telegram, Spotify, dan aplikasi lain yang tersedia di ponsel.", color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("Daftar sebenarnya mengikuti aplikasi yang terpasang pada masing-masing ponsel. Fitur di dalam aplikasi tetap dikendalikan oleh penggunanya.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }

            Spacer(Modifier.height(25.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("KEBIJAKAN & PRIVASI", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text("Informasi aplikasi", color = Muted, fontSize = 10.sp)
            }
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isVipPlus) premiumPanel else Color(0xFF102238)
                ),
                border = if (isVipPlus) androidx.compose.foundation.BorderStroke(1.dp, premiumBorder) else null
            ) {
                Column(modifier = Modifier.padding(17.dp)) {
                    Text("Privasi Anda penting", color = if (isVipPlus) premiumGold else Cyan, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "M Asisten menggunakan izin perangkat seperlunya untuk menjalankan perintah yang Anda pilih. Pemrosesan suara dirancang berlangsung di perangkat dan aplikasi tidak mengirim rekaman suara ke server M Asisten.",
                        color = Color.White, fontSize = 12.sp, lineHeight = 19.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("• Mikrofon", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Mikrofon aktif ketika Anda mengetuk widget atau memulai perintah suara. Aplikasi menggunakan pengenalan suara langsung di perangkat tanpa mengirim rekaman ke peladen M Asisten. Dukungan suara luring Bahasa Indonesia bergantung pada versi Android, layanan suara, dan paket bahasa yang tersedia; paket bahasa mungkin perlu diunduh satu kali.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("• Kamera", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Izin kamera digunakan untuk memeriksa dan mengendalikan lampu kilat sebagai senter. Aplikasi tidak mengambil atau mengunggah foto dan video melalui fitur senter.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("• Tampil di atas aplikasi lain", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Digunakan untuk menampilkan widget mikrofon mengambang. Anda dapat menonaktifkan izin ini kapan saja melalui Pengaturan Android.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("• Kendali dan data akun", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Widget mulai mendengarkan setelah Anda mengetuknya. Anda dapat menghentikan penggunaan dan mencabut izin mikrofon, kamera, notifikasi, atau tampilan di atas aplikasi melalui Pengaturan perangkat.",
                        color = Muted, fontSize = 11.sp, lineHeight = 17.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Nama profil dan pengaturan lokal disimpan di perangkat. Jika Anda masuk dengan Google atau mengajukan keanggotaan VIP+, aplikasi mengirim data akun, alamat surel, pilihan paket, nominal, nama pengirim, nomor referensi transfer jika diisi, serta status dan waktu permintaan ke Firebase untuk pemeriksaan keanggotaan dan pembayaran oleh admin. Aplikasi saat ini belum menyediakan unggahan bukti transfer. Aplikasi lain yang dibuka memiliki kebijakan privasi masing-masing. Jangan pernah membagikan PIN, OTP, atau kata sandi.",
                        color = Muted, fontSize = 10.sp, lineHeight = 16.sp
                    )
                }
            }

            Spacer(Modifier.height(25.dp))
            Text(
                "M Asisten",
                color = Cyan,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))
        }
        if (showVipPlus) {
            AlertDialog(
                onDismissRequest = { showVipPlus = false },
                containerColor = Panel,
                title = {
                    Text(
                        "KEANGGOTAAN VIP+",
                        color = Cyan,
                        fontWeight = FontWeight.ExtraBold
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .heightIn(max = maxHeight * 0.72f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        if (isAdminAccount) {
                            Card(Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2932)),
                                border = androidx.compose.foundation.BorderStroke(1.dp, Cyan)) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("PANEL ADMIN VIP+", color = Cyan, fontWeight = FontWeight.ExtraBold)
                                    Text("Cocokkan pembayaran dengan mutasi SeaBank secara manual sebelum verifikasi.", color = Muted, fontSize = 10.sp)
                                    Card(
                                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                                        colors = CardDefaults.cardColors(containerColor = Color(0xFF172B42)),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF547087))
                                    ) {
                                        Column(Modifier.padding(10.dp)) {
                                            Text("SIMULASI MASA AKTIF VIP+", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                            Text("Uji hitungan 4 paket secara lokal. Tidak membuat transaksi, tidak mengubah Firestore, dan tidak mengaktifkan akun.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
                                            OutlinedButton(
                                                onClick = {
                                                    val nowMillis = System.currentTimeMillis()
                                                    val formatter = java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id", "ID"))
                                                    val packages = listOf(
                                                        "VIP+ Mingguan • 7 hari" to 7,
                                                        "VIP+ Bulanan • 30 hari" to 30,
                                                        "VIP+ Tahunan • 365 hari" to 365,
                                                        "VIP+ Seumur Hidup" to -1
                                                    )
                                                    simulationMessage = buildString {
                                                        append("Waktu mulai simulasi: ")
                                                        append(formatter.format(java.util.Date(nowMillis)))
                                                        packages.forEach { (label, days) ->
                                                            append("\\n")
                                                            append(label)
                                                            append(": ")
                                                            if (days < 0) {
                                                                append("Tidak kedaluwarsa")
                                                            } else {
                                                                val expiryMillis = nowMillis + days.toLong() * 86400000L
                                                                append(formatter.format(java.util.Date(expiryMillis)))
                                                            }
                                                        }
                                                        append("\\n\\nSIMULASI SELESAI — data akun dan transaksi tidak diubah.")
                                                    }
                                                },
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("UJI HITUNG SEMUA PAKET", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                            if (simulationMessage.isNotBlank()) {
                                                Text(simulationMessage, color = Color.White, fontSize = 10.sp, lineHeight = 16.sp)
                                            }
                                        }
                                    }
                                    OutlinedButton(onClick = {
                                        if (!adminBusy) {
                                            adminBusy = true
                                            FirebaseFirestore.getInstance().collection("vip_payment_requests")
                                                .whereEqualTo("status", "PENDING_REVIEW").get()
                                                .addOnSuccessListener { snap ->
                                                    adminItems = snap.documents.map { d ->
                                                        val plan = d.getString("plan") ?: "Paket?"
                                                        val payer = d.getString("payerName") ?: "?"
                                                        val email = d.getString("email") ?: "?"
                                                        val amount = d.getLong("amountIdr") ?: 0L
                                                        val reference = d.getString("transferReference").orEmpty().ifBlank { "Tidak dicantumkan" }
                                                        val created = d.getTimestamp("createdAt")?.toDate()?.let {
                                                            java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale("id", "ID")).format(it)
                                                        } ?: "Waktu tidak tersedia"
                                                        d.id to "$plan • Rp$amount\nPengirim: $payer\nAkun: $email\nReferensi: $reference\nDikirim: $created"
                                                    }
                                                    adminBusy = false
                                                    adminMessage = if (adminItems.isEmpty()) "Tidak ada permintaan tertunda." else "Periksa mutasi bank sebelum memproses."
                                                }.addOnFailureListener { e -> adminBusy = false; adminMessage = "Gagal memuat: ${e.localizedMessage}" }
                                        }
                                    }, enabled = !adminBusy, modifier = Modifier.fillMaxWidth()) {
                                        Text(if (adminBusy) "MEMUAT..." else "MUAT PERMINTAAN VIP+")
                                    }
                                    Text(adminMessage, color = Muted, fontSize = 10.sp)
                                    adminItems.forEach { (id, info) ->
                                        Card(Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            colors = CardDefaults.cardColors(containerColor = Panel)) {
                                            Column(Modifier.padding(8.dp)) {
                                                Text(info, color = Color.White, fontSize = 10.sp)
                                                Text("ID: $id", color = Muted, fontSize = 8.sp)
                                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                    Button(onClick = {
                                                        val operator = FirebaseAuth.getInstance().currentUser
                                                        if (operator == null || adminBusy) { adminMessage = "Sesi admin tidak tersedia."; return@Button }
                                                        adminBusy = true
                                                        val db = FirebaseFirestore.getInstance()
                                                        val reqRef = db.collection("vip_payment_requests").document(id)
                                                        db.runTransaction { tx ->
                                                            val req = tx.get(reqRef)
                                                            check(req.exists() && req.getString("status") == "PENDING_REVIEW") { "Permintaan sudah diproses." }
                                                            val uid = req.getString("uid") ?: error("UID pelanggan tidak valid.")
                                                            val plan = req.getString("plan") ?: error("Paket tidak valid.")
                                                            val transferReference = req.getString("transferReference").orEmpty().trim()
                                                            check(!transferReference.equals("test", ignoreCase = true)) { "Referensi TEST tidak boleh diverifikasi sebagai pembayaran asli." }
                                                            val amount = req.getLong("amountIdr") ?: 0L
                                                            val days = when (plan) {
                                                                "VIP+ Mingguan (7 hari)" -> if (amount == 15000L) 7 else 0
                                                                "VIP+ Bulanan (30 hari)" -> if (amount == 50000L) 30 else 0
                                                                "VIP+ Tahunan (365 hari)" -> if (amount == 250000L) 365 else 0
                                                                "VIP+ Seumur Hidup" -> if (amount == 500000L) -1 else 0
                                                                else -> 0
                                                            }
                                                            check(days != 0) { "Paket dan nominal tidak cocok." }
                                                            val memberRef = db.collection("vip_members").document(uid)
                                                            check(tx.get(memberRef).exists()) { "Profil pelanggan belum ada." }
                                                            val now = com.google.firebase.Timestamp.now()
                                                            val expires = if (days < 0) null else com.google.firebase.Timestamp(java.util.Date(now.toDate().time + days.toLong() * 86400000L))
                                                            tx.update(memberRef, mapOf("membershipStatus" to "ACTIVE", "membershipPlan" to plan, "startedAt" to now, "expiresAt" to expires, "verifiedPaymentRequestId" to id, "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                                                            tx.update(reqRef, mapOf("status" to "VERIFIED", "adminUid" to operator.uid, "reviewedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(), "adminNote" to "Pembayaran diverifikasi oleh admin."))
                                                            null
                                                        }.addOnSuccessListener { adminBusy = false; adminItems = adminItems.filterNot { it.first == id }; adminMessage = "VIP+ berhasil diaktifkan." }
                                                            .addOnFailureListener { e -> adminBusy = false; adminMessage = "Gagal verifikasi: ${e.localizedMessage}" }
                                                    }, enabled = !adminBusy, modifier = Modifier.weight(1f)) { Text("VERIFIKASI", fontSize = 9.sp) }
                                                    OutlinedButton(onClick = {
                                                        val operator = FirebaseAuth.getInstance().currentUser
                                                        if (operator == null || adminBusy) { adminMessage = "Sesi admin tidak tersedia."; return@OutlinedButton }
                                                        adminBusy = true
                                                        val ref = FirebaseFirestore.getInstance().collection("vip_payment_requests").document(id)
                                                        FirebaseFirestore.getInstance().runTransaction { tx ->
                                                            val req = tx.get(ref)
                                                            check(req.exists() && req.getString("status") == "PENDING_REVIEW") { "Permintaan sudah diproses." }
                                                            tx.update(ref, mapOf("status" to "REJECTED", "adminUid" to operator.uid, "reviewedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(), "adminNote" to "Pembayaran ditolak oleh admin."))
                                                            null
                                                        }.addOnSuccessListener { adminBusy = false; adminItems = adminItems.filterNot { it.first == id }; adminMessage = "Permintaan ditolak." }
                                                            .addOnFailureListener { e -> adminBusy = false; adminMessage = "Gagal menolak: ${e.localizedMessage}" }
                                                    }, enabled = !adminBusy, modifier = Modifier.weight(1f)) { Text("TOLAK", fontSize = 9.sp) }
                                                }
                                                if (info.contains("Referensi: test", ignoreCase = true)) {
                                                    OutlinedButton(
                                                        onClick = {
                                                            val operator = FirebaseAuth.getInstance().currentUser
                                                            if (operator == null || adminBusy) { adminMessage = "Sesi admin tidak tersedia."; return@OutlinedButton }
                                                            adminBusy = true
                                                            val db = FirebaseFirestore.getInstance()
                                                            val reqRef = db.collection("vip_payment_requests").document(id)
                                                            db.runTransaction { tx ->
                                                                val req = tx.get(reqRef)
                                                                check(req.exists() && req.getString("status") == "PENDING_REVIEW") { "Permintaan uji sudah diproses." }
                                                                check(req.getString("transferReference").orEmpty().trim().equals("test", ignoreCase = true)) { "Tombol simulasi hanya untuk referensi TEST." }
                                                                val uid = req.getString("uid") ?: error("UID pelanggan tidak valid.")
                                                                val plan = req.getString("plan") ?: error("Paket tidak valid.")
                                                                val amount = req.getLong("amountIdr") ?: 0L
                                                                check(plan == "VIP+ Mingguan (7 hari)" && amount == 15000L) { "Simulasi ini khusus paket Mingguan Rp15.000." }
                                                                val memberRef = db.collection("vip_members").document(uid)
                                                                check(tx.get(memberRef).exists()) { "Profil pelanggan belum ada." }
                                                                val now = com.google.firebase.Timestamp.now()
                                                                val expires = com.google.firebase.Timestamp(java.util.Date(now.toDate().time + 7L * 86400000L))
                                                                tx.update(memberRef, mapOf("membershipStatus" to "ACTIVE", "membershipPlan" to "VIP+ Mingguan (7 hari)", "startedAt" to now, "expiresAt" to expires, "testMode" to true, "testPaymentRequestId" to id, "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()))
                                                                null
                                                            }.addOnSuccessListener {
                                                                adminBusy = false
                                                                adminMessage = "SIMULASI BERHASIL: VIP+ akun uji aktif 7 hari. Permintaan pembayaran tetap MENUNGGU VERIFIKASI."
                                                            }.addOnFailureListener { e ->
                                                                adminBusy = false
                                                                adminMessage = "Simulasi gagal: ${e.localizedMessage}"
                                                            }
                                                        },
                                                        enabled = !adminBusy,
                                                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                                                    ) { Text("SIMULASI AKTIFKAN VIP+ 7 HARI (BUKAN PEMBAYARAN)", fontSize = 9.sp) }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "Pengalaman lebih nyaman, dengan dukungan untuk pengembangan M Asisten.",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.height(14.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0D2932)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("STATUS KEANGGOTAAN", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.height(3.dp))
                                    Text(if (googleAccountEmail.isBlank()) "Hubungkan akun Google untuk memulai" else googleAccountName + " • " + vipMembershipStatus, color = Color.White, fontSize = 11.sp)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(vipMembershipStatus, color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (googleAccountEmail.isBlank()) {
                            Button(onClick = onGoogleSignIn, modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF202124))) {
                                Text("G   LANJUTKAN DENGAN GOOGLE", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Text("Akun: " + googleAccountEmail, color = Muted, fontSize = 10.sp)
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = onLogout,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF8A8A))
                            ) {
                                Text("KELUAR AKUN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        Spacer(Modifier.height(15.dp))
                        Text("KEUNTUNGAN VIP+", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        listOf(
                            "Latar layar beranda VIP+ dengan tampilan khusus",
                            "Sapaan dan panduan suara khusus VIP+ saat widget digunakan",
                            "Perintah suara untuk membuka aplikasi apa pun yang terpasang; penggunaan di dalam aplikasi tetap manual",
                            "Widget mengambang dengan pilihan desain ikon premium",
                            "Layanan berbasis kecerdasan buatan khusus VIP+"
                        ).forEach { benefit ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Text("✓", color = Cyan, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.width(8.dp))
                                Text(benefit, color = Color.White, fontSize = 12.sp, lineHeight = 18.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("PILIHAN PAKET", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        listOf(
                            "VIP+ Mingguan (7 hari)" to "Rp15.000",
                            "VIP+ Bulanan (30 hari)" to "Rp50.000",
                            "VIP+ Tahunan (365 hari)" to "Rp250.000",
                            "VIP+ Seumur Hidup" to "Rp500.000"
                        ).forEach { (plan, price) ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedVipPlan = plan },
                                colors = CardDefaults.cardColors(
                                    containerColor = if (selectedVipPlan == plan) Color(0xFF164354) else Color(0xFF172B42)
                                ),
                                border = if (selectedVipPlan == plan) androidx.compose.foundation.BorderStroke(1.dp, Cyan) else null,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(plan, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.weight(1f))
                                    Text(price, color = Cyan, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Pilihan saat ini: $selectedVipPlan", color = Color.White, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("PEMBAYARAN TRANSFER BANK", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(7.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF172B42)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("SeaBank", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(5.dp))
                                Text("Nomor rekening", color = Muted, fontSize = 10.sp)
                                Text("901023118338", color = Cyan, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                Spacer(Modifier.height(5.dp))
                                Text("Nama penerima: M Asisten", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text("Nama pemilik rekening terdaftar: Muhamad Soleh", color = Muted, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF172B42)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text("BUKTI PEMBAYARAN", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(4.dp))
                                Text("Siapkan bukti transfer yang jelas untuk dilampirkan saat mengirim konfirmasi kepada admin. Fitur lampiran langsung di aplikasi belum tersedia, jadi kirimkan bukti melalui saluran admin resmi.", color = Color.White, fontSize = 10.sp, lineHeight = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = payerName,
                            onValueChange = { payerName = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nama pengirim sesuai rekening") },
                            singleLine = true,
                            enabled = !paymentSubmitting,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan,
                                focusedLabelColor = Cyan,
                                unfocusedBorderColor = Muted,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(Modifier.height(7.dp))
                        OutlinedTextField(
                            value = transferReference,
                            onValueChange = { transferReference = it },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Nomor referensi transfer (opsional)") },
                            singleLine = true,
                            enabled = !paymentSubmitting,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Cyan,
                                focusedLabelColor = Cyan,
                                unfocusedBorderColor = Muted,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            )
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                val user = FirebaseAuth.getInstance().currentUser
                                if (user == null) {
                                    paymentSubmitMessage = "Hubungkan akun Google terlebih dahulu."
                                } else if (payerName.trim().length < 2) {
                                    paymentSubmitMessage = "Isi nama pengirim sesuai rekening sebelum mengirim konfirmasi."
                                } else if (paymentSubmitting) {
                                    // Abaikan ketukan berulang saat permintaan sedang dikirim.
                                } else {
                                    val amount = when (selectedVipPlan) {
                                        "VIP+ Mingguan (7 hari)" -> 15000
                                        "VIP+ Bulanan (30 hari)" -> 50000
                                        "VIP+ Tahunan (365 hari)" -> 250000
                                        else -> 500000
                                    }
                                    paymentSubmitting = true
                                    paymentSubmitMessage = "Mengirim konfirmasi..."
                                    val request = hashMapOf<String, Any>(
                                        "uid" to user.uid,
                                        "email" to (user.email ?: ""),
                                        "displayName" to (user.displayName ?: ""),
                                        "plan" to selectedVipPlan,
                                        "amountIdr" to amount,
                                        "paymentMethod" to "SEABANK_TRANSFER",
                                        "bankName" to "SeaBank",
                                        "accountNumber" to "901023118338",
                                        "accountBrandName" to "M Asisten",
                                        "registeredAccountHolder" to "Muhamad Soleh",
                                        "payerName" to payerName.trim(),
                                        "transferReference" to transferReference.trim(),
                                        "status" to "PENDING_REVIEW",
                                        "createdAt" to FieldValue.serverTimestamp()
                                    )
                                    FirebaseFirestore.getInstance()
                                        .collection("vip_payment_requests")
                                        .add(request)
                                        .addOnSuccessListener {
                                            paymentSubmitting = false
                                            paymentSubmitMessage = "Konfirmasi berhasil dikirim. Status: MENUNGGU VERIFIKASI ADMIN. VIP+ belum aktif."
                                            payerName = ""
                                            transferReference = ""
                                        }
                                        .addOnFailureListener { error ->
                                            paymentSubmitting = false
                                            paymentSubmitMessage = "Gagal mengirim konfirmasi: " + (error.localizedMessage ?: "Periksa koneksi dan aturan keamanan peladen.")
                                        }
                                }
                            },
                            enabled = !paymentSubmitting && googleAccountEmail.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Cyan,
                                contentColor = Navy,
                                disabledContainerColor = Color(0xFF26384A),
                                disabledContentColor = Muted
                            )
                        ) {
                            if (paymentSubmitting) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Navy)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("KIRIM KONFIRMASI TRANSFER", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        if (paymentSubmitMessage.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(paymentSubmitMessage, color = if (paymentSubmitMessage.contains("berhasil")) Cyan else Muted, fontSize = 10.sp, lineHeight = 15.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                val user = FirebaseAuth.getInstance().currentUser
                                if (user == null) {
                                    paymentSubmitMessage = "Hubungkan akun Google untuk melihat riwayat."
                                } else if (!historyLoading) {
                                    historyLoading = true
                                    FirebaseFirestore.getInstance()
                                        .collection("vip_payment_requests")
                                        .whereEqualTo("uid", user.uid)
                                        .get()
                                        .addOnSuccessListener { snapshot ->
                                            paymentHistory = snapshot.documents
                                                .sortedByDescending { it.getTimestamp("createdAt")?.seconds ?: 0L }
                                                .map { doc ->
                                                    val plan = doc.getString("plan") ?: "Paket VIP+"
                                                    val status = when (doc.getString("status")) {
                                                        "PENDING_REVIEW" -> "MENUNGGU VERIFIKASI"
                                                        "VERIFIED" -> "TERVERIFIKASI"
                                                        "REJECTED" -> "DITOLAK"
                                                        else -> "STATUS TIDAK DIKENAL"
                                                    }
                                                    "$plan • $status"
                                                }
                                            historyLoading = false
                                        }
                                        .addOnFailureListener { error ->
                                            historyLoading = false
                                            paymentSubmitMessage = "Riwayat belum dapat dimuat: " + (error.localizedMessage ?: "Periksa koneksi.")
                                        }
                                }
                            },
                            enabled = !historyLoading && googleAccountEmail.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan)
                        ) {
                            Text(if (historyLoading) "MEMUAT RIWAYAT..." else "RIWAYAT TRANSAKSI", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        paymentHistory.forEach { item ->
                            Text("• $item", color = Color.White, fontSize = 10.sp, lineHeight = 15.sp, modifier = Modifier.padding(vertical = 3.dp))
                        }
                        Text(
                            "Langkah: pilih paket, transfer sesuai nominal, lalu kirim konfirmasi dengan nama pengirim. Nomor referensi membantu pencocokan transaksi. Ini belum mengunggah bukti transfer. Permintaan akan berstatus menunggu pemeriksaan admin; VIP+ hanya diaktifkan setelah pembayaran diverifikasi dan status keanggotaan diperbarui oleh server. Jangan mengirim data rahasia seperti PIN, OTP, atau kata sandi.",
                            color = Muted,
                            fontSize = 10.sp,
                            lineHeight = 16.sp
                        )
                    }
                },
                confirmButton = {
                    OutlinedButton(
                        onClick = { showVipPlus = false },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Cyan),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Cyan),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("TUTUP", color = Cyan, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}


