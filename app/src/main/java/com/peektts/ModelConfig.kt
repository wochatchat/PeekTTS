package com.peektts

/**
 * ModelConfig — 所有可选模型定义
 *
 * 每种模型类型(VAD/ASR/TTS)提供多个选项，每个选项有多个镜像下载源。
 * 下载时依次尝试各镜像源，直到成功。
 */

/** 下载源镜像 */
enum class MirrorSource(val id: String, val label: String) {
    HF("hf", "HuggingFace (国际)"),
    HF_MIRROR("hfm", "HF-Mirror (国内加速)"),
    GITHUB("gh", "GitHub Releases"),
    MODELSCOPE("ms", "ModelScope (魔搭)"),
}

/** 单个模型文件定义 */
data class ModelFile(
    val name: String,           // 目标文件名/路径
    val sources: List<String>,  // 各镜像源的完整URL，按优先级排列
)

/** 模型选项 */
data class ModelOption(
    val id: String,
    val name: String,
    val description: String,
    val sizeText: String,       // 如 "~300MB"
    val dirName: String,        // 模型存储子目录名
    val files: List<ModelFile>, // 需要下载的文件列表
    val isArchive: Boolean = false, // 是否为压缩包(需解压)
    val archiveName: String = "",   // 压缩包文件名(如果isArchive)
)

/** 模型选择集合 */
data class ModelSelection(
    val vad: ModelOption,
    val asr: ModelOption,
    val tts: ModelOption,
)

object ModelRegistry {

