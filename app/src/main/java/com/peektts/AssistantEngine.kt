package com.peektts

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.peektts.audio.AudioPlayer
import com.peektts.llm.LlmEngine
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AssistantEngine — 核心编排引擎 (v2 支持多模型)
 *
 * Pipeline: 麦克风采集 → VAD检测 → ASR识别 → LLM推理 → TTS合成 → 播放
 *
 * 支持两种 ASR 模式：
 * - 流式 (Zipformer): 边说边识别，实时显示
 * - 非流式 (SenseVoice/Paraformer/Whisper): VAD检测到句尾后整体识别
 */
class AssistantEngine(private val context: Context) {

    private val tag = "AssistantEngine"
    private val service: AssistantService? = context as? AssistantService

    var isRunning = false
        private set

    private val isSpeaking = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listenJob: Job? = null

    // sherpa-onnx components
    private var vad: Vad? = null
    private var onlineAsr: OnlineRecognizer? = null      // 流式ASR
    private var offlineAsr: OfflineRecognizer? = null     // 非流式ASR
    private var tts: OfflineTts? = null
    private var asrStream: OnlineStream? = null

    private val llm = LlmEngine()

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioPlayer: AudioPlayer? = null

    // Config
    private val sampleRate = 16000
    private val vadWindowSize = 512
    private val isStreamingAsr: Boolean get() = modelManager.selection.asr.id == "asr_zipformer_bi"

    private val modelManager = ModelManager(context)

    fun start() {
        if (isRunning) return
        try {
            initModels()
            initAudio()
            isRunning = true
            service?.broadcastState("listening")
            listenJob = scope.launch { listenLoop() }
        } catch (t: Throwable) {
            // 注意：必须用 Throwable 而非 Exception。
            // native 库加载失败抛的是 UnsatisfiedLinkError (Error 子类)，
            // 用 Exception 接不住，会逃逸到 Service 进程让整个 App 闪退。
            CrashLogger.error(
                tag = tag,
                message = "engine.start() 抛异常 (${t.javaClass.name})",
                throwable = t
            )
            service?.broadcastState("error")
        }
    }

    fun stop() {
        isRunning = false
        listenJob?.cancel()
        listenJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        asrStream?.release()
        asrStream = null
        audioPlayer?.stop()
        audioPlayer = null
        service?.broadcastState("idle")
    }

    fun release() {
        stop()
        vad?.release(); vad = null
        onlineAsr?.release(); onlineAsr = null
        offlineAsr?.release(); offlineAsr = null
        tts?.release(); tts = null
        scope.cancel()
    }

    // ==================== 模型初始化 ====================

