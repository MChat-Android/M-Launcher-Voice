package com.m.launchervoice

import android.app.*
import android.Manifest
import android.content.Context
import android.net.Uri
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.drawable.GradientDrawable
import java.util.Locale
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class FloatingVoiceService : Service() {

    companion object {
        const val EXTRA_MODE = "com.m.launchervoice.EXTRA_WIDGET_MODE"
        const val MODE_STANDARD = "standard"
        const val MODE_VIP = "vip"
    }

    private var vipMode = false
    private var serviceInitialized = false
    private var modeVerificationRequest = 0
    private var membershipListener: com.google.firebase.firestore.ListenerRegistration? = null
    private lateinit var windowManager: WindowManager
    private lateinit var container: FrameLayout
    private lateinit var micButton: TextView
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var vipGreetingPending = false
    private var pendingSpeechResponse: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private var listening = false
    private var awaitingWakeWord = true

    private lateinit var layoutParams: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val preferences = getSharedPreferences("m_widget_mode", MODE_PRIVATE)
        val requestedMode = intent?.getStringExtra(EXTRA_MODE)
            ?: preferences.getString("active_mode", MODE_STANDARD)
            ?: MODE_STANDARD

        // Jangan pulihkan mode VIP+ hanya dari preferensi lokal.
        // Setiap aktivasi dan pemulihan service wajib diverifikasi ke Firestore server.
        val verificationId = ++modeVerificationRequest
        if (requestedMode != MODE_VIP) {
            configureMode(false)
            return START_STICKY
        }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            configureMode(false)
            return START_STICKY
        }

        // Service memverifikasi klaim admin langsung ke Firebase sebelum mengaktifkan mode VIP+.
        user.getIdToken(true)
            .addOnSuccessListener { token ->
                if (verificationId != modeVerificationRequest) return@addOnSuccessListener
                if (token.claims["admin"] == true) {
                    configureMode(true)
                } else {
                    verifyRegularVip(user.uid, verificationId)
                }
            }
            .addOnFailureListener {
                if (verificationId == modeVerificationRequest) verifyRegularVip(user.uid, verificationId)
            }
        return START_STICKY
    }

    private fun verifyRegularVip(uid: String, verificationId: Int) {
        FirebaseFirestore.getInstance()
            .collection("vip_members")
            .document(uid)
            .get(com.google.firebase.firestore.Source.SERVER)
            .addOnSuccessListener { member ->
                if (verificationId != modeVerificationRequest) return@addOnSuccessListener
                val expiresAt = member.getTimestamp("expiresAt")?.toDate()?.time
                val vipActive = member.getString("membershipStatus") == "ACTIVE" &&
                    (expiresAt == null || expiresAt > System.currentTimeMillis())
                configureMode(vipActive)
            }
            .addOnFailureListener {
                if (verificationId == modeVerificationRequest) configureMode(false)
            }
    }

    // Mode Standar dan VIP+ memakai siklus hidup terpisah.
    // Saat paket berubah, seluruh komponen mode lama dihentikan dahulu.
    private fun configureMode(enableVip: Boolean) {
        if (serviceInitialized && vipMode == enableVip) return

        if (serviceInitialized) {
            stopCurrentMode()
        }

        vipMode = enableVip
        serviceInitialized = true
        getSharedPreferences("m_widget_mode", MODE_PRIVATE)
            .edit().putString("active_mode", if (vipMode) MODE_VIP else MODE_STANDARD).apply()

        // Standar dan VIP+ sama-sama memerlukan foreground service mikrofon.
        createNotification()
        // Mesin suara dan respons TTS tersedia pada kedua mode.
        initializeVoiceAssistant()
        if (vipMode) {
            watchVipMembership()
        } else {
            membershipListener?.remove()
            membershipListener = null
        }

        createOverlay()
    }

    private fun stopCurrentMode() {
        membershipListener?.remove()
        membershipListener = null
        try { speechRecognizer?.cancel() } catch (_: Exception) {}
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        listening = false
        awaitingWakeWord = true
        try { textToSpeech?.stop() } catch (_: Exception) {}
        try { textToSpeech?.shutdown() } catch (_: Exception) {}
        textToSpeech = null
        textToSpeechReady = false
        vipGreetingPending = false
        handler.removeCallbacksAndMessages(null)

        try {
            if (::container.isInitialized && container.isAttachedToWindow) {
                windowManager.removeView(container)
            }
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun watchVipMembership() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            handler.post { configureMode(false) }
            return
        }

        membershipListener?.remove()
        membershipListener = null

        // Admin tidak memerlukan dokumen VIP atau tanggal kedaluwarsa.
        user.getIdToken(true)
            .addOnSuccessListener { token ->
                if (token.claims["admin"] == true) return@addOnSuccessListener
                watchRegularVipMembership(user.uid)
            }
            .addOnFailureListener {
                // Jika klaim admin tidak dapat diverifikasi, hak admin tidak diasumsikan.
                watchRegularVipMembership(user.uid)
            }
    }

    private fun watchRegularVipMembership(uid: String) {
        membershipListener?.remove()
        membershipListener = FirebaseFirestore.getInstance()
            .collection("vip_members")
            .document(uid)
            .addSnapshotListener { snapshot, error ->
                val expiresAt = snapshot?.getTimestamp("expiresAt")?.toDate()?.time
                val vipStillActive = snapshot?.getString("membershipStatus") == "ACTIVE" &&
                    (expiresAt == null || expiresAt > System.currentTimeMillis())
                // Pengguna biasa tetap mengikuti status dan masa aktif VIP+ di Firestore.
                if (error != null || snapshot == null || !vipStillActive) {
                    handler.post {
                        if (vipMode) {
                            showMessage(
                                if (error != null || snapshot == null)
                                    "Status VIP+ tidak dapat diverifikasi. Kembali ke sistem Standar."
                                else
                                    "VIP+ berakhir. Kembali ke sistem Standar."
                            )
                            configureMode(false)
                        }
                    }
                }
            }
    }

    private fun createNotification() {
        val channelId = "m_launcher_voice_channel"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "M Asisten",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager =
                getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("M Asisten aktif")
            .setContentText("Widget mikrofon sedang berjalan")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                10,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(10, notification)
        }
    }

    private fun createOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Widget mengambang tetap menjadi tombol mikrofon; mode VIP mendapat identitas visual premium.
        container = FrameLayout(this).apply {
            if (vipMode) {
                // Bingkai VIP+ berlapis emas dengan dasar navy premium.
                setPadding(6, 6, 6, 6)
                background = vipWidgetFrameBackground()
            } else {
                setPadding(0, 0, 0, 0)
                background = null
            }
        }

        micButton = TextView(this).apply {
            text = if (vipMode) "🎙" else "🎤"
            textSize = 25f
            typeface = Typeface.DEFAULT
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            includeFontPadding = false
            setTextColor(Color.WHITE)
            background = if (vipMode) vipWidgetBackground()
                else roundedBackground(0xFF087F9C.toInt(), 100)
            elevation = if (vipMode) 14f else 8f
            setPadding(0, 0, 0, 0)
            setOnClickListener { handleWidgetTap() }
        }

        container.addView(
            micButton,
            FrameLayout.LayoutParams(62, 62, Gravity.CENTER)
        )

        if (vipMode) {
            val vipLabel = TextView(this).apply {
                text = "VIP+"
                // Lebar badge dibuat cukup agar seluruh karakter dan jarak huruf tidak terpotong.
                textSize = 11f
                letterSpacing = 0.035f
                typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                includeFontPadding = false
                setTextColor(0xFF352006.toInt())
                background = vipBadgeBackground()
                elevation = 22f
                setPadding(3, 2, 3, 2)
                minWidth = 56
                minHeight = 26
            }
            container.addView(
                vipLabel,
                FrameLayout.LayoutParams(
                    56,
                    26,
                    Gravity.TOP or Gravity.END
                )
            )
        }

        layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 250
        }

        try {
            windowManager.addView(container, layoutParams)
            enableDragging()
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }
    }

    private fun vipWidgetFrameBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFFE9A6.toInt(), 0xFFB87916.toInt(), 0xFFFFD76A.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(2, 0xFFFFF4CF.toInt())
        }
    }

    private fun vipBadgeBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xFFFFF1B8.toInt(), 0xFFFFC94A.toInt(), 0xFFB87916.toInt())
        ).apply {
            cornerRadius = 22f
            setStroke(1, 0xFFFFF8DE.toInt())
        }
    }

    private fun vipWidgetBackground(): GradientDrawable {
        return GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF07111F.toInt(), 0xFF123B5A.toInt())
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(2, 0xFF35D6E8.toInt())
        }
    }

    private fun enableDragging() {
    val closeTarget = TextView(this).apply {
    text = "✕"
    textSize = 28f
    gravity = Gravity.CENTER
    setTextColor(Color.WHITE)
    background = roundedBackground(0xFFE53935.toInt(), 100)
    elevation = 20f
    }

    val closeParams = WindowManager.LayoutParams(
    68,
    68,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
    WindowManager.LayoutParams.TYPE_PHONE,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
    PixelFormat.TRANSLUCENT
    ).apply {
    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    y = 36
    }

    var startX = 0
    var startY = 0
    var touchX = 0f
    var touchY = 0f
    var moved = false
    var targetVisible = false

    fun showTarget() {
    if (targetVisible) return
    try {
    windowManager.addView(closeTarget, closeParams)
    targetVisible = true
    } catch (_: Exception) {}
    }

    fun hideTarget() {
    if (!targetVisible) return
    try {
    windowManager.removeView(closeTarget)
    } catch (_: Exception) {}
    targetVisible = false
    }

    fun handleDrag(event: MotionEvent): Boolean {
    when (event.action) {
  MotionEvent.ACTION_DOWN -> {
      startX = layoutParams.x
      startY = layoutParams.y
      touchX = event.rawX
      touchY = event.rawY
      moved = false
      showTarget()
      return true
  }

  MotionEvent.ACTION_MOVE -> {
      val dx = (event.rawX - touchX).toInt()
      val dy = (event.rawY - touchY).toInt()

      if (kotlin.math.abs(dx) > 8 ||
          kotlin.math.abs(dy) > 8
      ) {
          moved = true
      }

      if (moved) {
          layoutParams.x = (startX + dx).coerceAtLeast(0)
          layoutParams.y = (startY + dy).coerceAtLeast(0)

          try {
              windowManager.updateViewLayout(
                  container,
                  layoutParams
              )
          } catch (_: Exception) {}
      }
      return true
  }

  MotionEvent.ACTION_UP -> {
      val metrics = resources.displayMetrics
      val widgetX = layoutParams.x + container.width / 2
      val widgetY = layoutParams.y + container.height / 2
      val targetX = metrics.widthPixels / 2
      val targetY = metrics.heightPixels - closeParams.y - 34

      val dismiss = moved &&
          kotlin.math.abs(widgetX - targetX) < 90 &&
          kotlin.math.abs(widgetY - targetY) < 90

      hideTarget()

      if (dismiss) {
          stopSelf()
      } else if (!moved) {
          handleWidgetTap()
      }
      return true
  }

  MotionEvent.ACTION_CANCEL -> {
      hideTarget()
      return true
  }
  }
  return true
  }

  container.setOnTouchListener { _, event ->
  handleDrag(event)
  }

  micButton.setOnTouchListener { _, event ->
  handleDrag(event)
  }
  }

    private fun initializeVoiceAssistant() {
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                textToSpeech?.let { engine ->
                    // Ikuti bahasa utama perangkat; gunakan English (US) bila bahasa itu tidak didukung.
                    val preferredLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        resources.configuration.locales[0] ?: Locale.getDefault()
                    } else {
                        @Suppress("DEPRECATION")
                        resources.configuration.locale ?: Locale.getDefault()
                    }
                    val preferredResult = engine.setLanguage(preferredLocale)
                    textToSpeechReady = preferredResult >= TextToSpeech.LANG_AVAILABLE
                    if (!textToSpeechReady) {
                        val fallbackResult = engine.setLanguage(Locale.US)
                        textToSpeechReady = fallbackResult >= TextToSpeech.LANG_AVAILABLE
                    }
                    engine.setPitch(1.08f)
                    engine.setSpeechRate(1.06f)
                    pendingSpeechResponse?.let { pending ->
                        pendingSpeechResponse = null
                        if (textToSpeechReady) handler.post { speakResponse(pending) }
                    }
                    if (vipGreetingPending && vipMode) {
                        handler.post {
                            if (vipGreetingPending && vipMode) {
                                vipGreetingPending = false
                                if (textToSpeechReady) {
                                    speakTextThenListen("Hello M, please say a command.")
                                } else {
                                    showMessage("Text-to-speech is unavailable. Please install a voice for your device language in Android settings.")
                                    startVoiceRecognition()
                                }
                            }
                        }
                    }
                }
            } else if (vipGreetingPending && vipMode) {
                handler.post {
                    vipGreetingPending = false
                    showMessage("Text-to-speech is unavailable. Please say a command.")
                    startVoiceRecognition()
                }
            }
        }
    }

    private fun handleWidgetTap() {
        if (vipMode) {
            // VIP+: sapa pengguna, lalu dengarkan satu perintah.
            speakAndListen()
        } else {
            // Standar: langsung dengarkan satu perintah tanpa sapaan suara.
            beginManualListening()
        }
    }

    private fun beginManualListening() {
        if (listening && awaitingWakeWord) {
            try {
                speechRecognizer?.cancel()
            } catch (_: Exception) {}
            listening = false
        }
        awaitingWakeWord = false
        textToSpeech?.stop()
        startVoiceRecognition()
    }

    private fun speakAndListen() {
        if (listening) return
        awaitingWakeWord = false
        if (!textToSpeechReady || textToSpeech == null) {
            vipGreetingPending = true
            showMessage("Menyiapkan sapaan suara...")
            return
        }
        vipGreetingPending = false
        speakTextThenListen("Hallo M, silakan ucapkan perintah.")
    }

    private fun speakTextThenListen(message: String) {
        val engine = textToSpeech
        if (textToSpeechReady && engine != null) {
            try {
                showMessage(message, speak = false)
                val utteranceId = "m_voice_prompt_" + System.currentTimeMillis()
                engine.setOnUtteranceProgressListener(
                    object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            if (id == utteranceId) handler.post { startVoiceRecognition() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            if (id == utteranceId) handler.post { startVoiceRecognition() }
                        }
                    }
                )
                val result = engine.speak(
                    message,
                    TextToSpeech.QUEUE_FLUSH,
                    null,
                    utteranceId
                )
                if (result == TextToSpeech.ERROR) startVoiceRecognition()
            } catch (_: Exception) {
                startVoiceRecognition()
            }
        } else {
            showMessage(message)
            startVoiceRecognition()
        }
    }

    private fun speakWakeWordResponse() {
        awaitingWakeWord = false
        speakTextThenListen("Hallo, silahkan ucapkan perintah.")
    }

    private fun isWakeWord(text: String): Boolean {
        val normalized = text.lowercase(Locale("id", "ID"))
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return normalized == "hallo m" ||
            normalized == "halo m" ||
            normalized == "hello m" ||
            normalized == "hallo em" ||
            normalized == "halo em" ||
            normalized == "hello em"
    }

    private fun scheduleWakeWordListening(delayMs: Long = 800L) {
        handler.postDelayed({
            if (!listening) {
                awaitingWakeWord = true
                startVoiceRecognition()
            }
        }, delayMs)
    }

    private fun startVoiceRecognition() {
        if (listening) return

        // Jangan pernah jatuh kembali ke layanan pengenalan suara cloud.
        // API on-device tersedia mulai Android 12 (API 31).
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            showMessage("Pengenalan suara offline memerlukan Android 12 atau lebih baru pada versi aplikasi ini.")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            showMessage("Pengenalan suara offline tidak tersedia. Periksa dukungan layanan suara offline dan paket Bahasa Indonesia di perangkat.")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {
                    override fun onReadyForSpeech(params: android.os.Bundle?) {
                        showMessage(
                            if (awaitingWakeWord) "Menunggu panggilan Hallo M..."
                            else "Silahkan ucapkan perintah..."
                        )
                    }

                    override fun onBeginningOfSpeech() {
                        showMessage("Mendengarkan...")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        showMessage("Memproses suara...")
                    }

                    override fun onError(error: Int) {
                        listening = false
                        awaitingWakeWord = false
                        val detail = when (error) {
                            SpeechRecognizer.ERROR_AUDIO -> "Masalah pada input mikrofon"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Izin mikrofon belum diberikan"
                            SpeechRecognizer.ERROR_NETWORK,
                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Pengenalan suara offline gagal. Pastikan paket Bahasa Indonesia offline tersedia di perangkat."
                            SpeechRecognizer.ERROR_NO_MATCH,
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Suara tidak dikenali. Ketuk widget untuk mencoba lagi."
                            else -> "Pengenalan suara gagal (kode $error). Ketuk widget untuk mencoba lagi."
                        }
                        showMessage(detail)
                    }

                    override fun onResults(results: android.os.Bundle?) {
                        listening = false
                        awaitingWakeWord = false
                        val words = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull()

                        if (!words.isNullOrBlank()) {
                            showMessage("Perintah: $words", speak = false)
                            executeCommand(words)
                        } else {
                            showMessage("Perintah tidak dikenali. Ketuk widget untuk mencoba lagi.")
                        }
                    }

                    override fun onPartialResults(partialResults: android.os.Bundle?) {
                        val words = partialResults?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )?.firstOrNull()
                        if (!words.isNullOrBlank() && !awaitingWakeWord) {
                            showMessage(words, speak = false)
                        }
                    }

                    override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
                }
            )

            val deviceLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                resources.configuration.locales[0] ?: Locale.getDefault()
            } else {
                @Suppress("DEPRECATION")
                resources.configuration.locale ?: Locale.getDefault()
            }
            val deviceLanguageTag = deviceLocale.toLanguageTag().takeIf { it.isNotBlank() } ?: "en-US"
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                // Pengenalan suara mengikuti bahasa utama perangkat.
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, deviceLanguageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, deviceLanguageTag)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            }

            val recognizer = speechRecognizer
                ?: throw IllegalStateException("Mesin suara offline tidak tersedia")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Pilih bahasa perangkat jika tersedia; jika tidak, gunakan English (US).
                listening = true
                val callbackExecutor = java.util.concurrent.Executor { task ->
                    handler.post(task)
                }
                recognizer.checkRecognitionSupport(
                    intent,
                    callbackExecutor,
                    object : android.speech.RecognitionSupportCallback {
                        override fun onSupportResult(
                            support: android.speech.RecognitionSupport
                        ) {
                            if (speechRecognizer !== recognizer) return
                            fun hasLanguage(languages: List<String>, language: String): Boolean =
                                languages.any {
                                    Locale.forLanguageTag(it).language.equals(language, ignoreCase = true)
                                }

                            val preferredLanguage = deviceLocale.language.ifBlank { "en" }
                            val targetLanguage = when {
                                hasLanguage(support.installedOnDeviceLanguages, preferredLanguage) ||
                                    hasLanguage(support.pendingOnDeviceLanguages, preferredLanguage) ||
                                    hasLanguage(support.supportedOnDeviceLanguages, preferredLanguage) -> deviceLanguageTag
                                hasLanguage(support.installedOnDeviceLanguages, "en") ||
                                    hasLanguage(support.pendingOnDeviceLanguages, "en") ||
                                    hasLanguage(support.supportedOnDeviceLanguages, "en") -> "en-US"
                                else -> null
                            }

                            if (targetLanguage == null) {
                                listening = false
                                showMessage("Offline speech is not available for your device language or English. Check your Android speech-language settings.")
                                return
                            }

                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, targetLanguage)
                            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, targetLanguage)
                            when {
                                hasLanguage(support.installedOnDeviceLanguages, Locale.forLanguageTag(targetLanguage).language) -> {
                                    try {
                                        recognizer.startListening(intent)
                                        listening = true
                                    } catch (_: Exception) {
                                        listening = false
                                        showMessage("Could not start offline speech recognition.")
                                    }
                                }
                                hasLanguage(support.pendingOnDeviceLanguages, Locale.forLanguageTag(targetLanguage).language) -> {
                                    listening = false
                                    showMessage("The speech language pack is being prepared by Android. Try again when it is ready.")
                                }
                                else -> {
                                    listening = false
                                    showMessage("Downloading the speech language pack. Connect to the internet, then try again.")
                                    try {
                                        recognizer.triggerModelDownload(intent)
                                    } catch (_: Exception) {
                                        showMessage("Could not download the speech language pack. Check Android speech settings.")
                                    }
                                }
                            }
                        }

                        override fun onError(error: Int) {
                            if (speechRecognizer !== recognizer) return
                            if (error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT) {
                                try {
                                    recognizer.startListening(intent)
                                    listening = true
                                } catch (_: Exception) {
                                    listening = false
                                    showMessage("Could not start offline speech recognition.")
                                }
                            } else {
                                listening = false
                                showMessage("Could not check offline speech-language support (code $error).")
                            }
                        }
                    }
                )
            } else {
                // Android 6–12: gunakan bahasa perangkat yang dipilih pada intent.
                listening = true
                recognizer.startListening(intent)
            }
        } catch (_: Exception) {
            listening = false
            showMessage("Mikrofon atau mesin pengenalan suara offline tidak dapat diaktifkan.")
            if (awaitingWakeWord) scheduleWakeWordListening(1800L)
        }
    }

    private fun showMessage(message: String, speak: Boolean = true) {
        handler.post {
            // Simpan pesan terakhir agar tetap terlihat saat layar utama dibuka kembali.
            getSharedPreferences("m_voice_status", MODE_PRIVATE)
                .edit()
                .putString("latest_message", message)
                .apply()
            // Widget tidak menampilkan teks; pesan dikirim ke layar utama aplikasi.
            sendBroadcast(
                Intent("com.m.launchervoice.VOICE_MESSAGE")
                    .setPackage(packageName)
                    .putExtra("message", message)
            )
            if (speak && shouldSpeakResponse(message)) {
                speakResponse(message)
            }
        }
    }

    private fun shouldSpeakResponse(message: String): Boolean {
        val value = message.trim()
        if (value.isBlank()) return false
        val lower = value.lowercase(Locale("id", "ID"))
        return !lower.startsWith("perintah:") &&
            lower != "mendengarkan..." &&
            lower != "memproses suara..." &&
            !lower.startsWith("memeriksa akses vip+") &&
            !lower.startsWith("menyiapkan sapaan suara") &&
            !lower.startsWith("aplikasi vip+ sedang diperiksa")
    }

    private fun speakResponse(message: String) {
        val engine = textToSpeech
        if (!textToSpeechReady || engine == null) {
            pendingSpeechResponse = message
            return
        }
        try {
            val utteranceId = "m_voice_response_" + System.currentTimeMillis()
            engine.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                utteranceId
            )
        } catch (_: Exception) {
            pendingSpeechResponse = message
        }
    }

    private fun executeCommand(command: String) {
        // Pisahkan perintah berantai hanya pada penghubung urutan yang jelas.
        val sequenceParts = command
            .split(Regex("\\s+(?:lalu|kemudian|setelah itu)\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (sequenceParts.size > 1) {
            if (sequenceParts.size > 3) {
                showMessage("Maksimal tiga perintah dalam satu ucapan. Silakan bagi menjadi beberapa ucapan.")
                return
            }

            showMessage("Menjalankan ${sequenceParts.size} perintah secara berurutan.")
            sequenceParts.forEachIndexed { index, part ->
                handler.postDelayed({
                    showMessage("Menjalankan perintah ${index + 1} dari ${sequenceParts.size}.", speak = false)
                    executeCommand(part)
                }, index * 1800L)
            }
            return
        }

        val text = command.lowercase(Locale("id", "ID"))
            .replace(Regex("[^\\p{L}\\p{N}+ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("tolong ").removePrefix("silakan ")
            .removePrefix("please ").removePrefix("coba ")
            .removePrefix("bisa ").removePrefix("mohon ")
            .removePrefix("buka ").removePrefix("open ")
            .removePrefix("launch ").removePrefix("jalankan ")
            .removePrefix("start ").trim()
            // Padanan ucapan sehari-hari ke kata kerja yang dikenali mesin perintah.
            .replace(Regex("\\b(nyalain|hidupin|idupin)\\b"), "nyalakan")
            .replace(Regex("\\b(matiin|matikanlah|padamin)\\b"), "matikan")
            .replace(Regex("\\b(aktifin)\\b"), "aktifkan")
            .replace(Regex("\\b(besarin|gedein|kerasin)\\b"), "naikkan")
            .replace(Regex("\\b(kecilin|pelanin|kecilkan)\\b"), "turunkan")
            .replace(Regex("\\b(terangin|cerahkan)\\b"), "terang")
            .replace(Regex("\\b(gelapin|redupkan)\\b"), "redup")

        if (text.contains("daftar aplikasi") ||
            text.contains("aplikasi terpasang") ||
            text.contains("lihat aplikasi") ||
            text.contains("aplikasi apa saja") ||
            text.contains("tampilkan aplikasi")
        ) {
            showAvailableApps()
            return
        }

        // Menjawab tanggal, hari, dan waktu lokal dari jam perangkat.
        val asksDate = text.contains("tanggal berapa") ||
            text.contains("tanggal hari ini") ||
            text.contains("hari apa") ||
            text == "tanggal sekarang" ||
            text == "tanggal"
        val asksTime = text.contains("jam berapa") ||
            text.contains("pukul berapa") ||
            text == "waktu sekarang" ||
            text == "jam sekarang"
        if ((asksDate || asksTime) &&
            (text.contains("sekarang") || text.contains("hari ini") ||
             text.contains("berapa") || text == "tanggal" ||
             text == "jam sekarang" || text == "waktu sekarang")
        ) {
            val now = java.util.Date()
            val dateFormat = java.text.SimpleDateFormat(
                "EEEE, d MMMM yyyy", Locale("id", "ID")
            )
            val timeFormat = java.text.SimpleDateFormat(
                "HH:mm", Locale("id", "ID")
            )
            val answer = when {
                asksDate && asksTime -> "Sekarang ${dateFormat.format(now)}, pukul ${timeFormat.format(now)}."
                asksDate -> "Hari ini ${dateFormat.format(now)}."
                else -> "Sekarang pukul ${timeFormat.format(now)}."
            }
            showMessage(answer)
            return
        }

        // Status koneksi internet dan jenis jaringan aktif.
        if (text.contains("internet") ||
            text.contains("koneksi jaringan") ||
            text.contains("status jaringan")
        ) {
            try {
                val connectivityManager = getSystemService(
                    android.content.Context.CONNECTIVITY_SERVICE
                ) as android.net.ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork
                val capabilities = activeNetwork?.let {
                    connectivityManager.getNetworkCapabilities(it)
                }
                if (capabilities == null) {
                    showMessage("Saat ini perangkat tidak terhubung ke jaringan.")
                } else {
                    val networkType = when {
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "data seluler"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                        else -> "jaringan lain"
                    }
                    val hasInternet = capabilities.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
                    )
                    val validated = capabilities.hasCapability(
                        android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                    )
                    val answer = when {
                        !hasInternet -> "Terhubung ke $networkType, tetapi jaringan tidak menyediakan akses internet."
                        validated -> "Internet terhubung melalui $networkType dan akses internet terdeteksi aktif."
                        else -> "Terhubung ke $networkType, tetapi akses internet belum dapat dipastikan."
                    }
                    showMessage(answer)
                }
            } catch (_: Exception) {
                showMessage("Status koneksi internet tidak dapat dibaca.")
            }
            return
        }

        // Lama waktu perangkat aktif sejak terakhir dinyalakan.
        if (text.contains("uptime") ||
            text.contains("waktu aktif hp") ||
            text.contains("lama hp menyala") ||
            text.contains("sudah menyala berapa lama") ||
            text.contains("hp menyala berapa lama") ||
            text.contains("sejak kapan hp menyala")
        ) {
            try {
                var remainingMillis = android.os.SystemClock.elapsedRealtime()
                    .coerceAtLeast(0L)
                val days = remainingMillis / 86_400_000L
                remainingMillis %= 86_400_000L
                val hours = remainingMillis / 3_600_000L
                remainingMillis %= 3_600_000L
                val minutes = remainingMillis / 60_000L
                val parts = mutableListOf<String>()
                if (days > 0) parts.add("$days hari")
                if (hours > 0) parts.add("$hours jam")
                if (minutes > 0 || parts.isEmpty()) parts.add("$minutes menit")
                showMessage("Perangkat sudah aktif selama " + parts.joinToString(" ") + ".")
            } catch (_: Exception) {
                showMessage("Lama waktu perangkat aktif tidak dapat dibaca.")
            }
            return
        }

        // Informasi identitas dasar perangkat dan versi Android.
        if (text.contains("informasi hp") ||
            text.contains("info hp") ||
            text.contains("informasi perangkat") ||
            text.contains("info perangkat") ||
            text.contains("tipe hp") ||
            text.contains("model hp") ||
            text.contains("versi android")
        ) {
            try {
                val manufacturer = android.os.Build.MANUFACTURER
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale("id", "ID")) else it.toString() }
                val model = android.os.Build.MODEL
                val androidVersion = android.os.Build.VERSION.RELEASE
                val sdkVersion = android.os.Build.VERSION.SDK_INT
                showMessage(
                    "Informasi perangkat: $manufacturer $model. " +
                        "Versi Android $androidVersion, API level $sdkVersion."
                )
            } catch (_: Exception) {
                showMessage("Informasi perangkat belum dapat dibaca.")
            }
            return
        }

        // Informasi penggunaan RAM perangkat, dibaca melalui ActivityManager.
        if (text.contains("ram") ||
            text.contains("memori ram") ||
            text.contains("memory ram")
        ) {
            try {
                val activityManager = getSystemService(
                    android.content.Context.ACTIVITY_SERVICE
                ) as android.app.ActivityManager
                val memoryInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                val totalBytes = memoryInfo.totalMem.coerceAtLeast(0L)
                val freeBytes = memoryInfo.availMem.coerceIn(0L, totalBytes)
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
                val divisor = 1024.0 * 1024.0 * 1024.0
                val numberFormat = java.text.DecimalFormat(
                    "0.0",
                    java.text.DecimalFormatSymbols(Locale("id", "ID"))
                )
                val totalGb = numberFormat.format(totalBytes / divisor)
                val freeGb = numberFormat.format(freeBytes / divisor)
                val usedGb = numberFormat.format(usedBytes / divisor)
                val pressureText = if (memoryInfo.lowMemory) {
                    "Sistem sedang menandai kondisi memori rendah."
                } else {
                    "Kondisi memori masih normal."
                }
                showMessage(
                    "RAM perangkat: total $totalGb GB, digunakan sekitar $usedGb GB, " +
                        "tersedia $freeGb GB. $pressureText"
                )
            } catch (_: Exception) {
                showMessage("Informasi RAM perangkat tidak dapat dibaca.")
            }
            return
        }

        // Informasi ruang penyimpanan internal perangkat.
        if (text.contains("penyimpanan") ||
            text.contains("storage internal") ||
            text.contains("ruang internal")
        ) {
            try {
                val storage = android.os.StatFs(
                    android.os.Environment.getDataDirectory().path
                )
                val freeBytes = storage.availableBytes
                val totalBytes = storage.totalBytes
                val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
                val divisor = 1024.0 * 1024.0 * 1024.0
                val numberFormat = java.text.DecimalFormat(
                    "0.0",
                    java.text.DecimalFormatSymbols(Locale("id", "ID"))
                )
                val freeGb = numberFormat.format(freeBytes / divisor)
                val totalGb = numberFormat.format(totalBytes / divisor)
                val usedGb = numberFormat.format(usedBytes / divisor)
                showMessage(
                    "Penyimpanan internal: tersisa $freeGb GB dari $totalGb GB. " +
                        "Yang sudah digunakan sekitar $usedGb GB."
                )
            } catch (_: Exception) {
                showMessage("Informasi penyimpanan internal tidak dapat dibaca.")
            }
            return
        }

        // Membuka halaman pengaturan mode hemat daya Android.
        if (
            text == "buka pengaturan hemat daya" ||
            text == "pengaturan hemat daya hp" ||
            text == "atur penghemat baterai" ||
            text == "pengaturan battery saver" ||
            text == "buka pengaturan penghemat baterai"
        ) {
            openSettings(Settings.ACTION_BATTERY_SAVER_SETTINGS)
            return
        }

        // Status mode hemat daya Android, dibaca secara lokal tanpa izin tambahan.
        if (text.contains("hemat daya") ||
            text.contains("penghemat baterai") ||
            text.contains("battery saver") ||
            text.contains("mode hemat baterai")
        ) {
            try {
                val powerManager = getSystemService(
                    android.content.Context.POWER_SERVICE
                ) as android.os.PowerManager
                val saverActive = powerManager.isPowerSaveMode
                if (saverActive) {
                    showMessage("Mode hemat daya sedang aktif.")
                } else {
                    showMessage("Mode hemat daya sedang tidak aktif.")
                }
            } catch (_: Exception) {
                showMessage("Status mode hemat daya tidak dapat dibaca.")
            }
            return
        }

        // Suhu dan kondisi kesehatan baterai dari siaran status baterai Android.
        if (text.contains("suhu baterai") ||
            text.contains("temperatur baterai") ||
            text.contains("kesehatan baterai") ||
            text.contains("kondisi baterai")
        ) {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (batteryIntent == null) {
                    showMessage("Informasi baterai belum tersedia.")
                } else {
                    val temperatureTenths = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE
                    )
                    val health = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_HEALTH,
                        android.os.BatteryManager.BATTERY_HEALTH_UNKNOWN
                    )
                    val healthText = when (health) {
                        android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "kondisi terdeteksi baik"
                        android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "baterai terdeteksi terlalu panas"
                        android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "baterai dilaporkan sudah rusak"
                        android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "terdeteksi tegangan berlebih"
                        android.os.BatteryManager.BATTERY_HEALTH_COLD -> "baterai terdeteksi terlalu dingin"
                        else -> "kondisi kesehatan tidak dilaporkan oleh perangkat"
                    }
                    val temperatureText = if (temperatureTenths != Int.MIN_VALUE) {
                        val celsius = temperatureTenths / 10.0
                        val format = java.text.DecimalFormat(
                            "0.0",
                            java.text.DecimalFormatSymbols(Locale("id", "ID"))
                        )
                        "Suhu baterai sekitar ${format.format(celsius)} derajat Celsius. "
                    } else {
                        "Suhu baterai tidak tersedia. "
                    }
                    showMessage(temperatureText + "Status kesehatan: " + healthText + ".")
                }
            } catch (_: Exception) {
                showMessage("Informasi suhu dan kesehatan baterai tidak dapat dibaca.")
            }
            return
        }

        // Tegangan dan arus baterai, dibaca lokal melalui Android BatteryManager.
        if (text.contains("tegangan baterai") ||
            text.contains("voltase baterai") ||
            text.contains("arus baterai") ||
            text.contains("arus pengisian baterai")
        ) {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (batteryIntent == null) {
                    showMessage("Informasi kelistrikan baterai belum tersedia.")
                } else {
                    val voltageMv = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_VOLTAGE, -1
                    )
                    val batteryManager = getSystemService(
                        android.content.Context.BATTERY_SERVICE
                    ) as android.os.BatteryManager
                    val currentMicroAmps = batteryManager.getIntProperty(
                        android.os.BatteryManager.BATTERY_PROPERTY_CURRENT_NOW
                    )
                    val voltageText = if (voltageMv > 0) {
                        "Tegangan baterai sekitar " +
                            String.format(Locale.US, "%.2f", voltageMv / 1000.0) +
                            " volt. "
                    } else {
                        "Tegangan baterai tidak tersedia. "
                    }
                    val currentText = if (currentMicroAmps != Int.MIN_VALUE) {
                        val currentMa = currentMicroAmps / 1000
                        val direction = when {
                            currentMa > 0 -> "arus masuk atau pengisian"
                            currentMa < 0 -> "arus keluar atau pemakaian"
                            else -> "arus saat ini mendekati nol"
                        }
                        "Arus baterai sekitar " +
                            String.format(Locale("id", "ID"), "%,d", kotlin.math.abs(currentMa)) +
                            " miliampere, $direction."
                    } else {
                        "Data arus baterai tidak didukung oleh perangkat."
                    }
                    showMessage(voltageText + currentText)
                }
            } catch (_: Exception) {
                showMessage("Tegangan dan arus baterai tidak dapat dibaca.")
            }
            return
        }

        // Jenis sumber daya yang sedang terhubung ke perangkat.
        if (
            text.contains("sumber pengisian baterai") ||
            text.contains("sumber daya baterai") ||
            text.contains("jenis charger") ||
            text.contains("jenis pengisi daya") ||
            text.contains("pakai charger apa") ||
            text.contains("baterai diisi pakai apa") ||
            text.contains("pengisian lewat apa") ||
            text.contains("charging source")
        ) {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                if (batteryIntent == null) {
                    showMessage("Sumber pengisian baterai belum dapat dibaca.")
                } else {
                    val plugged = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_PLUGGED, 0
                    )
                    val sourceText = when {
                        plugged == 0 -> "Perangkat tidak sedang terhubung ke sumber pengisian daya."
                        (plugged and android.os.BatteryManager.BATTERY_PLUGGED_AC) != 0 ->
                            "Baterai terhubung ke pengisi daya listrik atau adaptor AC."
                        (plugged and android.os.BatteryManager.BATTERY_PLUGGED_USB) != 0 ->
                            "Baterai terhubung melalui USB, seperti port komputer atau adaptor USB."
                        (plugged and android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0 ->
                            "Baterai sedang menggunakan pengisian daya nirkabel."
                        (plugged and android.os.BatteryManager.BATTERY_PLUGGED_DOCK) != 0 ->
                            "Baterai terhubung ke dock pengisian daya."
                        else -> "Baterai terhubung ke sumber daya, tetapi jenis pengisi dayanya tidak dikenali."
                    }
                    showMessage(sourceText)
                }
            } catch (_: Exception) {
                showMessage("Maaf, sumber pengisian baterai tidak dapat diperiksa.")
            }
            return
        }

        // Estimasi kapasitas baterai dalam mAh jika disediakan oleh perangkat.
        if (
            text.contains("kapasitas baterai") ||
            text.contains("kapasitas m ah") ||
            text.contains("kapasitas mah") ||
            text.contains("kapasitas milliampere") ||
            text.contains("battery capacity")
        ) {
            try {
                val batteryManager = getSystemService(
                    android.content.Context.BATTERY_SERVICE
                ) as android.os.BatteryManager
                val chargeCounter = batteryManager.getIntProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                )
                val capacityNowMah = if (chargeCounter > 0) {
                    chargeCounter / 1000
                } else {
                    -1
                }
                val capacityText = if (capacityNowMah > 0) {
                    "Sisa muatan baterai yang terukur sekitar " +
                        String.format(Locale("id", "ID"), "%,d", capacityNowMah) +
                        " miliampere jam. Ini adalah perkiraan muatan saat ini, bukan kapasitas maksimum baterai."
                } else {
                    "Perangkat tidak menyediakan data penghitung muatan baterai yang dapat dibaca."
                }
                showMessage(capacityText)
            } catch (_: Exception) {
                showMessage("Maaf, data kapasitas atau muatan baterai tidak dapat dibaca.")
            }
            return
        }

        // Energi baterai tersisa jika perangkat menyediakan penghitung energi.
        // Android melaporkan nilai dalam nanowatt-jam (nWh).
        if (
            text.contains("energi baterai") ||
            text.contains("sisa energi baterai") ||
            text.contains("battery energy") ||
            text.contains("energy counter") ||
            text.contains("berapa energi baterai")
        ) {
            try {
                val batteryManager = getSystemService(
                    android.content.Context.BATTERY_SERVICE
                ) as android.os.BatteryManager
                val energyNowNwh = batteryManager.getLongProperty(
                    android.os.BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER
                )
                if (energyNowNwh > 0L && energyNowNwh != Long.MAX_VALUE) {
                    val energyNowMwh = energyNowNwh / 1_000_000.0
                    val formattedEnergy = String.format(
                        Locale("id", "ID"), "%,.1f", energyNowMwh
                    )
                    showMessage(
                        "Energi baterai yang terukur saat ini sekitar $formattedEnergy miliwatt-jam. Nilai ini adalah perkiraan energi tersisa, bukan kapasitas maksimum baterai."
                    )
                } else {
                    showMessage("Perangkat tidak menyediakan penghitung energi baterai yang dapat dibaca.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, informasi energi baterai tidak dapat dibaca.")
            }
            return
        }

        // Membaca jumlah siklus pengisian baterai jika Android dan perangkat menyediakannya.
        if (
            text.contains("siklus baterai") ||
            text.contains("jumlah siklus baterai") ||
            text.contains("battery cycle") ||
            text.contains("battery cycles") ||
            text.contains("berapa kali baterai diisi")
        ) {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    val batteryIntent = registerReceiver(
                        null,
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                    )
                    val cycleCount = batteryIntent?.getIntExtra(
                        android.os.BatteryManager.EXTRA_CYCLE_COUNT, -1
                    ) ?: -1
                    if (cycleCount >= 0) {
                        showMessage("Jumlah siklus pengisian baterai yang dilaporkan perangkat adalah $cycleCount kali.")
                    } else {
                        showMessage("Perangkat tidak menyediakan data jumlah siklus baterai.")
                    }
                } else {
                    showMessage("Pembacaan siklus baterai memerlukan Android 14 atau lebih baru, dan dukungan perangkat.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, jumlah siklus baterai tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        // Membaca tingkat kondisi termal perangkat melalui Android PowerManager.
        if (
            text == "status panas hp" ||
            text == "kondisi panas hp" ||
            text == "kondisi termal perangkat" ||
            text == "status suhu perangkat" ||
            text == "hp sedang panas" ||
            text == "thermal status" ||
            text == "tingkat panas hp"
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val powerManager = getSystemService(
                        android.content.Context.POWER_SERVICE
                    ) as android.os.PowerManager
                    val thermalStatus = powerManager.currentThermalStatus
                    val description = when (thermalStatus) {
                        android.os.PowerManager.THERMAL_STATUS_NONE -> "normal, tidak ada pembatasan termal"
                        android.os.PowerManager.THERMAL_STATUS_LIGHT -> "hangat ringan"
                        android.os.PowerManager.THERMAL_STATUS_MODERATE -> "panas sedang"
                        android.os.PowerManager.THERMAL_STATUS_SEVERE -> "panas tinggi, kinerja dapat dibatasi"
                        android.os.PowerManager.THERMAL_STATUS_CRITICAL -> "sangat panas, sistem dapat membatasi kinerja secara ketat"
                        android.os.PowerManager.THERMAL_STATUS_EMERGENCY -> "darurat termal"
                        android.os.PowerManager.THERMAL_STATUS_SHUTDOWN -> "melewati ambang aman dan dapat memicu pemadaman"
                        else -> "status tidak dikenal"
                    }
                    showMessage(
                        "Status termal perangkat: $description. " +
                            "Ini adalah tingkat kondisi panas yang dilaporkan sistem, bukan pengukuran suhu dalam derajat."
                    )
                } else {
                    showMessage("Pembacaan status termal memerlukan Android 10 atau lebih baru.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status termal perangkat tidak dapat dibaca.")
            }
            return
        }

        // Membaca versi dan nomor build aplikasi M Launcher Voice yang sedang berjalan.
        if (
            text == "versi m launcher voice" ||
            text == "versi aplikasi m" ||
            text == "versi aplikasi launcher" ||
            text == "nomor versi aplikasi" ||
            text == "versi aplikasi ini" ||
            text == "app version m launcher"
        ) {
            try {
                val appInfo = packageManager.getPackageInfo(packageName, 0)
                val versionName = appInfo.versionName?.takeIf { it.isNotBlank() } ?: "tidak tersedia"
                val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appInfo.longVersionCode
                } else {
                    @Suppress("DEPRECATION")
                    appInfo.versionCode.toLong()
                }
                showMessage(
                    "M Launcher Voice versi $versionName, nomor build aplikasi $versionCode."
                )
            } catch (_: Exception) {
                showMessage("Maaf, informasi versi M Launcher Voice tidak dapat dibaca.")
            }
            return
        }

        // Membaca nama keyboard utama yang dipilih pada pengaturan Android.
        if (
            text == "nama keyboard hp" ||
            text == "keyboard utama hp" ||
            text == "keyboard default hp" ||
            text == "papan ketik hp" ||
            text == "keyboard yang digunakan hp" ||
            text == "cek keyboard perangkat" ||
            text == "default keyboard"
        ) {
            try {
                val defaultImeId = Settings.Secure.getString(
                    contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD
                )
                if (defaultImeId.isNullOrBlank()) {
                    showMessage("Android tidak melaporkan keyboard utama yang sedang dipilih.")
                } else {
                    val inputMethodManager = getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    val inputMethodInfo = inputMethodManager.inputMethodList
                        .firstOrNull { it.id == defaultImeId }
                    val keyboardName = inputMethodInfo?.loadLabel(packageManager)?.toString()
                        ?.takeIf { it.isNotBlank() }
                        ?: defaultImeId.substringBefore("/")
                    showMessage("Keyboard utama perangkat saat ini adalah $keyboardName.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, nama keyboard utama tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        // Membuka halaman pengaturan keyboard Android melalui perintah suara.
        if (
            text == "buka pengaturan keyboard" ||
            text == "pengaturan keyboard hp" ||
            text == "ubah keyboard hp" ||
            text == "ganti keyboard hp" ||
            text == "atur keyboard hp" ||
            text == "buka pengaturan papan ketik"
        ) {
            showMessage("Membuka pengaturan keyboard perangkat...")
            openSettings(Settings.ACTION_INPUT_METHOD_SETTINGS)
            return
        }

        // Membuka pengaturan aksesibilitas Android melalui perintah suara.
        if (
            text == "buka pengaturan aksesibilitas" ||
            text == "pengaturan aksesibilitas hp" ||
            text == "atur aksesibilitas hp" ||
            text == "fitur aksesibilitas" ||
            text == "buka menu aksesibilitas" ||
            text == "pengaturan kemudahan akses"
        ) {
            showMessage("Membuka pengaturan aksesibilitas perangkat...")
            openSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            return
        }

        // Memeriksa apakah perangkat memiliki kunci layar yang aman dan status layar terkunci.
        if (
            text == "status kunci layar hp" ||
            text == "keamanan kunci layar" ||
            text == "apakah hp pakai pin" ||
            text == "apakah hp dikunci" ||
            text == "status pengunci layar" ||
            text == "cek kunci layar perangkat" ||
            text == "screen lock status"
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val keyguardManager = getSystemService(
                        android.content.Context.KEYGUARD_SERVICE
                    ) as android.app.KeyguardManager
                    val secureLock = keyguardManager.isDeviceSecure
                    val currentlyLocked = keyguardManager.isKeyguardLocked
                    val secureText = if (secureLock) {
                        "kunci layar aman sudah diatur"
                    } else {
                        "belum ada PIN, pola, atau kata sandi layar yang aman"
                    }
                    val lockedText = if (currentlyLocked) {
                        "layar sedang terkunci"
                    } else {
                        "layar saat ini tidak terkunci"
                    }
                    showMessage("Status keamanan: $secureText. Status saat ini: $lockedText.")
                } else {
                    showMessage("Pemeriksaan keamanan kunci layar memerlukan Android 6.0 atau lebih baru.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status keamanan kunci layar tidak dapat dibaca.")
            }
            return
        }

        // Memeriksa apakah jam dan zona waktu disinkronkan otomatis oleh jaringan.
        if (
            text == "waktu otomatis hp" ||
            text == "jam otomatis hp" ||
            text == "tanggal otomatis hp" ||
            text == "zona waktu otomatis hp" ||
            text == "sinkronisasi waktu hp" ||
            text == "sinkronisasi jam perangkat" ||
            text == "status waktu otomatis" ||
            text == "apakah jam hp otomatis"
        ) {
            try {
                val automaticTime = Settings.Global.getInt(
                    contentResolver, Settings.Global.AUTO_TIME, 0
                ) == 1
                val automaticTimeZone = Settings.Global.getInt(
                    contentResolver, Settings.Global.AUTO_TIME_ZONE, 0
                ) == 1
                val timeText = if (automaticTime) "aktif" else "nonaktif"
                val zoneText = if (automaticTimeZone) "aktif" else "nonaktif"
                showMessage(
                    "Pengaturan tanggal dan jam otomatis: $timeText. " +
                        "Zona waktu otomatis: $zoneText."
                )
            } catch (_: Exception) {
                showMessage("Maaf, status sinkronisasi waktu otomatis tidak dapat dibaca.")
            }
            return
        }

        // Membaca preferensi format jam 12/24 jam dan format tanggal sistem.
        if (
            text == "format jam hp" ||
            text == "format jam ponsel" ||
            text == "format waktu perangkat" ||
            text == "jam 12 atau 24" ||
            text == "jam 12 atau 24 jam" ||
            text == "hp menggunakan jam berapa" ||
            text == "format tanggal hp" ||
            text == "format tanggal perangkat" ||
            text == "pengaturan format tanggal"
        ) {
            try {
                val is24Hour = android.text.format.DateFormat.is24HourFormat(this)
                val datePattern = Settings.System.getString(
                    contentResolver, Settings.System.DATE_FORMAT
                )
                val timeDescription = if (is24Hour) {
                    "24 jam (contoh 18:30)"
                } else {
                    "12 jam (contoh 6:30 PM)"
                }
                val dateDescription = if (datePattern.isNullOrBlank()) {
                    "mengikuti format tanggal bawaan wilayah perangkat"
                } else {
                    datePattern
                }
                showMessage("Format jam perangkat: $timeDescription. Format tanggal: $dateDescription.")
            } catch (_: Exception) {
                showMessage("Maaf, preferensi format jam dan tanggal tidak dapat dibaca.")
            }
            return
        }

        // Membaca skala animasi jendela, transisi, dan animator Android.
        if (
            text == "skala animasi hp" ||
            text == "skala animasi android" ||
            text == "kecepatan animasi hp" ||
            text == "kecepatan animasi sistem" ||
            text == "pengaturan animasi hp" ||
            text == "animasi jendela hp" ||
            text == "status animasi sistem"
        ) {
            try {
                val windowScale = Settings.Global.getFloat(
                    contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE, 1.0f
                )
                val transitionScale = Settings.Global.getFloat(
                    contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE, 1.0f
                )
                val animatorScale = Settings.Global.getFloat(
                    contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1.0f
                )
                fun describeScale(value: Float): String {
                    val number = "%.1f".format(java.util.Locale.US, value)
                    return when {
                        value == 0f -> "mati"
                        value < 1f -> "$number kali, lebih cepat dari standar"
                        value > 1f -> "$number kali, lebih lambat dari standar"
                        else -> "1 kali, standar"
                    }
                }
                showMessage(
                    "Skala animasi Android: jendela ${describeScale(windowScale)}; " +
                        "transisi ${describeScale(transitionScale)}; " +
                        "durasi animator ${describeScale(animatorScale)}."
                )
            } catch (_: Exception) {
                showMessage("Maaf, pengaturan skala animasi tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        // Membaca skala ukuran teks sistem Android.
        if (
            text == "ukuran teks hp" ||
            text == "ukuran teks ponsel" ||
            text == "ukuran teks perangkat" ||
            text == "besar tulisan hp" ||
            text == "besar huruf hp" ||
            text == "skala font android" ||
            text == "skala tulisan hp" ||
            text == "font scale" ||
            text == "ukuran huruf android"
        ) {
            try {
                val fontScale = Settings.System.getFloat(
                    contentResolver,
                    Settings.System.FONT_SCALE,
                    1.0f
                )
                val percent = (fontScale * 100f).toInt()
                val description = when {
                    fontScale > 1.05f -> "lebih besar dari ukuran standar"
                    fontScale < 0.95f -> "lebih kecil dari ukuran standar"
                    else -> "ukuran standar"
                }
                showMessage("Ukuran teks sistem saat ini $percent persen, $description.")
            } catch (_: Exception) {
                showMessage("Maaf, ukuran teks sistem tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        // Memeriksa status opsi pengembang dan USB debugging Android.
        if (
            text == "status mode developer" ||
            text == "mode developer hp" ||
            text == "opsi pengembang hp" ||
            text == "usb debugging hp" ||
            text == "debugging usb hp" ||
            text == "status usb debugging" ||
            text == "apakah usb debugging aktif"
        ) {
            try {
                val developerEnabled = Settings.Global.getInt(
                    contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
                ) == 1
                val adbEnabled = Settings.Global.getInt(
                    contentResolver, Settings.Global.ADB_ENABLED, 0
                ) == 1
                val developerText = if (developerEnabled) "aktif" else "nonaktif"
                val adbText = if (adbEnabled) "aktif" else "nonaktif"
                showMessage(
                    "Opsi pengembang: $developerText. USB debugging: $adbText."
                )
            } catch (_: Exception) {
                showMessage("Maaf, status opsi pengembang dan USB debugging tidak dapat dibaca.")
            }
            return
        }

        // Informasi arsitektur CPU dan platform perangkat yang dilaporkan Android.
        if (
            text == "arsitektur cpu hp" ||
            text == "arsitektur prosesor hp" ||
            text == "tipe cpu hp" ||
            text == "jenis prosesor hp" ||
            text == "arsitektur perangkat" ||
            text == "cpu architecture" ||
            text == "abi perangkat"
        ) {
            try {
                val supportedAbis = Build.SUPPORTED_ABIS
                    .filter { it.isNotBlank() }
                    .joinToString(", ")
                val hardware = Build.HARDWARE.orEmpty().ifBlank { "tidak dilaporkan" }
                val board = Build.BOARD.orEmpty().ifBlank { "tidak dilaporkan" }
                showMessage(
                    "Arsitektur aplikasi yang didukung perangkat: ${supportedAbis.ifBlank { "tidak tersedia" }}. " +
                        "Platform hardware: $hardware. Board: $board."
                )
            } catch (_: Exception) {
                showMessage("Maaf, informasi arsitektur CPU perangkat tidak dapat dibaca.")
            }
            return
        }

        // Informasi nomor build/fingerprint sistem Android untuk diagnosis perangkat.
        if (
            text.contains("nomor build android") ||
            text.contains("nomor versi build") ||
            text.contains("build fingerprint") ||
            text.contains("fingerprint perangkat") ||
            text.contains("versi build hp")
        ) {
            try {
                val buildId = Build.DISPLAY.orEmpty().trim()
                val buildNumber = Build.ID.orEmpty().trim()
                val fingerprint = Build.FINGERPRINT.orEmpty().trim()
                val answer = buildString {
                    if (buildId.isNotBlank()) append("Versi build: $buildId. ")
                    if (buildNumber.isNotBlank()) append("ID build: $buildNumber. ")
                    if (fingerprint.isNotBlank()) append("Fingerprint: $fingerprint.")
                }.trim()
                if (answer.isNotBlank()) {
                    showMessage(answer)
                } else {
                    showMessage("Informasi nomor build tidak tersedia pada perangkat ini.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, informasi nomor build Android tidak dapat dibaca.")
            }
            return
        }

        // Informasi bahasa sistem dan zona waktu perangkat.
        if (
            text == "bahasa hp" ||
            text == "bahasa ponsel" ||
            text == "bahasa perangkat" ||
            text == "bahasa sistem" ||
            text == "bahasa android" ||
            text == "language hp" ||
            text == "bahasa yang digunakan hp" ||
            text == "zona waktu hp" ||
            text == "zona waktu ponsel" ||
            text == "zona waktu perangkat" ||
            text == "zona waktu android" ||
            text == "timezone hp" ||
            text == "timezone perangkat" ||
            text == "zona waktu"
        ) {
            try {
                val locale = java.util.Locale.getDefault()
                val timeZone = java.util.TimeZone.getDefault()
                val asksLanguage = text.contains("bahasa") || text == "language hp"
                val answer = if (asksLanguage) {
                    val language = locale.getDisplayLanguage(java.util.Locale("id", "ID"))
                    val country = locale.getDisplayCountry(java.util.Locale("id", "ID"))
                    val languageTag = locale.toLanguageTag()
                    buildString {
                        append("Bahasa sistem perangkat: ")
                        append(if (language.isNotBlank()) language else languageTag)
                        if (country.isNotBlank()) append(" ($country)")
                        append(". Kode bahasa: $languageTag.")
                    }
                } else {
                    val zoneName = timeZone.getDisplayName(
                        timeZone.inDaylightTime(java.util.Date()),
                        java.util.TimeZone.LONG,
                        java.util.Locale("id", "ID")
                    )
                    val offsetMinutes = timeZone.getOffset(System.currentTimeMillis()) / 60000
                    val sign = if (offsetMinutes >= 0) "+" else "-"
                    val absoluteOffset = kotlin.math.abs(offsetMinutes)
                    val offsetHours = absoluteOffset / 60
                    val remainingMinutes = absoluteOffset % 60
                    val offset = "UTC$sign%02d:%02d".format(
                        java.util.Locale.US, offsetHours, remainingMinutes
                    )
                    "Zona waktu perangkat: ${timeZone.id}, $zoneName ($offset)."
                }
                showMessage(answer)
            } catch (_: Exception) {
                showMessage("Maaf, informasi bahasa atau zona waktu perangkat tidak dapat dibaca.")
            }
            return
        }

        // Membaca tanggal patch keamanan Android yang dilaporkan sistem.
        if (
            text.contains("patch keamanan android") ||
            text.contains("tanggal patch keamanan") ||
            text.contains("tingkat patch keamanan") ||
            text.contains("security patch android") ||
            text.contains("patch keamanan hp")
        ) {
            try {
                val patchLevel = Build.VERSION.SECURITY_PATCH.orEmpty().trim()
                if (patchLevel.isNotBlank()) {
                    showMessage("Patch keamanan Android perangkat ini: $patchLevel.")
                } else {
                    showMessage("Informasi patch keamanan tidak tersedia pada perangkat ini.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, tanggal patch keamanan Android tidak dapat dibaca.")
            }
            return
        }

        // Jumlah boot/restart yang dicatat Android sejak reset pabrik.
        if (
            text.contains("berapa kali hp restart") ||
            text.contains("berapa kali ponsel restart") ||
            text.contains("jumlah restart perangkat") ||
            text.contains("jumlah restart hp") ||
            text.contains("berapa kali hp dinyalakan") ||
            text.contains("boot count") ||
            text.contains("jumlah boot perangkat")
        ) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val bootCount = Settings.Global.getInt(
                        contentResolver,
                        Settings.Global.BOOT_COUNT,
                        -1
                    )
                    if (bootCount >= 0) {
                        showMessage("Android mencatat perangkat sudah melakukan boot atau restart sebanyak $bootCount kali sejak reset pabrik.")
                    } else {
                        showMessage("Informasi jumlah restart tidak tersedia pada perangkat ini.")
                    }
                } else {
                    showMessage("Jumlah restart memerlukan Android 7.0 atau lebih baru.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, jumlah restart perangkat tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        // Lama perangkat menyala sejak boot/restart terakhir.
        if (
            text.contains("lama hp menyala") ||
            text.contains("lama ponsel menyala") ||
            text.contains("waktu sejak restart") ||
            text.contains("waktu sejak dinyalakan") ||
            text.contains("uptime perangkat") ||
            text.contains("berapa lama hp hidup") ||
            text.contains("sudah berapa lama hp")
        ) {
            try {
                val elapsed = android.os.SystemClock.elapsedRealtime()
                val totalMinutes = elapsed / 60000L
                val days = totalMinutes / 1440L
                val hours = (totalMinutes % 1440L) / 60L
                val minutes = totalMinutes % 60L
                val durationText = buildString {
                    if (days > 0L) append("$days hari ")
                    if (hours > 0L) append("$hours jam ")
                    append("$minutes menit")
                }.trim()
                showMessage("Perangkat sudah menyala selama $durationText sejak terakhir dinyalakan atau dimulai ulang.")
            } catch (_: Exception) {
                showMessage("Maaf, lama waktu perangkat menyala tidak dapat dibaca.")
            }
            return
        }

        // Membaca teknologi/kimia baterai yang dilaporkan Android.
        if (
            text.contains("jenis baterai") ||
            text.contains("teknologi baterai") ||
            text.contains("tipe baterai") ||
            text.contains("bahan baterai") ||
            text.contains("battery technology") ||
            text.contains("battery type")
        ) {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
                )
                val technology = batteryIntent?.getStringExtra(
                    android.os.BatteryManager.EXTRA_TECHNOLOGY
                )?.trim().orEmpty()
                if (technology.isNotBlank()) {
                    showMessage("Teknologi baterai yang dilaporkan perangkat adalah $technology.")
                } else {
                    showMessage("Informasi teknologi atau jenis baterai tidak disediakan oleh perangkat.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, informasi jenis baterai tidak dapat dibaca.")
            }
            return
        }

        // Informasi baterai perangkat, dibaca langsung dari status Android.
        if (text.contains("baterai") || text.contains("battery")) {
            try {
                val batteryIntent = registerReceiver(
                    null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )
                if (batteryIntent == null) {
                    showMessage("Informasi baterai belum tersedia.")
                } else {
                    val level = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_LEVEL, -1
                    )
                    val scale = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_SCALE, -1
                    )
                    val status = batteryIntent.getIntExtra(
                        android.os.BatteryManager.EXTRA_STATUS,
                        android.os.BatteryManager.BATTERY_STATUS_UNKNOWN
                    )
                    if (level < 0 || scale <= 0) {
                        showMessage("Persentase baterai belum dapat dibaca.")
                    } else {
                        val percent = (level * 100f / scale).toInt()
                        val chargingText = when (status) {
                            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "sedang mengisi daya"
                            android.os.BatteryManager.BATTERY_STATUS_FULL -> "sudah terisi penuh"
                            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "terhubung ke pengisi daya tetapi tidak sedang mengisi"
                            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "sedang menggunakan daya baterai"
                            else -> "status pengisian tidak diketahui"
                        }
                        showMessage("Baterai tersisa $percent persen, $chargingText.")
                    }
                }
            } catch (_: Exception) {
                showMessage("Maaf, informasi baterai tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("status senter") ||
            text.contains("cek senter") ||
            text.contains("senter menyala") ||
            text.contains("senter mati") ||
            text.contains("status flashlight")
        ) {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
                showMessage("Pemeriksaan status senter memerlukan Android 6 atau lebih baru.")
                return
            }
            if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                showMessage("Izin kamera diperlukan Android untuk memeriksa status senter.")
                return
            }
            try {
                val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull {
                    cameraManager.getCameraCharacteristics(it).get(
                        CameraCharacteristics.FLASH_INFO_AVAILABLE
                    ) == true
                }
                if (cameraId == null) {
                    showMessage("Senter tidak tersedia pada perangkat ini.")
                    return
                }
                val completed = java.util.concurrent.atomic.AtomicBoolean(false)
                val callback = object : CameraManager.TorchCallback() {
                    override fun onTorchModeChanged(id: String, enabled: Boolean) {
                        if (id == cameraId && completed.compareAndSet(false, true)) {
                            cameraManager.unregisterTorchCallback(this)
                            showMessage(if (enabled) "Senter sedang menyala." else "Senter sedang mati.")
                        }
                    }

                    override fun onTorchModeUnavailable(id: String) {
                        if (id == cameraId && completed.compareAndSet(false, true)) {
                            cameraManager.unregisterTorchCallback(this)
                            showMessage("Status senter sedang tidak tersedia, mungkin sedang digunakan kamera.")
                        }
                    }
                }
                cameraManager.registerTorchCallback(callback, handler)
                handler.postDelayed({
                    if (completed.compareAndSet(false, true)) {
                        try {
                            cameraManager.unregisterTorchCallback(callback)
                        } catch (_: Exception) {
                        }
                        showMessage("Status senter tidak berhasil dibaca.")
                    }
                }, 1500L)
            } catch (_: Exception) {
                showMessage("Status senter tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        if (text.contains("senter") ||
            text.contains("flashlight") ||
            text.contains("flash")
        ) {
            when {
                text.contains("matikan") ||
                text.contains("nonaktifkan") ||
                text.contains("padamkan") ||
                text.contains("turn off") ||
                text.contains("switch off") -> controlTorch(false)

                text.contains("nyalakan") ||
                text.contains("hidupkan") ||
                text.contains("aktifkan") ||
                text.contains("turn on") ||
                text.contains("switch on") -> controlTorch(true)

                else -> showMessage("Ucapkan nyalakan senter atau matikan senter")
            }
            return
        }

        if (text.contains("wifi") || text.contains("wi-fi")) {
            openSettings(Settings.ACTION_WIFI_SETTINGS)
            return
        }

        if (text.contains("status bluetooth") ||
            text.contains("cek bluetooth") ||
            text.contains("bluetooth aktif") ||
            text.contains("bluetooth menyala") ||
            text.contains("bluetooth mati") ||
            text.contains("bluetooth nonaktif")
        ) {
            try {
                val bluetoothState = android.provider.Settings.Global.getInt(
                    contentResolver,
                    android.provider.Settings.Global.BLUETOOTH_ON,
                    0
                )
                if (bluetoothState == 1) {
                    showMessage("Bluetooth sedang aktif.")
                } else {
                    showMessage("Bluetooth sedang nonaktif.")
                }
            } catch (_: Exception) {
                showMessage("Status Bluetooth tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        if (text.contains("bluetooth")) {
            openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            return
        }

        if (text.contains("hotspot") ||
            text.contains("hot spot") ||
            text.contains("tethering")
        ) {
            openHotspotSettings()
            return
        }

        if (text.contains("volume") || text.contains("suara")) {
            val audio = getSystemService(AUDIO_SERVICE) as AudioManager
            val requestedVolume = Regex("(\\d{1,3})\\s*(?:persen|%)")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()

            when {
                requestedVolume != null -> {
                    val maxLevel = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val targetLevel = (maxLevel * requestedVolume.coerceIn(0, 100) / 100.0)
                        .toInt().coerceIn(0, maxLevel)
                    audio.setStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        targetLevel,
                        AudioManager.FLAG_SHOW_UI
                    )
                    showMessage("Volume diatur ke ${requestedVolume.coerceIn(0, 100)} persen")
                }

                text.contains("naik") || text.contains("besar") ||
                text.contains("keras") || text.contains("tambah") ||
                text.contains("up") || text.contains("increase") ||
                text.contains("louder") ->
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )

                text.contains("turun") || text.contains("kecil") ||
                text.contains("kurangi") || text.contains("down") ||
                text.contains("decrease") || text.contains("lower") ->
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )

                text.contains("diam") || text.contains("mute") ||
                text.contains("silence") || text.contains("matikan") ->
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                else -> showMessage("Ucapkan naikkan volume, turunkan volume, atau matikan suara")
            }
            return
        }

        if (text.contains("kecerahan") ||
            text.contains("brightness") ||
            (text.contains("layar") &&
                (text.contains("terang") || text.contains("redup") ||
                 text.contains("naik") || text.contains("turun")))
        ) {
            val requestedBrightness = Regex("(\\d{1,3})\\s*(?:persen|%)")
                .find(text)?.groupValues?.get(1)?.toIntOrNull()

            when {
                requestedBrightness != null -> setScreenBrightnessPercent(requestedBrightness)

                text.contains("naik") || text.contains("tambah") ||
                text.contains("terang") || text.contains("increase") ||
                text.contains("up") -> adjustScreenBrightness(30)

                text.contains("turun") || text.contains("kurangi") ||
                text.contains("redup") || text.contains("decrease") ||
                text.contains("down") -> adjustScreenBrightness(-30)

                else -> openSettings(Settings.ACTION_DISPLAY_SETTINGS)
            }
            return
        }

        // Pengingat suara memakai timer atau alarm bawaan Android.
        if (text.contains("ingatkan") ||
            text.contains("pengingat") ||
            text.contains("reminder")
        ) {
            val reminderDurationPattern = Regex(
                "(\\d+)\\s*(detik|det|seconds?|secs?|menit|mnt|minutes?|jam|hours?|hr)",
                RegexOption.IGNORE_CASE
            )
            var reminderSeconds = 0L
            reminderDurationPattern.findAll(text).forEach { match ->
                val amount = match.groupValues[1].toLongOrNull() ?: 0L
                val unit = match.groupValues[2].lowercase(Locale.ROOT)
                val multiplier = when {
                    unit.startsWith("det") || unit.startsWith("sec") -> 1L
                    unit.startsWith("men") || unit.startsWith("mnt") || unit.startsWith("min") -> 60L
                    else -> 3600L
                }
                reminderSeconds += amount * multiplier
            }

            val reminderLabel = text
                .replace(Regex("\\b(ingatkan saya|ingatkan|pengingat|reminder|tolong|nanti|lagi|untuk|supaya|agar|besok|lusa|hari ini)\\b"), " ")
                .replace(reminderDurationPattern, " ")
                .replace(Regex("(?:jam|pukul)\\s*\\d{1,2}[:.]\\d{2}"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { "Pengingat dari M Asisten" }

            val hasCalendarDay = text.contains("besok") ||
                text.contains("lusa") || text.contains("hari ini")

            when {
                reminderSeconds in 1L..604800L && !hasCalendarDay -> {
                    val reminderIntent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, reminderSeconds.toInt())
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, reminderLabel)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    launchIntent(reminderIntent)
                    showMessage("Membuka timer pengingat: $reminderLabel. Mulai timer pada aplikasi Jam agar pengingat berjalan.")
                }

                reminderSeconds > 604800L -> {
                    showMessage("Durasi pengingat maksimal tujuh hari.")
                }

                else -> {
                    val reminderTime = Regex(
                        "(?:jam|pukul)\\s*(\\d{1,2})[:.](\\d{2})"
                    ).find(text)
                    if (reminderTime != null) {
                        val hour = reminderTime.groupValues[1].toIntOrNull()
                        val minute = reminderTime.groupValues[2].toIntOrNull()
                        if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                            if (hasCalendarDay) {
                                val eventStart = java.util.Calendar.getInstance().apply {
                                    when {
                                        text.contains("lusa") -> add(java.util.Calendar.DAY_OF_YEAR, 2)
                                        text.contains("besok") -> add(java.util.Calendar.DAY_OF_YEAR, 1)
                                    }
                                    set(java.util.Calendar.HOUR_OF_DAY, hour)
                                    set(java.util.Calendar.MINUTE, minute)
                                    set(java.util.Calendar.SECOND, 0)
                                    set(java.util.Calendar.MILLISECOND, 0)
                                }.timeInMillis
                                val eventIntent = Intent(
                                    Intent.ACTION_INSERT,
                                    android.provider.CalendarContract.Events.CONTENT_URI
                                ).apply {
                                    putExtra(android.provider.CalendarContract.Events.TITLE, reminderLabel)
                                    putExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, eventStart)
                                }
                                launchIntent(eventIntent)
                                val dayLabel = when {
                                    text.contains("lusa") -> "lusa"
                                    text.contains("besok") -> "besok"
                                    else -> "hari ini"
                                }
                                showMessage("Membuka acara kalender untuk $dayLabel pukul %02d:%02d: %s. Atur notifikasi pengingat lalu simpan acara.".format(hour, minute, reminderLabel))
                            } else {
                                val reminderIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                                    putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                                    putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, reminderLabel)
                                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                                }
                                launchIntent(reminderIntent)
                                showMessage("Membuka alarm pengingat pukul %02d:%02d: %s. Simpan alarm pada aplikasi Jam.".format(hour, minute, reminderLabel))
                            }
                        } else {
                            showMessage("Waktu pengingat tidak valid. Gunakan format jam 07:30.")
                        }
                    } else {
                        showMessage("Sebutkan waktunya, misalnya ingatkan saya 10 menit lagi untuk minum, atau ingatkan saya jam 07:30 untuk rapat.")
                    }
                }
            }
            return
        }

        // Timer dan hitung mundur memakai aplikasi Jam bawaan Android.
        if (text.contains("timer") ||
            text.contains("hitung mundur") ||
            text.contains("countdown") ||
            text.contains("penghitung waktu")
        ) {
            val durationPattern = Regex(
                "(\\d+)\\s*(detik|det|seconds?|secs?|menit|mnt|minutes?|jam|hours?|hr)",
                RegexOption.IGNORE_CASE
            )
            var totalSeconds = 0L
            durationPattern.findAll(text).forEach { match ->
                val amount = match.groupValues[1].toLongOrNull() ?: 0L
                val unit = match.groupValues[2].lowercase(Locale.ROOT)
                val multiplier = when {
                    unit.startsWith("det") || unit.startsWith("sec") -> 1L
                    unit.startsWith("men") || unit.startsWith("mnt") || unit.startsWith("min") -> 60L
                    else -> 3600L
                }
                totalSeconds += amount * multiplier
            }

            if (totalSeconds <= 0L) {
                launchIntent(Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS))
                showMessage("Membuka daftar timer. Ucapkan timer 5 menit atau hitung mundur 30 detik untuk membuat timer.")
            } else if (totalSeconds > 604800L) {
                showMessage("Durasi timer maksimal tujuh hari.")
            } else {
                val timerIntent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(android.provider.AlarmClock.EXTRA_LENGTH, totalSeconds.toInt())
                    putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Timer dari M Asisten")
                    putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                }
                launchIntent(timerIntent)
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                val seconds = totalSeconds % 60
                val durationLabel = buildList {
                    if (hours > 0) add("$hours jam")
                    if (minutes > 0) add("$minutes menit")
                    if (seconds > 0) add("$seconds detik")
                }.joinToString(" ")
                showMessage("Membuka timer $durationLabel. Mulai atau simpan timer pada aplikasi Jam.")
            }
            return
        }

        if (text.contains("alarm") || text.contains("jam weker")) {
            val alarmTime = Regex(
                "(?:jam|pukul)\\s*(\\d{1,2})[:.](\\d{2})"
            ).find(text)
            if (alarmTime != null) {
                val hour = alarmTime.groupValues[1].toIntOrNull()
                val minute = alarmTime.groupValues[2].toIntOrNull()
                if (hour != null && minute != null && hour in 0..23 && minute in 0..59) {
                    val alarmIntent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Alarm dari M Asisten")
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    launchIntent(alarmIntent)
                    showMessage("Membuka pengaturan alarm pukul %02d:%02d. Simpan alarm pada aplikasi Jam.".format(hour, minute))
                } else {
                    showMessage("Waktu alarm tidak valid. Gunakan format jam 06:30.")
                }
            } else {
                launchIntent(Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS))
            }
            return
        }

        if (text.contains("kalender") || text.contains("calendar")) {
            val wantsNewEvent = text.contains("buat") ||
                text.contains("tambah") || text.contains("jadwalkan") ||
                text.contains("catat acara") || text.contains("buat acara")
            if (wantsNewEvent) {
                val eventTitle = text
                    .replace(Regex("\\b(kalender|calendar|buat|tambah|jadwalkan|acara|event|catat)\\b"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .ifBlank { "Acara baru" }
                val createEventIntent = Intent(
                    Intent.ACTION_INSERT,
                    android.provider.CalendarContract.Events.CONTENT_URI
                ).apply {
                    putExtra(android.provider.CalendarContract.Events.TITLE, eventTitle)
                }
                launchIntent(createEventIntent)
                showMessage("Membuka formulir kalender untuk acara: $eventTitle. Lengkapi tanggal dan waktu lalu simpan.")
            } else {
                val calendarIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_CALENDAR)
                }
                launchIntent(calendarIntent)
            }
            return
        }

        if (text.contains("kontak") || text.contains("contacts") || text == "contact") {
            val contactsIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_CONTACTS)
            }
            launchIntent(contactsIntent)
            return
        }

        if (text.contains("kalkulator") || text.contains("calculator") ||
            text.contains("hitung") || text.startsWith("berapa ") ||
            text.startsWith("hasil ")
        ) {
            val calculation = Regex(
                "(\\d+)\\s*(tambah|plus|\\+|dikurangi|kurang|minus|kali|dikali|x|bagi|dibagi)\\s*(\\d+)"
            ).find(text)

            if (calculation != null) {
                val first = calculation.groupValues[1].toDoubleOrNull()
                val operation = calculation.groupValues[2]
                val second = calculation.groupValues[3].toDoubleOrNull()

                if (first == null || second == null) {
                    showMessage("Angka perhitungan tidak terbaca.")
                } else if ((operation == "bagi" || operation == "dibagi") && second == 0.0) {
                    showMessage("Tidak bisa membagi dengan nol.")
                } else {
                    val result = when (operation) {
                        "tambah", "plus", "+" -> first + second
                        "dikurangi", "kurang", "minus" -> first - second
                        "kali", "dikali", "x" -> first * second
                        else -> first / second
                    }
                    if (!result.isFinite()) {
                        showMessage("Hasil perhitungan terlalu besar.")
                    } else {
                        val formatted = java.text.DecimalFormat(
                            "#,##0.########",
                            java.text.DecimalFormatSymbols(Locale("id", "ID"))
                        ).format(result)
                        showMessage("Hasilnya $formatted")
                    }
                }
            } else if (!openAppByName("kalkulator", "calculator")) {
                showMessage("Aplikasi kalkulator tidak ditemukan")
            }
            return
        }

        if (text.contains("youtube")) {
            if (!openAppByName("youtube")) {
                showMessage("Aplikasi YouTube tidak ditemukan")
            }
            return
        }

        if (text.contains("browser") || text.contains("peramban") || text.contains("internet") || text == "chrome") {
            val browserIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_APP_BROWSER)
            }
            launchIntent(browserIntent)
            return
        }

        if (text.contains("peta") || text.contains("maps") || text.contains("navigasi") || text.contains("google map")) {
            launchIntent(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=")))
            return
        }

        if (text.contains("menu utama") ||
            text.contains("main screen") ||
            text.contains("home screen") ||
            text == "home" ||
            text.contains("layar utama") ||
            text.contains("halaman utama") ||
            text == "home"
        ) {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
            return
        }

        if (text.contains("video") || text.contains("rekam") || text.contains("record")) {
            openVideoRecorder()
            return
        }

        if (text.contains("kamera") || text.contains("camera") || text == "foto" || text.contains("photo")) {
            openCameraApp()
            return
        }

        if (text.contains("galeri") || text.contains("album") || text.contains("gallery")) {
            if (!openAppByName("galeri", "gallery", "photos", "foto")) {
                launchIntent(
                    Intent(Intent.ACTION_VIEW).apply {
                        type = "image/*"
                    }
                )
            }
            return
        }

        // Panggilan suara selalu membuka dialer dengan nomor terisi.
        // Pengguna tetap menekan tombol panggil sendiri; aplikasi tidak menelepon otomatis.
        val phoneNumberMatch = Regex("(?:\\+?\\d[\\d\\s().-]{6,}\\d)")
            .find(command.trim())
        val spokenPhoneNumber = phoneNumberMatch?.value
            ?.replace(Regex("[^+0-9]"), "")
            ?.takeIf { it.count { ch -> ch.isDigit() } in 8..15 }

        if (text.contains("telepon") ||
            text.contains("panggilan") ||
            text.contains("dialer") ||
            text.contains("phone") ||
            text.contains("call") ||
            text.contains("panggil")
        ) {
            val dialIntent = if (spokenPhoneNumber != null) {
                Intent(Intent.ACTION_DIAL, Uri.parse("tel:$spokenPhoneNumber"))
            } else {
                Intent(Intent.ACTION_DIAL)
            }
            launchIntent(dialIntent)
            return
        }

        // SMS disiapkan sebagai draf. Tidak dikirim otomatis tanpa pemeriksaan pengguna.
        val smsDraftMatch = Regex(
            "(?i)^(?:tolong\\s+)?(?:kirim|buat)\\s+(?:sms|pesan)\\s+(?:ke|kepada)\\s+(\\+?[0-9][0-9\\s().-]{6,}[0-9])\\s+(?:isi|dengan pesan|pesan)\\s+(.+)$"
        ).find(command.trim())
        if (smsDraftMatch != null) {
            val recipient = smsDraftMatch.groupValues[1].replace(Regex("[^+0-9]"), "")
            val messageBody = smsDraftMatch.groupValues[2].trim()
            val digitCount = recipient.count { it.isDigit() }
            if (digitCount in 8..15 && messageBody.isNotBlank()) {
                val smsIntent = Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:$recipient")
                ).apply {
                    putExtra("sms_body", messageBody)
                }
                launchIntent(smsIntent)
                showMessage("Membuka aplikasi pesan dengan draf SMS. Periksa nomor dan isi pesan sebelum mengirim.")
            } else {
                showMessage("Nomor atau isi SMS belum lengkap. Ucapkan: kirim SMS ke nomor, isi pesan.")
            }
            return
        }

        if (text == "pesan" ||
            text.contains("messages") ||
            text.contains("message") ||
            text.contains("sms") ||
            text.contains("pesan")
        ) {
            openMessagingApp()
            return
        }

        // Membuka halaman pengaturan suara dan audio Android.
        if (
            text == "buka pengaturan suara" ||
            text == "pengaturan suara hp" ||
            text == "atur pengaturan suara" ||
            text == "buka pengaturan audio" ||
            text == "pengaturan audio hp" ||
            text == "atur nada dering hp"
        ) {
            openSettings(Settings.ACTION_SOUND_SETTINGS)
            return
        }

        // Membuka halaman info aplikasi M Launcher Voice untuk mengelola izin dan penyimpanan.
        if (
            text == "buka info aplikasi m" ||
            text == "info aplikasi m launcher voice" ||
            text == "pengaturan aplikasi m" ||
            text == "izin aplikasi m launcher voice" ||
            text == "kelola izin aplikasi ini" ||
            text == "buka detail aplikasi ini"
        ) {
            try {
                val appDetailsIntent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(appDetailsIntent)
            } catch (_: Exception) {
                showMessage("Halaman informasi aplikasi tidak dapat dibuka.")
            }
            return
        }

        // Membuka pengaturan notifikasi khusus M Launcher Voice.
        if (
            text == "buka pengaturan notifikasi aplikasi m" ||
            text == "pengaturan notifikasi m launcher voice" ||
            text == "atur notifikasi aplikasi ini" ||
            text == "izin notifikasi aplikasi m" ||
            text == "pengaturan pemberitahuan aplikasi m"
        ) {
            try {
                val notificationIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                } else {
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:$packageName")
                    )
                }
                notificationIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(notificationIntent)
            } catch (_: Exception) {
                showMessage("Pengaturan notifikasi aplikasi tidak dapat dibuka.")
            }
            return
        }

        // Membuka halaman pengaturan tampilan layar Android.
        if (
            text == "buka pengaturan tampilan" ||
            text == "pengaturan tampilan hp" ||
            text == "buka pengaturan layar" ||
            text == "atur tampilan hp" ||
            text == "pengaturan display hp"
        ) {
            openSettings(Settings.ACTION_DISPLAY_SETTINGS)
            return
        }

        // Membuka halaman pengelolaan aplikasi yang terpasang pada perangkat.
        if (
            text == "buka kelola aplikasi" ||
            text == "pengelolaan aplikasi hp" ||
            text == "manajemen aplikasi hp" ||
            text == "atur aplikasi terpasang" ||
            text == "pengaturan aplikasi terpasang" ||
            text == "buka daftar aplikasi terpasang"
        ) {
            openSettings(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS)
            return
        }

        // Membuka halaman pengaturan keamanan Android.
        if (
            text == "buka pengaturan keamanan" ||
            text == "pengaturan keamanan hp" ||
            text == "atur keamanan perangkat" ||
            text == "buka keamanan android" ||
            text == "pengaturan security hp"
        ) {
            openSettings(Settings.ACTION_SECURITY_SETTINGS)
            return
        }

        // Membuka pengaturan akses penggunaan aplikasi Android.
        if (
            text == "buka akses penggunaan aplikasi" ||
            text == "pengaturan akses penggunaan" ||
            text == "izin akses penggunaan aplikasi" ||
            text == "atur akses penggunaan hp" ||
            text == "buka usage access" ||
            text == "pengaturan usage access"
        ) {
            openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            return
        }

        // Membuka halaman penggunaan data seluler dan Wi-Fi Android.
        if (
            text == "buka penggunaan data" ||
            text == "pengaturan penggunaan data" ||
            text == "cek pemakaian data internet" ||
            text == "pengaturan data seluler" ||
            text == "buka penggunaan data seluler" ||
            text == "atur pemakaian kuota internet" ||
            text == "cek penggunaan kuota"
        ) {
            openSettings(Settings.ACTION_DATA_USAGE_SETTINGS)
            return
        }

        // Membuka halaman pengaturan privasi Android jika didukung versi sistem.
        if (
            text == "buka pengaturan privasi" ||
            text == "pengaturan privasi hp" ||
            text == "atur privasi perangkat" ||
            text == "buka privasi android" ||
            text == "pengaturan privacy hp"
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                openSettings(Settings.ACTION_PRIVACY_SETTINGS)
            } else {
                openSettings(Settings.ACTION_SETTINGS)
            }
            return
        }

        // Membuka pengaturan tanggal dan waktu Android.
        if (
            text == "buka pengaturan tanggal dan waktu" ||
            text == "pengaturan tanggal hp" ||
            text == "pengaturan waktu hp" ||
            text == "atur tanggal dan jam" ||
            text == "ubah jam dan tanggal hp" ||
            text == "buka pengaturan jam"
        ) {
            openSettings(Settings.ACTION_DATE_SETTINGS)
            return
        }

        // Membuka halaman pengaturan penyimpanan internal Android.
        if (
            text == "buka pengaturan penyimpanan" ||
            text == "pengaturan penyimpanan hp" ||
            text == "kelola penyimpanan perangkat" ||
            text == "buka storage hp" ||
            text == "atur ruang penyimpanan"
        ) {
            openSettings(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            return
        }

        // Membuka halaman pengaturan NFC Android.
        if (
            text == "buka pengaturan nfc" ||
            text == "pengaturan nfc hp" ||
            text == "atur nfc perangkat" ||
            text == "buka pengaturan pembayaran nfc" ||
            text == "pengaturan near field communication"
        ) {
            openSettings(Settings.ACTION_NFC_SETTINGS)
            return
        }

        // Membuka halaman Opsi Pengembang Android.
        if (
            text == "buka opsi pengembang" ||
            text == "buka pengaturan developer" ||
            text == "pengaturan developer hp" ||
            text == "developer options hp" ||
            text == "buka mode developer"
        ) {
            openSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            return
        }

        // Membuka halaman Tentang Ponsel atau informasi perangkat di pengaturan Android.
        if (
            text == "buka tentang ponsel" ||
            text == "pengaturan tentang ponsel" ||
            text == "buka info perangkat" ||
            text == "tentang hp" ||
            text == "about phone" ||
            text == "informasi perangkat di pengaturan"
        ) {
            openSettings(Settings.ACTION_DEVICE_INFO_SETTINGS)
            return
        }

        if (text.contains("pengaturan") || text.contains("setelan") || text.contains("settings")) {
            openSettings(Settings.ACTION_SETTINGS)
            return
        }

        if (text.contains("status izin mikrofon") ||
            text.contains("izin mikrofon") ||
            text.contains("cek mikrofon") ||
            text.contains("akses mikrofon") ||
            text.contains("permission microphone")
        ) {
            try {
                val microphoneGranted =
                    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (microphoneGranted) {
                    showMessage("Izin mikrofon untuk M Asisten sudah diberikan.")
                } else {
                    showMessage("Izin mikrofon untuk M Asisten belum diberikan. Fitur perintah suara memerlukan izin ini.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status izin mikrofon tidak dapat diperiksa.")
            }
            return
        }

        if (text.contains("status izin overlay") ||
            text.contains("status overlay") ||
            text.contains("cek izin overlay") ||
            text.contains("izin tampil di atas aplikasi") ||
            text.contains("izin tampilan di atas aplikasi") ||
            text.contains("izin jendela mengambang") ||
            text.contains("tampilan di atas aplikasi")
        ) {
            try {
                val overlayAllowed = android.provider.Settings.canDrawOverlays(this)
                if (overlayAllowed) {
                    showMessage("Izin tampil di atas aplikasi untuk M Asisten sudah diberikan. Tombol mengambang dapat ditampilkan.")
                } else {
                    showMessage("Izin tampil di atas aplikasi untuk M Asisten belum diberikan. Tombol mengambang memerlukan izin ini.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status izin tampilan di atas aplikasi tidak dapat diperiksa.")
            }
            return
        }

        if (text.contains("status izin pengaturan") ||
            text.contains("status izin ubah pengaturan") ||
            text.contains("cek izin pengaturan sistem") ||
            text.contains("izin kecerahan") ||
            text.contains("izin mengubah kecerahan") ||
            text.contains("write settings") ||
            text.contains("ubah pengaturan sistem")
        ) {
            try {
                val canWriteSettings = android.provider.Settings.System.canWrite(this)
                if (canWriteSettings) {
                    showMessage("Izin mengubah pengaturan sistem untuk M Asisten sudah diberikan. Pengaturan seperti kecerahan layar dapat diubah.")
                } else {
                    showMessage("Izin mengubah pengaturan sistem untuk M Asisten belum diberikan. Fitur pengaturan seperti kecerahan layar memerlukan izin ini.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status izin mengubah pengaturan sistem tidak dapat diperiksa.")
            }
            return
        }

        if (text.contains("status optimasi baterai") ||
            text.contains("optimasi baterai") ||
            text.contains("pengoptimalan baterai") ||
            text.contains("hemat baterai untuk aplikasi") ||
            text.contains("pembatasan baterai") ||
            text.contains("battery optimization") ||
            text.contains("battery optimizations")
        ) {
            try {
                val powerManager = getSystemService(POWER_SERVICE) as android.os.PowerManager
                val ignoringOptimizations = powerManager.isIgnoringBatteryOptimizations(packageName)
                if (ignoringOptimizations) {
                    showMessage("M Asisten dikecualikan dari optimasi baterai. Sistem tidak menerapkan pembatasan optimasi baterai pada aplikasi ini.")
                } else {
                    showMessage("M Asisten masih mengikuti optimasi baterai Android. Sistem dapat membatasi aktivitas latar belakang aplikasi.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, status optimasi baterai tidak dapat diperiksa.")
            }
            return
        }

        if (text.contains("status notifikasi") ||
            text.contains("notifikasi aplikasi") ||
            text.contains("izin notifikasi") ||
            text.contains("cek notifikasi") ||
            text.contains("pemberitahuan aplikasi")
        ) {
            try {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                val notificationsEnabled = notificationManager.areNotificationsEnabled()
                val postPermissionGranted =
                    android.os.Build.VERSION.SDK_INT < 33 ||
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                val message = when {
                    !notificationsEnabled -> "Notifikasi untuk M Asisten sedang diblokir di pengaturan aplikasi."
                    !postPermissionGranted -> "Izin notifikasi M Asisten belum diberikan."
                    else -> "Notifikasi M Asisten diizinkan dan tidak diblokir."
                }
                showMessage(message)
            } catch (_: Exception) {
                showMessage("Maaf, status notifikasi aplikasi tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("kartu sd") ||
            text.contains("memori eksternal") ||
            text.contains("penyimpanan eksternal") ||
            text.contains("cek sd card") ||
            text.contains("sd card")
        ) {
            try {
                val removableStorage = getExternalFilesDirs(null)
                    .filterNotNull()
                    .firstOrNull { android.os.Environment.isExternalStorageRemovable(it) }
                if (removableStorage == null) {
                    showMessage("Kartu SD eksternal tidak terdeteksi pada perangkat ini.")
                } else {
                    val state = android.os.Environment.getExternalStorageState(removableStorage)
                    if (state == android.os.Environment.MEDIA_MOUNTED ||
                        state == android.os.Environment.MEDIA_MOUNTED_READ_ONLY
                    ) {
                        val stat = android.os.StatFs(removableStorage.path)
                        val totalGb = stat.totalBytes / (1024.0 * 1024.0 * 1024.0)
                        val freeGb = stat.availableBytes / (1024.0 * 1024.0 * 1024.0)
                        val totalText = String.format(java.util.Locale("id", "ID"), "%.1f", totalGb)
                        val freeText = String.format(java.util.Locale("id", "ID"), "%.1f", freeGb)
                        showMessage("Kartu SD terdeteksi dan dapat dibaca. Kapasitas sekitar $totalText gigabita, ruang tersedia $freeText gigabita.")
                    } else {
                        showMessage("Slot penyimpanan eksternal terdeteksi, tetapi kartu SD tidak siap digunakan. Status: $state.")
                    }
                }
            } catch (_: Exception) {
                showMessage("Maaf, status kartu SD tidak dapat diperiksa.")
            }
            return
        }

        if (text.contains("sensor hp") ||
            text.contains("sensor perangkat") ||
            text.contains("sensor apa saja") ||
            text.contains("cek sensor") ||
            text.contains("sensor tersedia") ||
            text.contains("daftar sensor")
        ) {
            try {
                val sensorManager = getSystemService(SENSOR_SERVICE) as android.hardware.SensorManager
                val sensorTypes = listOf(
                    android.hardware.Sensor.TYPE_ACCELEROMETER to "akselerometer",
                    android.hardware.Sensor.TYPE_GYROSCOPE to "giroskop",
                    android.hardware.Sensor.TYPE_PROXIMITY to "proximity",
                    android.hardware.Sensor.TYPE_LIGHT to "cahaya",
                    android.hardware.Sensor.TYPE_MAGNETIC_FIELD to "magnetometer",
                    android.hardware.Sensor.TYPE_PRESSURE to "tekanan udara",
                    android.hardware.Sensor.TYPE_ROTATION_VECTOR to "rotasi"
                )
                val availableSensors = sensorTypes
                    .filter { sensorManager.getDefaultSensor(it.first) != null }
                    .map { it.second }
                if (availableSensors.isEmpty()) {
                    showMessage("Tidak ditemukan sensor utama yang didukung pada perangkat ini.")
                } else {
                    showMessage("Sensor yang tersedia: " + availableSensors.joinToString(", ") + ".")
                }
            } catch (_: Exception) {
                showMessage("Maaf, daftar sensor perangkat tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("resolusi layar") ||
            text.contains("cek resolusi") ||
            text.contains("ukuran layar") ||
            text.contains("berapa resolusi") ||
            text.contains("display resolution") ||
            text.contains("pixel layar")
        ) {
            try {
                val displayMetrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                (getSystemService(WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getRealMetrics(displayMetrics)
                val width = displayMetrics.widthPixels
                val height = displayMetrics.heightPixels
                val density = displayMetrics.densityDpi
                showMessage("Resolusi layar perangkat ini $width kali $height piksel, dengan kepadatan $density DPI.")
            } catch (_: Exception) {
                showMessage("Maaf, informasi resolusi layar tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("waktu layar mati") ||
            text.contains("timeout layar") ||
            text.contains("screen timeout") ||
            text.contains("durasi layar aktif") ||
            text.contains("berapa lama layar mati") ||
            text.contains("layar mati setelah")
        ) {
            try {
                val timeoutMs = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_OFF_TIMEOUT,
                    30000
                ).toLong().coerceAtLeast(0L)
                val totalSeconds = timeoutMs / 1000L
                val minutes = totalSeconds / 60L
                val seconds = totalSeconds % 60L
                val durationText = when {
                    minutes > 0 && seconds > 0 -> "$minutes menit $seconds detik"
                    minutes > 0 -> "$minutes menit"
                    else -> "$seconds detik"
                }
                showMessage("Layar diatur mati otomatis setelah $durationText tidak digunakan.")
            } catch (_: Exception) {
                showMessage("Maaf, pengaturan waktu layar mati tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("status getaran") ||
            text.contains("cek getaran") ||
            text.contains("mode getar") ||
            text.contains("getaran perangkat") ||
            text.contains("vibrate mode") ||
            text.contains("status vibrasi")
        ) {
            try {
                val audioManager = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
                val ringerMode = audioManager.ringerMode
                val vibrateSetting = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.VIBRATE_WHEN_RINGING,
                    0
                )
                val description = when (ringerMode) {
                    android.media.AudioManager.RINGER_MODE_SILENT -> "mode senyap aktif"
                    android.media.AudioManager.RINGER_MODE_VIBRATE -> "mode getar aktif"
                    android.media.AudioManager.RINGER_MODE_NORMAL ->
                        if (vibrateSetting == 1) "mode suara aktif dan getar saat dering diaktifkan"
                        else "mode suara aktif dan getar saat dering nonaktif"
                    else -> "mode dering tidak diketahui"
                }
                showMessage("Status suara dan getaran: $description.")
            } catch (_: Exception) {
                showMessage("Maaf, status suara dan getaran tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("status kecerahan layar") ||
            text.contains("cek kecerahan layar") ||
            text.contains("berapa persen kecerahan") ||
            text.contains("tingkat kecerahan layar") ||
            text.contains("brightness layar") ||
            text.contains("status brightness")
        ) {
            try {
                val brightness = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    0
                ).coerceIn(0, 255)
                val percent = kotlin.math.round(brightness * 100.0 / 255.0).toInt()
                val brightnessMode = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                )
                val modeText = if (
                    brightnessMode == android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                ) "mode kecerahan otomatis aktif" else "mode kecerahan manual"
                showMessage("Nilai pengaturan kecerahan layar $percent persen, $modeText.")
            } catch (_: Exception) {
                showMessage("Maaf, pengaturan kecerahan layar tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("refresh rate") ||
            text.contains("refresh layar") ||
            text.contains("berapa hz layar") ||
            text.contains("kecepatan refresh layar") ||
            text.contains("frekuensi layar") ||
            text.contains("hz layar")
        ) {
            try {
                val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
                val refreshRate = windowManager.defaultDisplay.refreshRate
                if (refreshRate > 0f) {
                    val refreshText = String.format(java.util.Locale("id", "ID"), "%.1f", refreshRate)
                    showMessage("Refresh rate layar saat ini sekitar $refreshText hertz.")
                } else {
                    showMessage("Informasi refresh rate layar tidak tersedia.")
                }
            } catch (_: Exception) {
                showMessage("Maaf, refresh rate layar tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("status rotasi layar") ||
            text.contains("cek rotasi layar") ||
            text.contains("rotasi otomatis") ||
            text.contains("putar otomatis") ||
            text.contains("auto rotate") ||
            text.contains("layar berputar otomatis")
        ) {
            try {
                val rotationEnabled = android.provider.Settings.System.getInt(
                    contentResolver,
                    android.provider.Settings.System.ACCELEROMETER_ROTATION,
                    0
                ) == 1
                showMessage(
                    if (rotationEnabled) {
                        "Rotasi otomatis layar sedang aktif."
                    } else {
                        "Rotasi otomatis layar sedang nonaktif. Layar terkunci pada orientasi saat ini."
                    }
                )
            } catch (_: Exception) {
                showMessage("Maaf, status rotasi otomatis layar tidak dapat dibaca.")
            }
            return
        }

        if (text.contains("status lokasi") ||
            text.contains("cek lokasi") ||
            text.contains("status gps") ||
            text.contains("cek gps") ||
            text.contains("gps aktif") ||
            text.contains("gps menyala") ||
            text.contains("lokasi aktif") ||
            text.contains("layanan lokasi")
        ) {
            try {
                val locationManager = getSystemService(
                    android.content.Context.LOCATION_SERVICE
                ) as android.location.LocationManager
                if (locationManager.isLocationEnabled) {
                    showMessage("Layanan lokasi perangkat sedang aktif.")
                } else {
                    showMessage("Layanan lokasi perangkat sedang nonaktif.")
                }
            } catch (_: Exception) {
                showMessage("Status layanan lokasi tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        if (text.contains("lokasi") || text.contains("location")) {
            openSettings(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            return
        }

        if (text.contains("status mode pesawat") ||
            text.contains("cek mode pesawat") ||
            text.contains("mode pesawat aktif") ||
            text.contains("mode pesawat menyala") ||
            text.contains("mode pesawat mati") ||
            text.contains("status airplane") ||
            text.contains("flight mode aktif")
        ) {
            try {
                val airplaneMode = android.provider.Settings.Global.getInt(
                    contentResolver,
                    android.provider.Settings.Global.AIRPLANE_MODE_ON,
                    0
                )
                if (airplaneMode == 1) {
                    showMessage("Mode pesawat sedang aktif.")
                } else {
                    showMessage("Mode pesawat sedang nonaktif.")
                }
            } catch (_: Exception) {
                showMessage("Status mode pesawat tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        if (text.contains("nfc") ||
            text.contains("near field communication") ||
            text.contains("status pembayaran nirsentuh")
        ) {
            try {
                val nfcAdapter = android.nfc.NfcAdapter.getDefaultAdapter(this)
                when {
                    nfcAdapter == null -> showMessage("Perangkat ini tidak memiliki fitur NFC.")
                    nfcAdapter.isEnabled -> showMessage("NFC tersedia dan sedang aktif.")
                    else -> showMessage("NFC tersedia, tetapi sedang nonaktif.")
                }
            } catch (_: Exception) {
                showMessage("Status NFC tidak dapat dibaca pada perangkat ini.")
            }
            return
        }

        if (text.contains("pesawat") || text.contains("airplane") || text.contains("flight mode")) {
            openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS)
            return
        }

        val target = text
            .replace(Regex("\\b(aplikasi|app|program)\\b"), " ")
            .replace(Regex("\\b(jalankan|mulai|tolong|dong|ya|please)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (target.isNotBlank()) {
            // Alias ucapan umum dipetakan ke label aplikasi; pencarian tetap
            // menggunakan PackageManager dan tetap melewati pembatas VIP+.
            val appAliases = mapOf(
                "wa" to arrayOf("WhatsApp"),
                "w a" to arrayOf("WhatsApp"),
                "whatsapp" to arrayOf("WhatsApp"),
                "ig" to arrayOf("Instagram"),
                "instagram" to arrayOf("Instagram"),
                "fb" to arrayOf("Facebook"),
                "facebook" to arrayOf("Facebook"),
                "yt" to arrayOf("YouTube"),
                "youtube" to arrayOf("YouTube"),
                "chrome" to arrayOf("Chrome", "Google Chrome"),
                "maps" to arrayOf("Maps", "Google Maps"),
                "google maps" to arrayOf("Maps", "Google Maps"),
                "gmail" to arrayOf("Gmail"),
                "tiktok" to arrayOf("TikTok"),
                "telegram" to arrayOf("Telegram"),
                "spotify" to arrayOf("Spotify")
            )
            val aliases = appAliases[target] ?: arrayOf(target)
            if (!openAppByName(*aliases)) {
                showMessage("Aplikasi $target tidak ditemukan atau tidak tersedia pada mode ini")
            }
        } else {
            showMessage("Perintah tidak dikenali")
        }
    }

    // Menemukan aplikasi yang benar-benar dapat dibuka pada perangkat.
    // Mode Standar hanya menampilkan aplikasi sistem; VIP+ menampilkan seluruh
    // aplikasi launcher yang terlihat oleh Android Package Visibility.
    private fun showAvailableApps() {
        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcherIntent, 0)
            }

            val available = activities.mapNotNull { info ->
                val targetPackage = info.activityInfo.packageName
                if (targetPackage == packageName ||
                    (!vipMode && !isBuiltInSystemApp(targetPackage))
                ) {
                    null
                } else {
                    info.loadLabel(packageManager).toString().trim()
                }
            }.filter { it.isNotBlank() }
                .distinctBy { it.lowercase(Locale("id", "ID")) }
                .sortedBy { it.lowercase(Locale("id", "ID")) }

            if (available.isEmpty()) {
                showMessage("Belum ada aplikasi yang dapat ditampilkan")
                return
            }

            val visible = available.take(8)
            val remaining = available.size - visible.size
            val modeLabel = if (vipMode) "VIP+" else "Standar"
            val suffix = if (remaining > 0) " dan $remaining aplikasi lainnya" else ""
            showMessage(
                "Aplikasi $modeLabel (${available.size}): " +
                    visible.joinToString(", ") + suffix
            )
        } catch (_: Exception) {
            showMessage("Daftar aplikasi tidak dapat dibaca oleh Android")
        }
    }

    private fun openVideoRecorder() {
        val videoIntent = Intent("android.media.action.VIDEO_CAPTURE").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        launchIntent(videoIntent)
    }

    private fun openCameraApp() {
        val cameraIntent = Intent("android.media.action.STILL_IMAGE_CAMERA").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent("android.media.action.IMAGE_CAPTURE").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            // Lewat pemeriksaan paket agar Standar hanya membuka kamera bawaan.
            val resolved = packageManager.resolveActivity(
                cameraIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            if (resolved != null) {
                launchIntent(cameraIntent)
            } else {
                launchIntent(fallbackIntent)
            }
        } catch (_: Exception) {
            showMessage("Aplikasi kamera tidak dapat dibuka")
        }
    }

    private fun openMessagingApp() {
        val mainIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_APP_MESSAGING)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val fallbackIntent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        try {
            val resolved = packageManager.resolveActivity(
                mainIntent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            if (resolved != null) {
                launchIntent(mainIntent)
            } else {
                launchIntent(fallbackIntent)
            }
        } catch (_: Exception) {
            showMessage("Aplikasi pesan/SMS tidak dapat dibuka")
        }
    }

    private fun openHotspotSettings() {
        val actions = listOf(
            "android.settings.TETHER_SETTINGS",
            "android.settings.WIRELESS_SETTINGS"
        )

        for (action in actions) {
            try {
                startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
             return
            } catch (_: Exception) {
                // Try the next supported settings screen.
            }
        }
        showMessage("Pengaturan hotspot tidak dapat dibuka")
    }

    private fun setScreenBrightnessPercent(percent: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.System.canWrite(this)
        ) {
            showMessage("Izinkan M Asisten mengubah kecerahan layar")
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(permissionIntent)
            } catch (_: Exception) {
                showMessage("Izin perubahan kecerahan tidak dapat dibuka")
            }
            return
        }

        try {
            val safePercent = percent.coerceIn(0, 100)
            val target = (safePercent * 255 / 100.0).toInt().coerceIn(10, 255)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val saved = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                target
            )
            if (saved) {
                showMessage("Kecerahan layar diatur ke $safePercent persen")
            } else {
                showMessage("Kecerahan tidak berhasil diubah")
            }
        } catch (_: Exception) {
            showMessage("Kecerahan tidak dapat diubah. Periksa izin aplikasi.")
        }
    }

    private fun adjustScreenBrightness(change: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.System.canWrite(this)
        ) {
            showMessage("Izinkan M Asisten mengubah kecerahan layar")
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:$packageName")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(permissionIntent)
            } catch (_: Exception) {
                showMessage("Izin perubahan kecerahan tidak dapat dibuka")
            }
            return
        }

        try {
            val current = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            val updated = (current + change).coerceIn(10, 255)
            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            val saved = Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                updated
            )
            if (saved) {
                showMessage(
                    if (change > 0) "Kecerahan layar dinaikkan"
                    else "Kecerahan layar diturunkan"
                )
            } else {
                showMessage("Kecerahan tidak berhasil diubah")
            }
        } catch (_: Exception) {
            showMessage("Kecerahan tidak dapat diubah. Periksa izin aplikasi.")
        }
    }

    private fun openSettings(action: String) {
        try {
            startActivity(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            showMessage("Pengaturan tidak dapat dibuka")
        }
    }

    private fun isBuiltInSystemApp(packageNameToCheck: String): Boolean {
        return try {
            val info = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.getApplicationInfo(
                    packageNameToCheck,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageNameToCheck, 0)
            }
            val systemFlags = android.content.pm.ApplicationInfo.FLAG_SYSTEM or
                android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
            (info.flags and systemFlags) != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun launchVipApp(intent: Intent) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            showMessage("Aplikasi ini khusus VIP+. Hubungkan akun Google dan aktifkan VIP+.")
            return
        }

        fun openVipTarget() {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (_: Exception) {
                showMessage("Aplikasi tidak dapat dibuka")
            }
        }

        fun checkRegularVip() {
            FirebaseFirestore.getInstance()
                .collection("vip_members")
                .document(user.uid)
                .get(com.google.firebase.firestore.Source.SERVER)
                .addOnSuccessListener { member ->
                    val expiresAt = member.getTimestamp("expiresAt")?.toDate()?.time
                    val vipActive = member.getString("membershipStatus") == "ACTIVE" &&
                        (expiresAt == null || expiresAt > System.currentTimeMillis())
                    if (vipActive) openVipTarget()
                    else showMessage("Aplikasi ini khusus VIP+. Aktifkan VIP+ untuk membukanya.")
                }
                .addOnFailureListener {
                    showMessage("Akses VIP+ belum dapat diverifikasi. Periksa koneksi internet.")
                }
        }

        showMessage("Memeriksa akses VIP+...")
        user.getIdToken(true)
            .addOnSuccessListener { token ->
                if (token.claims["admin"] == true) openVipTarget()
                else checkRegularVip()
            }
            .addOnFailureListener { checkRegularVip() }
    }

    private fun launchIntent(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolved = packageManager.resolveActivity(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY
            )
            val targetPackage = resolved?.activityInfo?.packageName
            if (targetPackage.isNullOrBlank()) {
                showMessage("Aplikasi tidak ditemukan")
                return
            }

            if (targetPackage == packageName || isBuiltInSystemApp(targetPackage)) {
                startActivity(intent)
            } else {
                launchVipApp(intent)
            }
        } catch (_: Exception) {
            showMessage("Aplikasi tidak ditemukan")
        }
    }

    private fun openAppByName(vararg names: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val apps = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(0L)
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, 0)
            }
            val aliases = names.map {
                it.lowercase(Locale("id", "ID"))
                    .replace(Regex("[^a-z0-9 ]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }.filter { it.length >= 3 }
            val matched = apps.mapNotNull { app ->
                val targetPackage = app.activityInfo.packageName
                if (targetPackage == packageName ||
                    (!vipMode && !isBuiltInSystemApp(targetPackage))
                ) {
                    null
                } else {
                    val label = app.loadLabel(packageManager).toString()
                        .lowercase(Locale("id", "ID"))
                        .replace(Regex("[^a-z0-9 ]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    val score = when {
                        aliases.any { label == it } -> 0
                        aliases.any { label.startsWith(it) } -> 1
                        aliases.any { label.contains(it) } -> 2
                        aliases.any { it.contains(label) && label.length >= 3 } -> 3
                        else -> null
                    }
                    if (score == null) null else app to score
                }
            }.sortedBy { it.second }.firstOrNull()?.first

            if (matched != null) {
                // Jalankan komponen launcher yang benar-benar ditemukan, bukan menebak nama paket.
                val launch = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(
                        matched.activityInfo.packageName,
                        matched.activityInfo.name
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                launchIntent(launch)
                true
            } else false
        } catch (_: Exception) {
            false
        }
    }

    private fun controlTorch(turnOn: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showMessage("Izin kamera belum diberikan")
            return
        }

        try {
            val cameraManager =
                getSystemService(CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull {
                cameraManager.getCameraCharacteristics(it).get(
                    CameraCharacteristics.FLASH_INFO_AVAILABLE
                ) == true
            }

            if (cameraId == null) {
                showMessage("Senter tidak tersedia")
                return
            }

            cameraManager.setTorchMode(cameraId, turnOn)
            showMessage(
                if (turnOn) "Senter dinyalakan" else "Senter dimatikan"
            )
        } catch (e: Exception) {
            showMessage("Senter tidak dapat digunakan")
        }
    }

    override fun onDestroy() {
        stopCurrentMode()
        serviceInitialized = false
        super.onDestroy()
    }
}
