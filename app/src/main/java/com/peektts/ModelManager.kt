package com.peektts

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ModelManager v2 — 多镜像源 + 多模型选择 + 实时进度 + 自动重试
 *
 * 下载策略：
 * 1. 每个文件有多个镜像源URL，依次尝试直到成功
 * 2. 每个源最多重试2次
 * 3. 下载超时30秒自动切换下一个源
 * 4. 实时报告下载进度（字节数/总大小）
 * 5. 支持断点续传（跳过已下载的文件）
 */
class ModelManager(private val context: Context) {

    private val tag = "ModelManager"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cancelFlag = AtomicBoolean(false)

    /** 当前选择的模型 */
    var selection: ModelSelection = loadSelection()
        private set

    fun getModelDir(): File = File(context.filesDir, "models").also { it.mkdirs() }

    fun getVadDir(): File = File(getModelDir(), selection.vad.dirName)
    fun getAsrDir(): File = File(getModelDir(), selection.asr.dirName)
    fun getTtsDir(): File = File(getModelDir(), selection.tts.dirName)

    /** 检查选中的模型是否全部就绪 */
    fun areModelsReady(): Boolean {
        return isVadReady() && isAsrReady() && isTtsReady()
    }

    fun isVadReady(): Boolean = File(getVadDir(), "silero_vad.onnx").exists()

    fun isAsrReady(): Boolean {
        val dir = getAsrDir()
        // 检查关键文件是否存在
        return when (selection.asr.id) {
            "asr_zipformer_bi" -> File(dir, "encoder-epoch-99-avg-1.onnx").exists()
            "asr_sense_voice" -> File(dir, "model.int8.onnx").exists()
            "asr_paraformer" -> File(dir, "model.int8.onnx").exists()
            "asr_whisper_tiny" -> File(dir, "whisper-tiny.onnx").exists()
            else -> false
        }
    }

    fun isTtsReady(): Boolean {
        val dir = getTtsDir()
        return when (selection.tts.id) {
            "tts_kokoro_v11", "tts_kokoro_v10" -> File(dir, "model.onnx").exists()
            "tts_vits_zh" -> dir.listFiles()?.any { it.name.endsWith(".onnx") } == true
            "tts_vits_melo" -> File(dir, "vits-melo-tts-zh_en.onnx").exists()
            else -> false
        }
    }

    /** 更新模型选择 */
    fun updateSelection(asrId: String, ttsId: String) {
        selection = ModelSelection(
            vad = ModelRegistry.VAD_SILERO,
            asr = ModelRegistry.findAsr(asrId),
            tts = ModelRegistry.findTts(ttsId),
        )
        saveSelection()
    }

    fun cancelDownload() {
        cancelFlag.set(true)
    }

    /**
     * 下载所有选中的模型
     * callback: (status, overallProgress 0-100, detail) -> Unit
     */
    fun downloadAll(callback: (String, Int, String) -> Unit) {
        cancelFlag.set(false)
        Thread {
            try {
                // Phase 1: VAD (权重 0-5%)
                if (!isVadReady()) {
                    callback("下载 VAD 模型...", 1, "${selection.vad.name} (${selection.vad.sizeText})")
                    downloadModel(selection.vad) { p, d ->
                        callback("下载 VAD 模型...", (p * 0.05f).toInt(), d)
                    }
                }

                if (cancelFlag.get()) { callback("已取消", -1, ""); return@Thread }

                // Phase 2: ASR (权重 5-35%)
                if (!isAsrReady()) {
                    callback("下载 ASR 语音识别模型...", 5, "${selection.asr.name} (${selection.asr.sizeText})")
                    downloadModel(selection.asr) { p, d ->
                        callback("下载 ASR 模型...", 5 + (p * 0.30f).toInt(), d)
                    }
                }

                if (cancelFlag.get()) { callback("已取消", -1, ""); return@Thread }

                // Phase 3: TTS (权重 35-100%)
                if (!isTtsReady()) {
                    callback("下载 TTS 语音合成模型...", 35, "${selection.tts.name} (${selection.tts.sizeText})")
                    downloadModel(selection.tts) { p, d ->
                        callback("下载 TTS 模型...", 35 + (p * 0.65f).toInt(), d)
                    }
                }

                if (cancelFlag.get()) { callback("已取消", -1, ""); return@Thread }

                callback("模型下载完成！", 100, "全部就绪")

            } catch (e: Exception) {
                Log.e(tag, "Download failed", e)
                callback("下载失败: ${e.message}", -1, e.message ?: "未知错误")
            }
        }.start()
    }