    private fun initModels() {
        val baseDir = modelManager.getModelDir().absolutePath
        val sel = modelManager.selection
        CrashLogger.log("INFO", tag, "initModels: baseDir=$baseDir, vad=${sel.vad.id}, asr=${sel.asr.id}, tts=${sel.tts.id}")

        // 0. 提前显式加载 native 库，失败立即抛可读错误。
        //    如果不在这一步失败，后面 new Vad(...) 触发类初始化时也会失败但堆栈更隐蔽。
        try {
            System.loadLibrary("sherpa-onnx-jni")
            CrashLogger.log("INFO", tag, "System.loadLibrary(\"sherpa-onnx-jni\") OK")
        } catch (t: Throwable) {
            CrashLogger.error(
                tag = tag,
                message = "loadLibrary 失败: libsherpa-onnx-jni.so 找不到/依赖缺失。ABI=${android.os.Build.SUPPORTED_ABIS.joinToString(",")}",
                throwable = t
            )
            throw t
        }

        // 1. VAD (固定使用 Silero)
        try {
            val vadFile = File("$baseDir/${sel.vad.dirName}/silero_vad.onnx")
            CrashLogger.log("INFO", tag, "VAD model path: ${vadFile.absolutePath}, exists=${vadFile.exists()}, size=${if (vadFile.exists()) vadFile.length() else -1}")
            vad = Vad(config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "$baseDir/${sel.vad.dirName}/silero_vad.onnx",
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = vadWindowSize,
                    maxSpeechDuration = 30.0f,
                ),
                sampleRate = sampleRate,
                numThreads = 1,
            ))
            CrashLogger.log("INFO", tag, "VAD initialized")
        } catch (t: Throwable) {
            CrashLogger.error(tag = tag, message = "VAD 初始化失败 (sel.vad=${sel.vad.id}, dir=${sel.vad.dirName})", throwable = t)
            throw t
        }

        // 2. ASR — 根据选择初始化流式或非流式
        try {
            when (sel.asr.id) {
                "asr_zipformer_bi" -> initStreamingAsr(baseDir, sel.asr.dirName)
                "asr_sense_voice" -> initSenseVoiceAsr(baseDir, sel.asr.dirName)
                "asr_paraformer" -> initParaformerAsr(baseDir, sel.asr.dirName)
                "asr_whisper_tiny" -> initWhisperAsr(baseDir, sel.asr.dirName)
            }
            CrashLogger.log("INFO", tag, "ASR initialized: ${sel.asr.id}")
        } catch (t: Throwable) {
            CrashLogger.error(tag = tag, message = "ASR 初始化失败 (sel.asr=${sel.asr.id}, dir=${sel.asr.dirName})", throwable = t)
            throw t
        }

        // 3. TTS — 根据选择初始化
        try {
            when (sel.tts.id) {
                "tts_kokoro_v11", "tts_kokoro_v10" -> initKokoroTts(baseDir, sel.tts.dirName)
                "tts_vits_zh" -> initVitsTts(baseDir, sel.tts.dirName)
                "tts_vits_melo" -> initVitsMeloTts(baseDir, sel.tts.dirName)
            }
            CrashLogger.log("INFO", tag, "TTS initialized: ${sel.tts.id}, sampleRate=${tts?.sampleRate()}")
        } catch (t: Throwable) {
            CrashLogger.error(tag = tag, message = "TTS 初始化失败 (sel.tts=${sel.tts.id}, dir=${sel.tts.dirName})", throwable = t)
            throw t
        }
    }

    private fun initStreamingAsr(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        onlineAsr = OnlineRecognizer(config = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$dir/encoder-epoch-99-avg-1.onnx",
                    decoder = "$dir/decoder-epoch-99-avg-1.onnx",
                    joiner = "$dir/joiner-epoch-99-avg-1.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "zipformer",
            ),
            endpointConfig = EndpointConfig(
                rule1 = EndpointRule(false, 2.4f, 0.0f),
                rule2 = EndpointRule(true, 1.2f, 0.0f),
                rule3 = EndpointRule(false, 0.0f, 20.0f),
            ),
            enableEndpoint = true,
            decodingMethod = "greedy_search",
        ))
    }

    private fun initSenseVoiceAsr(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        offlineAsr = OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                senseVoice = OfflineSenseVoiceModelConfig(
                    model = "$dir/model.int8.onnx",
                    language = "auto",
                    useInverseTextNormalization = true,
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "sense_voice",
            ),
            decodingMethod = "greedy_search",
        ))
    }

    private fun initParaformerAsr(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        offlineAsr = OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = "$dir/model.int8.onnx",
                ),
                tokens = "$dir/tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "paraformer",
            ),
            decodingMethod = "greedy_search",
        ))
    }

    private fun initWhisperAsr(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        offlineAsr = OfflineRecognizer(config = OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = "$dir/whisper-tiny-encoder.onnx",
                    decoder = "$dir/whisper-tiny-decoder.onnx",
                    language = "zh",
                    task = "transcribe",
                    tailPaddings = 1000,
                ),
                tokens = "$dir/tiny-tokens.txt",
                numThreads = 2,
                provider = "cpu",
                modelType = "whisper",
            ),
            decodingMethod = "greedy_search",
        ))
    }

    private fun initKokoroTts(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        // kokoro-multi-lang-v1_1 解压后包含 lexicon.txt + lexicon-zh.txt，sherpa-onnx 支持逗号分隔多 lexicon。
        // 如果某个不存在则跳过，避免 native 拿不到文件直接 abort 整个进程。
        val lexFiles = listOf("lexicon.txt", "lexicon-zh.txt")
            .map { File("$dir/$it") }
            .filter { it.exists() && it.length() > 0 }
            .joinToString(",") { it.absolutePath }
        CrashLogger.log("INFO", tag, "initKokoroTts dir=$dir, lexicon=$lexFiles, model=${File("$dir/model.onnx").exists()}, voices=${File("$dir/voices.bin").exists()}, tokens=${File("$dir/tokens.txt").exists()}, espeak=${File("$dir/espeak-ng-data").isDirectory}")
        CrashLogger.flushNow()
        tts = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                    lexicon = lexFiles,
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                provider = "cpu",
            ),
        ))
    }


    private fun initVitsTts(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        val modelFile = File(dir).listFiles()?.find { it.name.endsWith(".onnx") }
        tts = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = modelFile?.absolutePath ?: "$dir/model.onnx",
                    lexicon = "$dir/lexicon.txt",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        ))
    }

    private fun initVitsMeloTts(baseDir: String, dirName: String) {
        val dir = "$baseDir/$dirName"
        tts = OfflineTts(config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "$dir/vits-melo-tts-zh_en.onnx",
                    lexicon = "$dir/lexicon.txt",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                ),
                numThreads = 2,
                provider = "cpu",
            ),
        ))
    }

    // ==================== 音频初始化 ====================

    private fun initAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, vadWindowSize * 4)

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )
        audioPlayer = AudioPlayer(sampleRate = tts?.sampleRate() ?: 24000)
        audioRecord?.startRecording()
    }

    // ==================== 监听循环 ====================

    private suspend fun listenLoop() {
        val buffer = ShortArray(vadWindowSize)
        val floatBuffer = FloatArray(vadWindowSize)
        var streamingActive = false

        while (isRunning && scope.isActive) {
            // TTS 播放时暂停 VAD (回声消除)
            if (isSpeaking.get()) {
                delay(50); continue
            }

            val read = audioRecord?.read(buffer, 0, vadWindowSize) ?: -1
            if (read <= 0) { delay(10); continue }

            for (i in 0 until read) {
                floatBuffer[i] = buffer[i] / 32768.0f
            }

            vad?.acceptWaveform(floatBuffer.copyOf(read))

            // 处理 VAD 检测到的完整语音段
            while (vad?.empty() == false) {
                val segment = vad?.front() ?: break
                vad?.pop()

                if (segment.samples.size > sampleRate / 10) {
                    Log.i(tag, "Speech segment: ${segment.samples.size} samples")
                    processSpeechSegment(segment.samples)
                    streamingActive = false
                }
            }

            // 流式 ASR: 实时识别 (边说边出字)
            if (isStreamingAsr) {
                if (vad?.isSpeechDetected() == true && !streamingActive) {
                    streamingActive = true
                    asrStream = onlineAsr?.createStream()
                }
                if (streamingActive && vad?.isSpeechDetected() == true) {
                    asrStream?.acceptWaveform(floatBuffer.copyOf(read), sampleRate)
                    while (onlineAsr?.isReady(asrStream!!) == true) {
                        onlineAsr?.decode(asrStream!!)
                    }
                }
            }
        }
    }

    private suspend fun processSpeechSegment(samples: FloatArray) {
        try {
            service?.broadcastState("recognizing")

            var recognizedText = ""

            if (isStreamingAsr) {
                // 流式: 获取已累积的结果
                if (asrStream != null) {
                    while (onlineAsr?.isReady(asrStream!!) == true) {
                        onlineAsr?.decode(asrStream!!)
                    }
                    recognizedText = onlineAsr?.getResult(asrStream!!)?.text?.trim() ?: ""
                    asrStream?.release()
                    asrStream = null
                }
            } else {
                // 非流式: 对整段语音进行识别
                val stream = offlineAsr?.createStream()
                stream?.acceptWaveform(samples, sampleRate)
                offlineAsr?.decode(stream!!)
                recognizedText = offlineAsr?.getResult(stream!!)?.text?.trim() ?: ""
                stream?.release()
            }

            Log.i(tag, "Recognized: $recognizedText")

            if (recognizedText.isBlank()) {
                service?.broadcastState("listening")
                return
            }

            service?.broadcastConversation("user", recognizedText)

            // LLM
            service?.broadcastState("thinking")
            val response = llm.generateResponse(recognizedText)
            service?.broadcastConversation("assistant", response)

            // TTS
            service?.broadcastState("speaking")
            speak(response)

            // 恢复监听
            vad?.reset()
            service?.broadcastState("listening")

        } catch (t: Throwable) {
            // 同上：捕获 Throwable 兜底 native 抛的 Error
            CrashLogger.error(tag = tag, message = "processSpeechSegment 抛异常", throwable = t)
            service?.broadcastState("listening")
        }
    }

    private suspend fun speak(text: String) {
        if (text.isBlank()) return
        isSpeaking.set(true)
        try {
            val audio = tts?.generate(text, sid = 0, speed = 1.0f)
            if (audio != null) {
                val pcm = ShortArray(audio.samples.size)
                for (i in audio.samples.indices) {
                    pcm[i] = (audio.samples[i] * 32767).toInt().toShort()
                }
                audioPlayer?.play(pcm, audio.sampleRate)
                while (audioPlayer?.isPlaying == true) delay(50)
            }
        } finally {
            isSpeaking.set(false)
        }
    }
}
