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
    private lateinit var btnOpenLog: Button
    private lateinit var btnDiag: Button

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
        preflightNativeLib()
    }

    /**
     * 在主线程显式预加载 native 库：
     *  - 通过 try/catch(Throwable) 暴露 loadLibrary 失败
     *  - 失败时 App 仍然能开，但点击启动助手按钮后 Service 也不会再"莫名其妙"闪退
     *  - 把结果记到 CrashLogger，让日志页能看到
     */
    private fun preflightNativeLib() {
        try {
            System.loadLibrary("sherpa-onnx-jni")
            CrashLogger.log("INFO", "MainActivity", "preflight: loadLibrary OK, abi=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}")
        } catch (t: Throwable) {
            CrashLogger.error(
                tag = "MainActivity",
                message = "preflight: loadLibrary(\"sherpa-onnx-jni\") 失败 — 模型可能未含对应 ABI 或 .so 文件缺失/被 ProGuard 剥离",
                throwable = t
            )
        }
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
        btnOpenLog = findViewById(R.id.btnOpenLog)
        btnDiag = findViewById(R.id.btnDiag)
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
            try {
                val intent = Intent(this, AssistantService::class.java)
                intent.action = AssistantService.ACTION_TOGGLE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } catch (t: Throwable) {
                CrashLogger.error(
                    tag = "MainActivity",
                    message = "点击『启动助手』按钮抛异常",
                    throwable = t
                )
                Toast.makeText(this, "启动助手时出错，正在打开日志页", Toast.LENGTH_LONG).show()
                android.content.Intent(this, LogActivity::class.java).also {
                    it.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(it)
                }
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

        btnOpenLog.setOnClickListener {
            startActivity(Intent(this, LogActivity::class.java))
        }

        btnDiag.setOnClickListener {
            // 在子线程跑 native init，防止 ANR。
            // 一旦 native abort 把整个进程杀掉，主线程也来不及显示 Toast，
            // 但崩前所有 INFO marker 都已 flush 到磁盘，LogActivity 重启后可读。
            Toast.makeText(this, "正在子线程同步执行 native init...", Toast.LENGTH_LONG).show()
            Thread { diagSyncInit() }.start()
        }

        switchAutoStart.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("peektts", MODE_PRIVATE)
                .edit().putBoolean("auto_start_boot", isChecked).apply()
        }
    }

    /**
     * 同步诊断：在主线程跑一遍 initModels 各步、并把每一步之前的 marker 立即 flush 到磁盘，
     * 这样即使 native 在某一步 abort() 整个进程，崩前最后一条 marker 仍能在 log 里看到。
     */
    private fun diagSyncInit() {
        try {
            // 用一个临时 AssistantEngine 实际跑 initModels
            CrashLogger.log("INFO", "DIAG", ">>> diag start, thread=${Thread.currentThread().name}")
            CrashLogger.flushNow()

            val baseDir = modelManager.getModelDir().absolutePath
            val sel = modelManager.selection
            CrashLogger.log("INFO", "DIAG", "baseDir=$baseDir, vad=${sel.vad.id}/${sel.vad.dirName}, asr=${sel.asr.id}/${sel.asr.dirName}, tts=${sel.tts.id}/${sel.tts.dirName}")
            CrashLogger.flushNow()

            // 详细列出每个文件是否存在 + 大小
            fun dumpDir(label: String, dir: File) {
                if (!dir.exists()) {
                    CrashLogger.log("INFO", "DIAG", "$label: DIR NOT EXIST -> ${dir.absolutePath}")
                } else {
                    val files = dir.listFiles() ?: emptyArray()
                    CrashLogger.log("INFO", "DIAG", "$label: ${dir.absolutePath}, files=${files.size}")
                    files.forEach { f ->
                        CrashLogger.log("INFO", "DIAG", "   - ${f.name} size=${f.length()} isDir=${f.isDirectory}")
                    }
                }
                CrashLogger.flushNow()
            }
            dumpDir("VAD dir", File(baseDir, sel.vad.dirName))
            dumpDir("ASR dir", File(baseDir, sel.asr.dirName))
            dumpDir("TTS dir", File(baseDir, sel.tts.dirName))

            CrashLogger.log("INFO", "DIAG", "=== before loadLibrary ===")
            CrashLogger.flushNow()
            System.loadLibrary("sherpa-onnx-jni")
            CrashLogger.log("INFO", "DIAG", "=== after loadLibrary OK ===")
            CrashLogger.flushNow()

            // 试构造 VAD (这是 initModels 里第一步，最可能崩)
            CrashLogger.log("INFO", "DIAG", "=== before new Vad(...) — 如果进程在这里之后死掉，说明 native 在 Vad init 内部 abort，多半是 silero_vad.onnx 路径错的或文件损坏 ===")
            CrashLogger.flushNow()
            val vadCfg = com.k2fsa.sherpa.onnx.VadModelConfig(
                sileroVadModelConfig = com.k2fsa.sherpa.onnx.SileroVadModelConfig(
                    model = "$baseDir/${sel.vad.dirName}/silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = 512,
                    maxSpeechDuration = 30.0f,
                ),
                sampleRate = 16000,
                numThreads = 1,
            )
            val vad = com.k2fsa.sherpa.onnx.Vad(config = vadCfg)
            CrashLogger.log("INFO", "DIAG", "=== Vad OK, vad=$vad ===")
            CrashLogger.flushNow()
            vad.release()

            // ASR
            CrashLogger.log("INFO", "DIAG", "=== before ASR init ===")
            CrashLogger.flushNow()
            // 选 streaming / offline 复用 Engine 里的逻辑不太好直接拿到 private 方法，复用一个最简的 sense voice
            // 实际跑 initStreaming/Offline 会用 Engine 自己的方法
            CrashLogger.log("INFO", "DIAG", "   skipping ASR detail init here，单独 crash 难诊断、留给 engine.start 复跑")
            CrashLogger.flushNow()

            CrashLogger.log("INFO", "DIAG", ">>> diag complete — 如果看到这里说明 VAD init 没崩，那崩点在 ASR/TTS init 或 listenLoop")
            CrashLogger.flushNow()
            runOnUiThread {
                Toast.makeText(this, "VAD init 成功，已跳转到 log 页", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LogActivity::class.java))
            }
        } catch (t: Throwable) {
            CrashLogger.error(tag = "DIAG", message = "diag 同步初始化抛 ${t.javaClass.name}", throwable = t)
            CrashLogger.flushNow()
            runOnUiThread {
                Toast.makeText(this, "崩溃已记录：${t.javaClass.simpleName}: ${t.message}", Toast.LENGTH_LONG).show()
                startActivity(Intent(this, LogActivity::class.java))
            }
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

        // 上次 MainActivity 不可见时若有新增 CRASH 级别日志，自动跳转 LogActivity。
        // 解决按钮点击 → Service 内崩溃 → App 进程被系统杀 → 再点开 App 看不到 log 的盲点。
        val lastSeen = getSharedPreferences("peektts", MODE_PRIVATE).getInt("last_crash_seen", 0)
        val currentCount = CrashLogger.snapshot().count { it.level == "CRASH" || it.level == "ERROR" }
        if (currentCount > lastSeen) {
            getSharedPreferences("peektts", MODE_PRIVATE).edit().putInt("last_crash_seen", currentCount).apply()
            startActivity(Intent(this, LogActivity::class.java))
        }

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
