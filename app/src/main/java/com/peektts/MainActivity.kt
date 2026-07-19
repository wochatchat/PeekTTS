package com.peektts

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.peektts.AssistantEngine.EngineState

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvConversation: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownloadModels: Button
    private lateinit var spinnerTtsVoice: Spinner
    private lateinit var switchAutoStart: Switch

    private val modelManager by lazy { ModelManager(this) }

    // Broadcast receiver for engine state updates
    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                AssistantService.ACTION_STATE_CHANGED -> {
                    val state = intent.getStringExtra("state") ?: "idle"
                    updateStatus(state)
                }
                AssistantService.ACTION_CONVERSATION_UPDATE -> {
                    val role = intent.getStringExtra("role") ?: ""
                    val text = intent.getStringExtra("text") ?: ""
                    appendConversation(role, text)
                }
                AssistantService.ACTION_MODEL_STATUS -> {
                    val status = intent.getStringExtra("status") ?: ""
                    val progress = intent.getIntExtra("progress", -1)
                    updateModelStatus(status, progress)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupListeners()
        checkPermissions()
        updateModelStatus(if (modelManager.areModelsReady()) "模型已就绪" else "模型未下载", -1)
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvConversation = findViewById(R.id.tvConversation)
        progressBar = findViewById(R.id.progressBar)
        btnDownloadModels = findViewById(R.id.btnDownloadModels)
        spinnerTtsVoice = findViewById(R.id.spinnerTtsVoice)
        switchAutoStart = findViewById(R.id.switchAutoStart)

        // TTS voice options
        val voices = arrayOf("默认女声 (0)", "男声 (1)", "女声 (2)", "男声 (3)")
        spinnerTtsVoice.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, voices)
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            val intent = Intent(this, AssistantService::class.java)
            intent.action = AssistantService.ACTION_TOGGLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }

        btnDownloadModels.setOnClickListener {
            if (!modelManager.areModelsReady()) {
                btnDownloadModels.isEnabled = false
                progressBar.visibility = View.VISIBLE
                modelManager.downloadAll { status, progress ->
                    runOnUiThread {
                        tvModelStatus.text = status
                        if (progress >= 0) {
                            progressBar.progress = progress
                        }
                        if (modelManager.areModelsReady()) {
                            progressBar.visibility = View.GONE
                            btnDownloadModels.isEnabled = true
                            btnDownloadModels.text = "模型已就绪"
                            Toast.makeText(this, "模型下载完成！", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            val prefs = getSharedPreferences("peektts", MODE_PRIVATE)
            prefs.edit().putBoolean("auto_start_boot", isChecked).apply()
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), 100)
        }
    }

    private fun updateStatus(state: String) {
        tvStatus.text = when (state) {
            "listening" -> "🎧 监听中..."
            "recognizing" -> "🗣️ 识别中..."
            "thinking" -> "🤔 思考中..."
            "speaking" -> "🔊 回复中..."
            "idle" -> "⏸️ 待机"
            "error" -> "❌ 错误"
            else -> state
        }
        btnToggle.text = if (state == "idle" || state == "error") "启动助手" else "停止助手"
    }

    private fun appendConversation(role: String, text: String) {
        val prefix = when (role) {
            "user" -> "我: "
            "assistant" -> "助手: "
            else -> ""
        }
        tvConversation.append("$prefix$text\n\n")
    }

    private fun updateModelStatus(status: String, progress: Int) {
        tvModelStatus.text = status
        if (progress >= 0) {
            progressBar.progress = progress
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = android.content.IntentFilter().apply {
            addAction(AssistantService.ACTION_STATE_CHANGED)
            addAction(AssistantService.ACTION_CONVERSATION_UPDATE)
            addAction(AssistantService.ACTION_MODEL_STATUS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(stateReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(stateReceiver)
    }
}
