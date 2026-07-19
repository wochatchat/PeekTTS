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

class MainActivity : AppCompatActivity() {

    private lateinit var btnToggle: Button
    private lateinit var tvStatus: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var tvDownloadDetail: TextView
    private lateinit var tvConversation: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var btnDownloadModels: Button
    private lateinit var btnCancelDownload: Button
    private lateinit var spinnerAsrModel: Spinner
    private lateinit var spinnerTtsModel: Spinner
    private lateinit var tvAsrModelDesc: TextView
    private lateinit var tvTtsModelDesc: TextView
    private lateinit var switchAutoStart: Switch

    private val modelManager by lazy { ModelManager(this) }

    private val stateReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                AssistantService.ACTION_STATE_CHANGED -> {
                    updateStatus(intent.getStringExtra("state") ?: "idle")
                }
                AssistantService.ACTION_CONVERSATION_UPDATE -> {
                    appendConversation(
                        intent.getStringExtra("role") ?: "",
                        intent.getStringExtra("text") ?: ""
                    )
                }
                AssistantService.ACTION_MODEL_STATUS -> {
                    val status = intent.getStringExtra("status") ?: ""
                    val progress = intent.getIntExtra("progress", -1)
                    val detail = intent.getStringExtra("detail") ?: ""
                    updateModelStatus(status, progress, detail)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupModelSpinners()
        setupListeners()
        checkPermissions()
        refreshModelStatus()
    }

    private fun initViews() {
        btnToggle = findViewById(R.id.btnToggle)
        tvStatus = findViewById(R.id.tvStatus)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        tvDownloadDetail = findViewById(R.id.tvDownloadDetail)
        tvConversation = findViewById(R.id.tvConversation)
        progressBar = findViewById(R.id.progressBar)
        btnDownloadModels = findViewById(R.id.btnDownloadModels)
        btnCancelDownload = findViewById(R.id.btnCancelDownload)
        spinnerAsrModel = findViewById(R.id.spinnerAsrModel)
        spinnerTtsModel = findViewById(R.id.spinnerTtsModel)
        tvAsrModelDesc = findViewById(R.id.tvAsrModelDesc)
        tvTtsModelDesc = findViewById(R.id.tvTtsModelDesc)
        switchAutoStart = findViewById(R.id.switchAutoStart)
    }

    private fun setupModelSpinners() {
        // ASR 模型选择
        val asrNames = ModelRegistry.ASR_OPTIONS.map { "${it.name} (${it.sizeText})" }
        spinnerAsrModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, asrNames)
        // 选中当前模型
        val currentAsrIdx = ModelRegistry.ASR_OPTIONS.indexOfFirst { it.id == modelManager.selection.asr.id }
        if (currentAsrIdx >= 0) spinnerAsrModel.setSelection(currentAsrIdx)
        updateAsrDesc()

        spinnerAsrModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                updateAsrDesc()
                saveModelSelection()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // TTS 模型选择
        val ttsNames = ModelRegistry.TTS_OPTIONS.map { "${it.name} (${it.sizeText})" }
        spinnerTtsModel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, ttsNames)
        val currentTtsIdx = ModelRegistry.TTS_OPTIONS.indexOfFirst { it.id == modelManager.selection.tts.id }
        if (currentTtsIdx >= 0) spinnerTtsModel.setSelection(currentTtsIdx)
        updateTtsDesc()

        spinnerTtsModel.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                updateTtsDesc()
                saveModelSelection()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateAsrDesc() {
        val opt = ModelRegistry.ASR_OPTIONS[spinnerAsrModel.selectedItemPosition]
        tvAsrModelDesc.text = opt.description
    }

    private fun updateTtsDesc() {
        val opt = ModelRegistry.TTS_OPTIONS[spinnerTtsModel.selectedItemPosition]
        tvTtsModelDesc.text = opt.description
    }

    private fun saveModelSelection() {
        val asrId = ModelRegistry.ASR_OPTIONS[spinnerAsrModel.selectedItemPosition].id
        val ttsId = ModelRegistry.TTS_OPTIONS[spinnerTtsModel.selectedItemPosition].id
        modelManager.updateSelection(asrId, ttsId)
        refreshModelStatus()
    }

    private fun setupListeners() {
        btnToggle.setOnClickListener {
            // Android 14+ 启动 microphone 类型的前台服务必须先授权 RECORD_AUDIO,
            // 否则会 SecurityException 闪退。先确认权限,缺失时请求并中止启动。
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    100
                )
                Toast.makeText(this, "请先授权麦克风权限", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
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
                btnCancelDownload.visibility = View.VISIBLE
                progressBar.visibility = View.VISIBLE
                saveModelSelection()

                modelManager.downloadAll { status, progress, detail ->
                    runOnUiThread {
                        tvModelStatus.text = status
                        tvDownloadDetail.text = detail
                        if (progress >= 0) {
                            progressBar.progress = progress
                        }
                        if (progress == 100 || progress == -1) {
                            btnDownloadModels.isEnabled = true
                            btnCancelDownload.visibility = View.GONE
                            if (progress == 100) {
                                progressBar.visibility = View.GONE
                                Toast.makeText(this, "模型下载完成！", Toast.LENGTH_SHORT).show()
                            }
                            refreshModelStatus()
                        }
                    }
                }
            }
        }

        btnCancelDownload.setOnClickListener {
            modelManager.cancelDownload()
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("peektts", MODE_PRIVATE)
                .edit().putBoolean("auto_start_boot", isChecked).apply()
        }
    }

    private fun refreshModelStatus() {
        val vadReady = modelManager.isVadReady()
        val asrReady = modelManager.isAsrReady()
        val ttsReady = modelManager.isTtsReady()

        val status = buildString {
            append("VAD: ${if (vadReady) "✅" else "❌"}  ")
            append("ASR: ${if (asrReady) "✅" else "❌"}  ")
            append("TTS: ${if (ttsReady) "✅" else "❌"}")
        }
        tvModelStatus.text = status

        if (modelManager.areModelsReady()) {
            btnDownloadModels.text = "✅ 模型已就绪"
            btnDownloadModels.isEnabled = false
        } else {
            btnDownloadModels.text = "下载模型"
            btnDownloadModels.isEnabled = true
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

    private fun updateModelStatus(status: String, progress: Int, detail: String) {
        tvModelStatus.text = status
        tvDownloadDetail.text = detail
        if (progress >= 0) progressBar.progress = progress
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
