package com.peektts

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.peektts.audio.AudioPlayer
import com.peektts.llm.LlmEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AssistantEngine — 核心编排引擎
 *
 * Pipeline: 麦克风采集 → VAD检测 → ASR识别 → LLM推理 → TTS合成 → 播放
 *
 * 无唤醒词设计：VAD 持续监听，检测到人声自动触发完整对话流程。
 * 回声处理：TTS 播放时暂停 VAD，播放完毕后恢复监听。
 */
class AssistantEngine(
    private val context: Context,
) {
    private val tag = "AssistantEngine"
    private val service: AssistantService? = context as? AssistantService

    var isRunning = false
        private set

    private val isSpeaking = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var listenJob: Job? = null

    // sherpa-onnx components
    private var vad: Vad? = null
    private var asr: OnlineRecognizer? = null
    private var tts: OfflineTts? = null
    private var asrStream: OnlineStream? = null

    // LLM engine (placeholder for llama.cpp integration)
    private val llm = LlmEngine()

    // Audio
    private var audioRecord: AudioRecord? = null
    private var audioPlayer: AudioPlayer? = null

    // Configuration
    private val sampleRate = 16000
    private val vadSilenceDuration = 0.5f  // seconds of silence to detect end of speech
    private val vadWindowSize = 512         // samples per VAD window

    private val modelManager = ModelManager(context)

    enum class EngineState {
        IDLE, LISTENING, RECOGNIZING, THINKING, SPEAKING
    }

    fun start() {
        if (isRunning) return

        try {
            initModels()
            initAudio()
            isRunning = true
            service?.broadcastState("listening")
            listenJob = scope.launch { listenLoop() }
        } catch (e: Exception) {
            Log.e(tag, "Failed to start engine", e)
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
        vad?.release()
        vad = null
        asr?.release()
        asr = null
        tts?.release()
        tts = null
        scope.cancel()
    }

    private fun initModels() {
        val modelDir = modelManager.getModelDir().absolutePath

        // 1. Init VAD (Silero VAD)
        val vadConfig = VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = "$modelDir/silero_vad/silero_vad.onnx",
                threshold = 0.5f,
                minSilenceDuration = vadSilenceDuration,
                minSpeechDuration = 0.25f,
                windowSize = vadWindowSize,
                maxSpeechDuration = 30.0f,
            ),
            sampleRate = sampleRate,
            numThreads = 1,
            provider = "cpu",
        )
        vad = Vad(config = vadConfig)
        Log.i(tag, "VAD initialized")

        // 2. Init Streaming ASR (Zipformer)
        val asrConfig = OnlineRecognizerConfig(
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = "$modelDir/zipformer-asr/encoder-epoch-99-avg-1.onnx",
                    decoder = "$modelDir/zipformer-asr/decoder-epoch-99-avg-1.onnx",
                    joiner = "$modelDir/zipformer-asr/joiner-epoch-99-avg-1.onnx",
                ),
                tokens = "$modelDir/zipformer-asr/tokens.txt",
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
        )
        asr = OnlineRecognizer(config = asrConfig)
        Log.i(tag, "ASR initialized")

        // 3. Init TTS (Kokoro)
        val ttsConfig = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$modelDir/kokoro-tts/model.onnx",
                    voices = "$modelDir/kokoro-tts/voices.bin",
                    tokens = "$modelDir/kokoro-tts/tokens.txt",
                    dataDir = "$modelDir/kokoro-tts/espeak-ng-data",
                    lexicon = "$modelDir/kokoro-tts/lexicon.txt",
                    lengthScale = 1.0f,
                ),
                numThreads = 4,
                provider = "cpu",
            ),
        )
        tts = OfflineTts(config = ttsConfig)
        Log.i(tag, "TTS initialized, sampleRate=${tts?.sampleRate()}")
    }

    private fun initAudio() {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBuf * 2, vadWindowSize * 2 * 2)

        @Suppress("MissingPermission")
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioPlayer = AudioPlayer(
            sampleRate = tts?.sampleRate() ?: 24000,
            channelConfig = AudioFormat.CHANNEL_OUT_MONO,
        )

        audioRecord?.startRecording()
        Log.i(tag, "AudioRecord started, bufferSize=$bufferSize")
    }

    /**
     * Main listening loop — VAD driven, no wake word needed
     */
    private suspend fun listenLoop() {
        val buffer = ShortArray(vadWindowSize)
        val floatBuffer = FloatArray(vadWindowSize)
        var speechBuffer = mutableListOf<Float>()
        var isInSpeech = false

        while (isRunning && !scope.isActive.not()) {
            // Pause VAD during TTS playback (echo cancellation)
            if (isSpeaking.get()) {
                delay(50)
                continue
            }

            val read = audioRecord?.read(buffer, 0, vadWindowSize) ?: -1
            if (read <= 0) {
                delay(10)
                continue
            }

            // Convert 16-bit PCM to float [-1, 1]
            for (i in 0 until read) {
                floatBuffer[i] = buffer[i] / 32768.0f
            }

            // Feed to VAD
            vad?.acceptWaveform(floatBuffer.copyOf(read))

            // Check for speech segments
            while (vad?.empty() == false) {
                val segment = vad?.front() ?: break
                vad?.pop()

                if (segment.samples.size > sampleRate / 10) {  // min 100ms speech
                    Log.i(tag, "Speech segment: ${segment.samples.size} samples (${segment.samples.size.toFloat() / sampleRate}s)")
                    processSpeechSegment(segment.samples)
                }
            }

            // Also do real-time ASR streaming while speech is ongoing
            if (vad?.isSpeechDetected() == true && !isInSpeech) {
                isInSpeech = true
                asrStream = asr?.createStream()
                service?.broadcastState("recognizing")
            }

            if (isInSpeech && vad?.isSpeechDetected() == true) {
                asrStream?.acceptWaveform(floatBuffer.copyOf(read), sampleRate)
                while (asr?.isReady(asrStream!!) == true) {
                    asr?.decode(asrStream!!)
                }
            }
        }
    }

    /**
     * Process a complete speech segment (VAD detected end of speech)
     */
    private suspend fun processSpeechSegment(samples: FloatArray) {
        try {
            // Get final ASR result from the streaming session
            var recognizedText = ""
            if (asrStream != null) {
                while (asr?.isReady(asrStream!!) == true) {
                    asr?.decode(asrStream!!)
                }
                val result = asr?.getResult(asrStream!!)
                recognizedText = result?.text?.trim() ?: ""
                asrStream?.release()
                asrStream = null
            }

            // If streaming didn't capture it, do offline recognition
            if (recognizedText.isEmpty()) {
                recognizedText = recognizeOffline(samples)
            }

            Log.i(tag, "Recognized: $recognizedText")

            if (recognizedText.isBlank()) {
                service?.broadcastState("listening")
                return
            }

            // Broadcast recognized text
            service?.broadcastConversation("user", recognizedText)

            // 3. LLM reasoning
            service?.broadcastState("thinking")
            val response = llm.generateResponse(recognizedText)
            Log.i(tag, "LLM response: $response")
            service?.broadcastConversation("assistant", response)

            // 4. TTS synthesis
            service?.broadcastState("speaking")
            speak(response)

            // 5. Resume listening
            vad?.reset()
            service?.broadcastState("listening")

        } catch (e: Exception) {
            Log.e(tag, "Error processing speech segment", e)
            service?.broadcastState("listening")
        }
    }

    private fun recognizeOffline(samples: FloatArray): String {
        // For now, return empty - streaming ASR should have captured it
        // Could add offline recognizer here as fallback
        return ""
    }

    /**
     * Synthesize speech and play it
     */
    private suspend fun speak(text: String) {
        if (text.isBlank()) return

        isSpeaking.set(true)
        try {
            val audio = tts?.generate(text, sid = 0, speed = 1.0f)
            if (audio != null) {
                // Convert float [-1,1] to 16-bit PCM
                val pcm = ShortArray(audio.samples.size)
                for (i in audio.samples.indices) {
                    pcm[i] = (audio.samples[i] * 32767).toInt().toShort()
                }

                // Play audio
                audioPlayer?.play(pcm, audio.sampleRate)

                // Wait for playback to finish
                while (audioPlayer?.isPlaying == true) {
                    delay(50)
                }
            }
        } finally {
            isSpeaking.set(false)
        }
    }
}
