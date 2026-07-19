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
import java.io.File

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
    private lateinit var btnReExtract: Button

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
        btnReExtract = findViewById(R.id.btnReExtract)
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

        btnReExtract.setOnClickListener {
            // 不重新下载，用刚改好的纯 Java tar 解压器把 TTS 目录里残留的 .tar.bz2 重新解压一遍。
            // 解完会把压缩包删除，并清理之前 strip 失败导致的嵌套目录。
            Toast.makeText(this, "正在重新解压 TTS 模型，请等待...", Toast.LENGTH_LONG).show()
            Thread {
                try {
                    CrashLogger.log("INFO", "ReEx", ">>> reExtractTtsArchive start")
                    CrashLogger.flushNow()
                    val n = modelManager.reExtractTtsArchive()
                    CrashLogger.log("INFO", "ReEx", ">>> reExtractTtsArchive done, archives reprocessed=$n")
                    CrashLogger.flushNow()
                    val ready = modelManager.isTtsReady()
                    CrashLogger.log("INFO", "ReEx", "isTtsReady=$ready, listing tts dir:")
                    val dir = java.io.File(modelManager.getTtsDir().absolutePath)
                    if (dir.exists()) {
                        dir.listFiles()?.forEach { f ->
                            CrashLogger.log("INFO", "ReEx", "   - ${f.name} size=${f.length()} isDir=${f.isDirectory}")
                        }
                    }
                    CrashLogger.flushNow()
                    runOnUiThread {
                        refreshModelStatus()
                        Toast.makeText(this, if (n < 0) "压缩包不存在，可能未下载" else "重新解压完成，准备诊断", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, LogActivity::class.java))
                    }
                } catch (t: Throwable) {
                    CrashLogger.error(tag = "ReEx", message = "重新解压失败", throwable = t)
                    CrashLogger.flushNow()
                    runOnUiThread {
                        Toast.makeText(this, "重新解压出错：${t.message}", Toast.LENGTH_LONG).show()
                        startActivity(Intent(this, LogActivity::class.java))
                    }
                }
            }.start()
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
            CrashLogger.log("INFO", "DIAG", "=== before ASR init (sel.asr=${sel.asr.id}) ===")
            CrashLogger.flushNow()
            val asrDir = File(baseDir, sel.asr.dirName)
            when (sel.asr.id) {
                "asr_zipformer_bi" -> {
                    CrashLogger.log("INFO", "DIAG", "   Trying OnlineRecognizer(zipformer bi) tokens=${File(asrDir, "tokens.txt").exists()}, enc=${File(asrDir, "encoder-epoch-99-avg-1.onnx").exists()}")
                    CrashLogger.flushNow()
                    val asr = com.k2fsa.sherpa.onnx.OnlineRecognizer(
                        config = com.k2fsa.sherpa.onnx.OnlineRecognizerConfig(
                            modelConfig = com.k2fsa.sherpa.onnx.OnlineModelConfig(
                                transducer = com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig(
                                    encoder = "${asrDir.absolutePath}/encoder-epoch-99-avg-1.onnx",
                                    decoder = "${asrDir.absolutePath}/decoder-epoch-99-avg-1.onnx",
                                    joiner = "${asrDir.absolutePath}/joiner-epoch-99-avg-1.onnx",
                                ),
                                tokens = "${asrDir.absolutePath}/tokens.txt",
                                numThreads = 2,
                                provider = "cpu",
                                modelType = "zipformer",
                            ),
                            endpointConfig = com.k2fsa.sherpa.onnx.EndpointConfig(
                                rule1 = com.k2fsa.sherpa.onnx.EndpointRule(false, 2.4f, 0.0f),
                                rule2 = com.k2fsa.sherpa.onnx.EndpointRule(true, 1.2f, 0.0f),
                                rule3 = com.k2fsa.sherpa.onnx.EndpointRule(false, 0.0f, 20.0f),
                            ),
                            enableEndpoint = true,
                            decodingMethod = "greedy_search",
                        )
                    )
                    CrashLogger.log("INFO", "DIAG", "   OnlineRecognizer(zf bi) OK")
                    CrashLogger.flushNow()
                    asr.release()
                }
                else -> {
                    CrashLogger.log("INFO", "DIAG", "   skipping ASR init for ${sel.asr.id}、非 zipformer_bi 暂不诊断")
                    CrashLogger.flushNow()
                }
            }

            CrashLogger.log("INFO", "DIAG", "=== before TTS init (sel.tts=${sel.tts.id}, tts_dir files=${File(baseDir, sel.tts.dirName).listFiles()?.map { it.name } ?: listOf()}) ===")
            CrashLogger.flushNow()
            val ttsDir = File(baseDir, sel.tts.dirName)
            val lexFiles = listOf("lexicon.txt", "lexicon-zh.txt")
                .map { File(ttsDir, it) }
                .filter { it.exists() && it.length() > 0 }
                .joinToString(",") { it.absolutePath }
            if (sel.tts.id in listOf("tts_kokoro_v11", "tts_kokoro_v10")) {
                CrashLogger.log("INFO", "DIAG", "   Trying OfflineTts(Kokoro) model=${File(ttsDir, "model.onnx").exists()}, voices=${File(ttsDir, "voices.bin").exists()}, tokens=${File(ttsDir, "tokens.txt").exists()}, espeak=${File(ttsDir, "espeak-ng-data").isDirectory}, lex=$lexFiles")
                CrashLogger.flushNow()
                val tEngine = com.k2fsa.sherpa.onnx.OfflineTts(
                    config = com.k2fsa.sherpa.onnx.OfflineTtsConfig(
                        model = com.k2fsa.sherpa.onnx.OfflineTtsModelConfig(
                            kokoro = com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig(
                                model = "${ttsDir.absolutePath}/model.onnx",
                                voices = "${ttsDir.absolutePath}/voices.bin",
                                tokens = "${ttsDir.absolutePath}/tokens.txt",
                                dataDir = "${ttsDir.absolutePath}/espeak-ng-data",
                                lexicon = lexFiles,
                                lengthScale = 1.0f,
                            ),
                            numThreads = 4,
                            provider = "cpu",
                        )
                    )
                )
                CrashLogger.log("INFO", "DIAG", "   OfflineTts(Kokoro) OK, sampleRate=${tEngine.sampleRate()}")
                CrashLogger.flushNow()
                tEngine.release()
            } else {
                CrashLogger.log("INFO", "DIAG", "   skipping TTS init for ${sel.tts.id}、非 kokoro 暂不诊断")
                CrashLogger.flushNow()
            }

            CrashLogger.log("INFO", "DIAG", ">>> diag complete — 如果看到这里说明所有 native init 都跑通了，崩点在 listenLoop")
            CrashLogger.flushNow()
            runOnUiThread {
                Toast.makeText(this, "全部 native init 成功，已跳转 log 页", Toast.LENGTH_LONG).show()
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
