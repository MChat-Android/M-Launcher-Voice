package com.masisten.offlineaipoc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.content.Intent
import android.net.Uri
import android.os.StatFs
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Standalone POC; the serial worker exclusively owns Engine and Conversation. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var engine: Engine? = null
    @Volatile private var conversation: Conversation? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var prompt: EditText
    private lateinit var chooseButton: Button
    private lateinit var deleteButton: Button
    private lateinit var loadButton: Button
    private lateinit var unloadButton: Button
    private lateinit var sendButton: Button
    private lateinit var resetButton: Button
    private var closing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        showModelInstructions()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 20)
        }
        status = TextView(this).apply { textSize = 16f }
        transcript = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        val scroll = ScrollView(this).apply { addView(transcript) }
        prompt = EditText(this).apply {
            hint = "Tulis pertanyaan untuk model lokal"
            minLines = 2
            setSingleLine(false)
        }
        chooseButton = Button(this).apply {
            text = "PILIH FILE MODEL (.litertlm)"
            setOnClickListener {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQUEST_MODEL_FILE)
            }
        }
        deleteButton = Button(this).apply {
            text = "HAPUS MODEL"
            setOnClickListener { deleteModel() }
        }
        loadButton = Button(this).apply {
            text = "MUAT MODEL LOKAL"
            setOnClickListener { loadModel() }
        }
        unloadButton = Button(this).apply {
            text = "LEPAS MODEL DARI MEMORI"
            isEnabled = false
            setOnClickListener { unloadModel() }
        }
        sendButton = Button(this).apply {
            text = "KIRIM"
            isEnabled = false
            setOnClickListener { sendPrompt() }
        }
        resetButton = Button(this).apply {
            text = "RESET PERCAKAPAN"
            isEnabled = false
            setOnClickListener { resetConversation() }
        }
        root.addView(status)
        root.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        root.addView(prompt)
        root.addView(chooseButton)
        root.addView(deleteButton)
        root.addView(loadButton)
        root.addView(unloadButton)
        root.addView(sendButton)
        root.addView(resetButton)
        setContentView(root)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL_FILE || resultCode != RESULT_OK) return
        data?.data?.let { importModel(it) }
    }

    private fun importModel(uri: Uri) {
        val destination = File(filesDir, "model.litertlm")
        val temporary = File(filesDir, "model.litertlm.part")
        if (StatFs(filesDir.absolutePath).availableBytes < EXPECTED_MODEL_BYTES + 32L * 1024 * 1024) {
            status.text = "Ruang penyimpanan tidak cukup (perlu sekitar 620 MiB kosong)."
            return
        }
        chooseButton.isEnabled = false
        loadButton.isEnabled = false
        deleteButton.isEnabled = false
        status.text = "Menyalin dan memverifikasi model…"
        worker.execute {
            try {
                if (engine != null) throw IllegalStateException("Lepas model dari memori sebelum mengganti file")
                temporary.delete()
                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                val source = contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("File tidak dapat dibuka")
                source.use { input ->
                    temporary.outputStream().buffered().use { output ->
                        val buffer = ByteArray(1024 * 1024)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > EXPECTED_MODEL_BYTES) throw IllegalStateException("Ukuran file terlalu besar")
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (total != EXPECTED_MODEL_BYTES) throw IllegalStateException("Ukuran file tidak cocok: $total byte (seharusnya $EXPECTED_MODEL_BYTES)")
                if (hash != EXPECTED_MODEL_SHA256) throw IllegalStateException("SHA-256 tidak cocok; hasil file: $hash")
                if (engine != null) throw IllegalStateException("Model sedang dimuat; tutup aplikasi lalu coba lagi")
                if (destination.exists() && !destination.delete()) throw IllegalStateException("Model lama gagal dihapus")
                if (!temporary.renameTo(destination)) throw IllegalStateException("Gagal menyimpan model terverifikasi")
                mainHandler.post {
                    status.text = "Model terpasang • 586 MiB • ukuran dan SHA-256 valid"
                    chooseButton.isEnabled = true
                    deleteButton.isEnabled = true
                    loadButton.isEnabled = true
                }
            } catch (error: Exception) {
                temporary.delete()
                mainHandler.post {
                    status.text = "Pemasangan ditolak: " + (error.message ?: error.javaClass.simpleName)
                    chooseButton.isEnabled = true
                    deleteButton.isEnabled = destination.exists()
                    loadButton.isEnabled = destination.exists()
                }
            }
        }
    }

    private fun deleteModel() {
        if (engine != null) {
            status.text = "Model sedang dimuat. Tutup lalu buka kembali aplikasi untuk menghapus."
            return
        }
        val model = File(filesDir, "model.litertlm")
        if (model.exists() && !model.delete()) {
            status.text = "Gagal menghapus file model."
            return
        }
        status.text = "File model dihapus dari penyimpanan aplikasi."
        loadButton.isEnabled = false
        deleteButton.isEnabled = false
    }

    private fun showModelInstructions() {
        val model = File(filesDir, "model.litertlm")
        val installed = model.isFile && model.length() == EXPECTED_MODEL_BYTES
        status.text = "POC terisolasi • CPU • tanpa akses internet\n" +
            if (installed) "Model terpasang • ukuran cocok" else
                "Pilih file Qwen3-0.6B INT8 .litertlm. Ukuran dan SHA-256 akan diverifikasi."
        loadButton.isEnabled = installed
        deleteButton.isEnabled = model.exists()
        unloadButton.isEnabled = false
    }

    private fun loadModel() {
        val model = File(filesDir, "model.litertlm")
        if (!model.isFile || model.length() < 10_000_000L) {
            status.text = "Model belum ditemukan atau ukuran tidak valid."
            return
        }
        loadButton.isEnabled = false
        status.text = "Memuat model di thread latar belakang…"
        worker.execute {
            try {
                val candidate = Engine(EngineConfig(
                    modelPath = model.absolutePath,
                    backend = Backend.CPU(),
                    cacheDir = cacheDir.absolutePath
                ))
                // Do not close an Engine when initialize() itself fails.
                candidate.initialize()
                engine = candidate
                try {
                    conversation = candidate.createConversation(ConversationConfig())
                } catch (error: Exception) {
                    engine = null
                    candidate.close()
                    throw error
                }
                mainHandler.post {
                    status.text = "Model siap • inferensi lokal CPU"
                    sendButton.isEnabled = true
                    resetButton.isEnabled = true
                    loadButton.isEnabled = false
                    unloadButton.isEnabled = true
                    chooseButton.isEnabled = false
                    deleteButton.isEnabled = false
                }
            } catch (error: Exception) {
                mainHandler.post {
                    status.text = "Gagal memuat model: " +
                        (error.message ?: error.javaClass.simpleName)
                    loadButton.isEnabled = true
                    unloadButton.isEnabled = false
                    chooseButton.isEnabled = true
                    deleteButton.isEnabled = File(filesDir, "model.litertlm").exists()
                }
            }
        }
    }

    private fun closeRuntime() {
        val oldConversation = conversation
        val oldEngine = engine
        conversation = null
        engine = null
        try { oldConversation?.close() } finally { oldEngine?.close() }
    }

    private fun unloadModel() {
        if (closing) return
        sendButton.isEnabled = false
        resetButton.isEnabled = false
        unloadButton.isEnabled = false
        loadButton.isEnabled = false
        chooseButton.isEnabled = false
        deleteButton.isEnabled = false
        status.text = "Melepas model dari memori…"
        worker.execute {
            try {
                closeRuntime()
                mainHandler.post {
                    status.text = "Model dilepas dari memori • file tetap tersimpan"
                    sendButton.isEnabled = false
                    resetButton.isEnabled = false
                    loadButton.isEnabled = File(filesDir, "model.litertlm").isFile
                    chooseButton.isEnabled = true
                    deleteButton.isEnabled = File(filesDir, "model.litertlm").exists()
                }
            } catch (error: Exception) {
                mainHandler.post {
                    status.text = "Pelepasan model selesai dengan peringatan: " + (error.message ?: error.javaClass.simpleName)
                    loadButton.isEnabled = File(filesDir, "model.litertlm").isFile
                    chooseButton.isEnabled = true
                    deleteButton.isEnabled = File(filesDir, "model.litertlm").exists()
                }
            }
        }
    }

    private fun sendPrompt() {
        val text = prompt.text.toString().trim()
        if (text.isEmpty() || conversation == null || closing) return
        prompt.setText("")
        sendButton.isEnabled = false
        transcript.append("\n\nAnda: " + text + "\nM Asisten: ")
        worker.execute {
            try {
                val active = conversation ?: return@execute
                runBlocking {
                    active.sendMessageAsync(text).collect { chunk ->
                        val piece = chunk.toString()
                        mainHandler.post { transcript.append(piece) }
                    }
                }
                mainHandler.post { sendButton.isEnabled = conversation != null }
            } catch (error: Exception) {
                mainHandler.post {
                    transcript.append("\n[Error: " +
                        (error.message ?: error.javaClass.simpleName) + "]")
                    sendButton.isEnabled = conversation != null
                }
            }
        }
    }

    private fun resetConversation() {
        if (closing) return
        sendButton.isEnabled = false
        resetButton.isEnabled = false
        worker.execute {
            try {
                conversation?.close()
                conversation = null
                conversation = engine?.createConversation(ConversationConfig())
                mainHandler.post {
                    transcript.text = ""
                    status.text = "Percakapan baru siap • model tetap dimuat"
                    sendButton.isEnabled = conversation != null
                    resetButton.isEnabled = conversation != null
                }
            } catch (error: Exception) {
                mainHandler.post {
                    status.text = "Gagal reset: " +
                        (error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    companion object {
        private const val REQUEST_MODEL_FILE = 4107
        private const val EXPECTED_MODEL_BYTES = 613722264L
        private const val EXPECTED_MODEL_SHA256 = "c5f39b419192678c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108"
    }

    override fun onDestroy() {
        closing = true
        worker.execute {
            try { closeRuntime() } catch (_: Exception) {}
        }
        worker.shutdown()
        super.onDestroy()
    }
}