    /**
     * 下载单个模型选项 — 依次尝试各镜像源
     */
    private fun downloadModel(option: ModelOption, progressCallback: (Int, String) -> Unit) {
        val targetDir = File(getModelDir(), option.dirName).apply { mkdirs() }

        for ((fileIndex, modelFile) in option.files.withIndex()) {
            if (cancelFlag.get()) return

            val targetFile = File(targetDir, modelFile.name)

            // 跳过已下载的文件
            if (targetFile.exists() && targetFile.length() > 0) {
                Log.i(tag, "File already exists, skipping: ${modelFile.name}")
                progressCallback(100, "${modelFile.name} 已存在")
                continue
            }

            var lastError: Exception? = null

            // 依次尝试各镜像源
            for ((sourceIndex, url) in modelFile.sources.withIndex()) {
                if (cancelFlag.get()) return

                val sourceLabel = when {
                    url.contains("hf-mirror.com") -> "HF-Mirror"
                    url.contains("huggingface.co") -> "HuggingFace"
                    url.contains("modelscope") -> "ModelScope"
                    url.contains("github.com") -> "GitHub"
                    else -> "Source${sourceIndex + 1}"
                }

                // 每个源最多重试2次
                for (retry in 0..1) {
                    if (cancelFlag.get()) return

                    try {
                        val retryText = if (retry > 0) " (重试$retry)" else ""
                        progressCallback(0, "正在从 $sourceLabel 下载 ${modelFile.name}$retryText...")
                        Log.i(tag, "Downloading from $sourceLabel: $url")

                        downloadFileWithProgress(url, targetFile) { downloaded, total, speed ->
                            val percent = if (total > 0) (downloaded * 100 / total).toInt() else 0
                            val sizeMB = downloaded / 1048576.0
                            val totalMB = if (total > 0) total / 1048576.0 else 0.0
                            val speedKB = speed / 1024.0
                            progressCallback(
                                percent,
                                "$sourceLabel: ${String.format("%.1f", sizeMB)}/${String.format("%.1f", totalMB)}MB  ${String.format("%.0f", speedKB)}KB/s"
                            )
                        }

                        // 下载成功
                        Log.i(tag, "Downloaded ${modelFile.name} from $sourceLabel (${targetFile.length()} bytes)")

                        // 如果是压缩包，解压
                        if (option.isArchive && modelFile.name.endsWith(".tar.bz2")) {
                            progressCallback(100, "正在解压 ${modelFile.name}...")
                            extractTarBz2(targetFile, targetDir)
                            // 解压后删除压缩包
                            targetFile.delete()
                            Log.i(tag, "Extracted and deleted archive: ${modelFile.name}")
                        }

                        lastError = null
                        break  // 成功，跳出重试循环

                    } catch (e: Exception) {
                        lastError = e
                        Log.w(tag, "Failed from $sourceLabel (retry $retry): ${e.message}")

                        // 删除不完整的文件
                        if (targetFile.exists()) targetFile.delete()

                        if (cancelFlag.get()) return
                    }
                }

                if (lastError == null) break  // 成功，跳出镜像源循环
            }

            if (lastError != null) {
                throw IOException("所有镜像源下载失败: ${modelFile.name}\n${lastError.message}", lastError)
            }
        }
    }

    /**
     * 带进度的文件下载
     */
    private fun downloadFileWithProgress(
        url: String,
        target: File,
        progressCallback: (downloaded: Long, total: Long, speedBytesPerSec: Long) -> Unit
    ) {
        val request = Request.Builder().url(url).header("Connection", "keep-alive").build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${response.message}")
            }

            val total = response.body?.contentLength() ?: -1L
            val inputStream = response.body?.byteStream()
                ?: throw IOException("Empty response body")

            val tmpFile = File(target.parentFile, "${target.name}.tmp")
            var downloaded = 0L
            var lastReportTime = System.currentTimeMillis()
            var lastReportBytes = 0L
            val buffer = ByteArray(8192)

            tmpFile.outputStream().use { output ->
                while (true) {
                    if (cancelFlag.get()) {
                        tmpFile.delete()
                        throw IOException("下载已取消")
                    }

                    val read = inputStream.read(buffer)
                    if (read == -1) break

                    output.write(buffer, 0, read)
                    downloaded += read

                    // 每秒报告一次进度
                    val now = System.currentTimeMillis()
                    if (now - lastReportTime >= 500) {
                        val elapsed = (now - lastReportTime) / 1000.0
                        val speed = if (elapsed > 0) ((downloaded - lastReportBytes) / elapsed).toLong() else 0L
                        progressCallback(downloaded, total, speed)
                        lastReportTime = now
                        lastReportBytes = downloaded
                    }
                }
                output.flush()
            }

            // 重命名临时文件为最终文件
            if (target.exists()) target.delete()
            tmpFile.renameTo(target)

            // 最终进度报告
            progressCallback(downloaded, total, 0L)
        }
    }

    /** 解压 tar.bz2 文件 (自动去除顶层目录) */
    private fun extractTarBz2(archiveFile: File, targetDir: File) {
        // sherpa-onnx 模型包都有一个顶层目录，用 --strip-components=1 去掉
        val process = ProcessBuilder(
            "tar", "xjf", archiveFile.absolutePath,
            "-C", targetDir.absolutePath,
            "--strip-components=1"
        ).redirectErrorStream(true)

        val proc = process.start()
        val output = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()

        if (exitCode != 0) {
            Log.e(tag, "Extract failed (exit $exitCode): $output")
            // 如果 strip-components=1 失败，尝试不 strip
            Log.w(tag, "Retrying extraction without strip-components...")
            val process2 = ProcessBuilder(
                "tar", "xjf", archiveFile.absolutePath,
                "-C", targetDir.absolutePath
            ).redirectErrorStream(true)
            val proc2 = process2.start()
            val output2 = proc2.inputStream.bufferedReader().readText()
            val exit2 = proc2.waitFor()
            if (exit2 != 0) {
                throw IOException("解压失败: $output2")
            }
        }
        Log.i(tag, "Extracted to ${targetDir.absolutePath}")
    }

    // ==================== 持久化选择 ====================

    private fun loadSelection(): ModelSelection {
        val prefs = context.getSharedPreferences("peektts_models", android.content.Context.MODE_PRIVATE)
        val asrId = prefs.getString("asr_id", null) ?: return ModelRegistry.DEFAULT_SELECTION
        val ttsId = prefs.getString("tts_id", null) ?: return ModelRegistry.DEFAULT_SELECTION
        return ModelSelection(
            vad = ModelRegistry.VAD_SILERO,
            asr = ModelRegistry.findAsr(asrId),
            tts = ModelRegistry.findTts(ttsId),
        )
    }

    private fun saveSelection() {
        val prefs = context.getSharedPreferences("peektts_models", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("asr_id", selection.asr.id)
            .putString("tts_id", selection.tts.id)
            .apply()
    }
}