    // ==================== VAD 模型 ====================
    val VAD_SILERO = ModelOption(
        id = "vad_silero",
        name = "Silero VAD",
        description = "语音活动检测 (必选)",
        sizeText = "~2MB",
        dirName = "silero_vad",
        files = listOf(
            ModelFile(
                name = "silero_vad.onnx",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/silero_vad.onnx",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/silero_vad.onnx",
                )
            )
        )
    )

    // ==================== ASR 语音识别模型 ====================

    /** Zipformer 双语流式 (中+英) — 默认推荐 */
    val ASR_ZIPFORMER_BILINGUAL = ModelOption(
        id = "asr_zipformer_bi",
        name = "Zipformer 中英双语 (流式)",
        description = "流式识别，边说边出字，延迟低。中英文混合识别效果好",
        sizeText = "~50MB",
        dirName = "zipformer-asr",
        isArchive = true,
        archiveName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
        files = listOf(
            ModelFile(
                name = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20/resolve/main/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                )
            )
        )
    )

    /** SenseVoice — 阿里达摩院，中文极强，非流式 */
    val ASR_SENSE_VOICE = ModelOption(
        id = "asr_sense_voice",
        name = "SenseVoice (阿里·中文极强)",
        description = "非流式识别，中文准确率极高，自带标点和语种检测。需说完一句话后识别",
        sizeText = "~230MB",
        dirName = "sense-voice-asr",
        isArchive = true,
        archiveName = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
        files = listOf(
            ModelFile(
                name = "sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
                    "https://huggingface.co/pcsuke/sense-voice/resolve/main/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
                    "https://hf-mirror.com/pcsuke/sense-voice/resolve/main/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17.tar.bz2",
                )
            )
        )
    )

    /** Paraformer — 阿里FunASR，中文非流式 */
    val ASR_PARAFORMER = ModelOption(
        id = "asr_paraformer",
        name = "Paraformer (阿里FunASR·中文)",
        description = "非流式识别，中文效果好，模型适中。适合纯中文场景",
        sizeText = "~220MB",
        dirName = "paraformer-asr",
        isArchive = true,
        archiveName = "sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
        files = listOf(
            ModelFile(
                name = "sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14/resolve/main/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2",
                )
            )
        )
    )

    /** Whisper tiny — 多语言，轻量 */
    val ASR_WHISPER_TINY = ModelOption(
        id = "asr_whisper_tiny",
        name = "Whisper Tiny (多语言·轻量)",
        description = "OpenAI Whisper tiny版，支持多语言，体积小但准确率一般",
        sizeText = "~40MB",
        dirName = "whisper-tiny-asr",
        isArchive = true,
        archiveName = "sherpa-onnx-whisper-tiny.tar.bz2",
        files = listOf(
            ModelFile(
                name = "sherpa-onnx-whisper-tiny.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-tiny.tar.bz2",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/sherpa-onnx-whisper-tiny.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main/sherpa-onnx-whisper-tiny.tar.bz2",
                )
            )
        )
    )

    val ASR_OPTIONS = listOf(
        ASR_ZIPFORMER_BILINGUAL,
        ASR_SENSE_VOICE,
        ASR_PARAFORMER,
        ASR_WHISPER_TINY,
    )

    // ==================== TTS 语音合成模型 ====================

    /** Kokoro 多语言 v1.1 — 最佳质量，中英双语，103个音色 */
    val TTS_KOKORO_V11 = ModelOption(
        id = "tts_kokoro_v11",
        name = "Kokoro v1.1 (最佳质量·中英)",
        description = "82M参数，中英双语，103个音色，质量媲美大模型。推荐首选",
        sizeText = "~350MB",
        dirName = "kokoro-tts",
        isArchive = true,
        archiveName = "kokoro-multi-lang-v1_1.tar.bz2",
        files = listOf(
            ModelFile(
                name = "kokoro-multi-lang-v1_1.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_1.tar.bz2",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-kokoro-multi-lang-v1_1/resolve/main/kokoro-multi-lang-v1_1.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-kokoro-multi-lang-v1_1/resolve/main/kokoro-multi-lang-v1_1.tar.bz2",
                )
            )
        )
    )

    /** Kokoro 多语言 v1.0 — 较新，53个音色 */
    val TTS_KOKORO_V10 = ModelOption(
        id = "tts_kokoro_v10",
        name = "Kokoro v1.0 (中英·53音色)",
        description = "82M参数，中英双语，53个音色，体积稍小",
        sizeText = "~300MB",
        dirName = "kokoro-tts",
        isArchive = true,
        archiveName = "kokoro-multi-lang-v1_0.tar.bz2",
        files = listOf(
            ModelFile(
                name = "kokoro-multi-lang-v1_0.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
                    "https://huggingface.co/csukuangfj/sherpa-onnx-kokoro-multi-lang-v1_0/resolve/main/kokoro-multi-lang-v1_0.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/sherpa-onnx-kokoro-multi-lang-v1_0/resolve/main/kokoro-multi-lang-v1_0.tar.bz2",
                )
            )
        )
    )

    /** VITS 中文 — 轻量，仅中文 */
    val TTS_VITS_ZH = ModelOption(
        id = "tts_vits_zh",
        name = "VITS 中文 (轻量·仅中文)",
        description = "经典VITS模型，仅中文，体积小速度快。适合不需要英文的场景",
        sizeText = "~100MB",
        dirName = "vits-zh-tts",
        isArchive = true,
        archiveName = "vits-icefall-zh-aishell3.tar.bz2",
        files = listOf(
            ModelFile(
                name = "vits-icefall-zh-aishell3.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2",
                    "https://huggingface.co/csukuangfj/vits-icefall-zh-aishell3/resolve/main/vits-icefall-zh-aishell3.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/vits-icefall-zh-aishell3/resolve/main/vits-icefall-zh-aishell3.tar.bz2",
                )
            )
        )
    )

    /** VITS MeloTTS — 中英，较小 */
    val TTS_VITS_MELO = ModelOption(
        id = "tts_vits_melo",
        name = "VITS MeloTTS (中英·较小)",
        description = "MeloTTS模型，中英双语，体积适中",
        sizeText = "~120MB",
        dirName = "vits-melo-tts",
        isArchive = true,
        archiveName = "vits-melo-tts-zh_en.tar.bz2",
        files = listOf(
            ModelFile(
                name = "vits-melo-tts-zh_en.tar.bz2",
                sources = listOf(
                    "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2",
                    "https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/resolve/main/vits-melo-tts-zh_en.tar.bz2",
                    "https://hf-mirror.com/csukuangfj/vits-melo-tts-zh_en/resolve/main/vits-melo-tts-zh_en.tar.bz2",
                )
            )
        )
    )

    val TTS_OPTIONS = listOf(
        TTS_KOKORO_V11,
        TTS_KOKORO_V10,
        TTS_VITS_ZH,
        TTS_VITS_MELO,
    )

    // ==================== 默认选择 ====================
    val DEFAULT_SELECTION = ModelSelection(
        vad = VAD_SILERO,
        asr = ASR_ZIPFORMER_BILINGUAL,
        tts = TTS_KOKORO_V11,
    )

    /** 根据ID查找模型选项 */
    fun findAsr(id: String): ModelOption = ASR_OPTIONS.find { it.id == id } ?: ASR_ZIPFORMER_BILINGUAL
    fun findTts(id: String): ModelOption = TTS_OPTIONS.find { it.id == id } ?: TTS_KOKORO_V11
}
