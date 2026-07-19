package com.peektts

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ModelManager — 管理语音模型的下载和路径
 *
 * 模型下载自 HuggingFace，存储在应用内部存储中：
 * - silero_vad/silero_vad.onnx (~2MB)
 * - zipformer-asr/ (encoder, decoder, joiner, tokens) (~50MB)
 * - kokoro-tts/ (model.onnx, voices.bin, tokens.txt, espeak-ng-data, lexicon.txt) (~300MB)
 */
class ModelManager(private val context: Context) {

    private val tag = "ModelManager"
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        // Model download URLs from HuggingFace
        private const val BASE_URL = "https://huggingface.co/csukuangfj"

        private const val VAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx"

        // Zipformer streaming ASR model (Chinese + English)
        private const val ASR_MODEL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main"

        // Kokoro multilingual TTS (Chinese + English)
        private const val TTS_MODEL_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-kokoro-multi-lang-v1_1/resolve/main"
    }

    fun getModelDir(): File {
        return File(context.filesDir, "models").also { it.mkdirs() }
    }

    fun areModelsReady(): Boolean {
        val dir = getModelDir()
        return File(dir, "silero_vad/silero_vad.onnx").exists() &&
               File(dir, "zipformer-asr/encoder-epoch-99-avg-1.onnx").exists() &&
               File(dir, "kokoro-tts/model.onnx").exists()
    }

    fun downloadAll(callback: (status: String, progress: Int) -> Unit) {
        Thread {
            try {
                callback("正在下载 VAD 模型...", 5)
                downloadVad()

                callback("正在下载 ASR 语音识别模型...", 15)
                downloadAsr()

                callback("正在下载 TTS 语音合成模型 (较大，请耐心等待)...", 50)
                downloadTts(callback)

                callback("模型下载完成！", 100)
            } catch (e: Exception) {
                Log.e(tag, "Download failed", e)
                callback("下载失败: ${e.message}", -1)
            }
        }.start()
    }

    private fun downloadVad() {
        val targetDir = File(getModelDir(), "silero_vad").apply { mkdirs() }
        val targetFile = File(targetDir, "silero_vad.onnx")
        if (targetFile.exists()) return
        downloadFile(VAD_URL, targetFile)
        Log.i(tag, "VAD model downloaded")
    }

    private fun downloadAsr() {
        val targetDir = File(getModelDir(), "zipformer-asr").apply { mkdirs() }
        val files = listOf(
            "encoder-epoch-99-avg-1.onnx",
            "decoder-epoch-99-avg-1.onnx",
            "joiner-epoch-99-avg-1.onnx",
            "tokens.txt",
        )
        for (file in files) {
            val target = File(targetDir, file)
            if (!target.exists()) {
                downloadFile("$ASR_MODEL_URL/$file", target)
            }
        }
        Log.i(tag, "ASR model downloaded")
    }

    private fun downloadTts(callback: (status: String, progress: Int) -> Unit) {
        val targetDir = File(getModelDir(), "kokoro-tts").apply { mkdirs() }
        val files = listOf(
            "model.onnx",
            "voices.bin",
            "tokens.txt",
            "lexicon.txt",
        )
        for ((i, file) in files.withIndex()) {
            val target = File(targetDir, file)
            if (!target.exists()) {
                callback("下载 $file...", 50 + i * 10)
                downloadFile("$TTS_MODEL_URL/$file", target)
            }
        }

        // espeak-ng-data (needed for Kokoro)
        val espeakDir = File(targetDir, "espeak-ng-data").apply { mkdirs() }
        val espeakUrl = "$TTS_MODEL_URL/espeak-ng-data"
        // The espeak-ng-data is a directory, we need to handle it
        // For simplicity, download the tar and extract
        val espeakTar = File(targetDir, "espeak-ng-data.tar.bz2")
        if (!File(espeakDir, "phontab").exists()) {
            try {
                downloadFile(espeakUrl, espeakTar)
                ProcessBuilder("tar", "xf", espeakTar.absolutePath, "-C", targetDir.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
                espeakTar.delete()
            } catch (e: Exception) {
                Log.w(tag, "espeak-ng-data download may have failed, trying alternative", e)
            }
        }
        Log.i(tag, "TTS model downloaded")
    }

    private fun downloadFile(url: String, target: File) {
        Log.i(tag, "Downloading: $url -> ${target.absolutePath}")

        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed: ${response.code} for $url")
            }
            response.body?.byteStream()?.use { input ->
                FileOutputStream(target).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
