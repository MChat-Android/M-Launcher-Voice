package com.masisten.offlineaipoc

import android.app.Activity
import android.os.Bundle
import android.os.Handler
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
import java.util.concurrent.Executors

/** Standalone POC; the serial worker exclusively owns Engine and Conversation. */
class MainActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var prompt: EditText
    private lateinit var loadButton: Button
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
        loadButton = Button(this).apply {
            text = "MUAT MODEL LOKAL"
            setOnClickListener { loadModel() }
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
        root.addView(loadButton)
        root.addView(sendButton)
        root.addView(resetButton)
        setContentView(root)
    }

    private fun showModelInstructions() {
        val model = File(filesDir, "model.litertlm")
        status.text = "POC terisolasi • CPU • tanpa akses internet\n" +
            "Letakkan model .litertlm di:\n" + model.absolutePath +
            "\nUkuran harus sesuai model card."
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
                }
            } catch (error: Exception) {
                mainHandler.post {
                    status.text = "Gagal memuat model: " +
                        (error.message ?: error.javaClass.simpleName)
                    loadButton.isEnabled = true
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

    override fun onDestroy() {
        closing = true
        worker.execute {
            try { conversation?.close(); conversation = null } catch (_: Exception) {}
            try { engine?.close(); engine = null } catch (_: Exception) {}
        }
        worker.shutdown()
        super.onDestroy()
    }
}
