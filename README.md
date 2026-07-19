# 🔊 PeekTTS — 离线语音助手

> 无唤醒词 · 全离线 · 后台保活 · 随时语音交互的 Android 智能语音助手

PeekTTS 是一个类似小米小爱同学和 Siri 的 Android 语音助手应用。与主流语音助手不同，PeekTTS **不需要唤醒词**，你随时对着手机说话，它就会立即用语音回复你。

## ✨ 核心特性

- 🎤 **无唤醒词** — 不需要说"小爱同学"或"嘿Siri"，直接说话即可触发
- 🔒 **全离线运行** — ASR、VAD、TTS 全部在手机本地推理，无需联网
- 🔄 **后台保活** — Foreground Service 持续运行，随时待命
- 🧠 **智能对话** — 内置意图识别和规则引擎，后续可接入本地 LLM
- 🌏 **中英双语** — 语音识别和语音合成均支持中文和英文

## 🏗️ 技术架构

```
麦克风采集 → VAD(语音活动检测) → ASR(语音转文字) → LLM(智能回复) → TTS(文字转语音) → 播放
```

### 技术栈

| 组件 | 方案 | 说明 |
|------|------|------|
| VAD 语音活动检测 | Silero VAD (sherpa-onnx) | ~2MB，替代唤醒词，持续监听 |
| ASR 语音识别 | Zipformer 流式 (sherpa-onnx) | 中英双语流式识别 |
| TTS 语音合成 | Kokoro-82M (sherpa-onnx) | 82M参数，高质量中英双语 |
| LLM 大模型 | 规则引擎 (Phase 1) → llama.cpp + Qwen2.5 (Phase 2) | 意图识别与对话生成 |
| 后台保活 | Foreground Service | 常驻通知栏 + 开机自启 |
| 推理引擎 | ONNX Runtime | sherpa-onnx 框架统一管理 |

### 核心依赖

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 一站式语音处理框架 (ASR + VAD + TTS)
- [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) — 开源 TTS 模型 (Apache 2.0)

## 📱 模型说明

App 首次启动时需要下载语音模型（约 350MB），存储在应用内部目录：

| 模型 | 大小 | 用途 |
|------|------|------|
| silero_vad.onnx | ~2MB | 语音活动检测 |
| Zipformer ASR | ~50MB | 流式语音识别 (中英双语) |
| Kokoro-82M TTS | ~300MB | 语音合成 (中英双语, 103个音色) |

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34
- NDK (用于 sherpa-onnx 原生库)

### 本地编译

```bash
# 1. Clone the repo
git clone https://github.com/<your-username>/PeekTTS.git
cd PeekTTS

# 2. Download sherpa-onnx native libraries
chmod +x scripts/download_native_libs.sh
./scripts/download_native_libs.sh v1.13.4

# 3. Open in Android Studio and build, or use Gradle:
./gradlew assembleRelease

# 4. Find APK at app/build/outputs/apk/release/
```

### CI 自动构建

本项目配置了 GitHub Actions 自动构建：
- 每次 push 到 main/master 分支自动触发
- 使用内置 keystore 签名 release APK
- 构建完成后 APK 自动归档到 `apk/` 目录
- 同时上传为 GitHub Actions Artifact

## 📂 项目结构

```
PeekTTS/
├── app/
│   ├── src/main/
│   │   ├── java/com/peektts/
│   │   │   ├── PeekTTSApp.kt          # Application 入口
│   │   │   ├── MainActivity.kt         # 主界面
│   │   │   ├── AssistantService.kt      # 前台服务(保活)
│   │   │   ├── AssistantEngine.kt       # 核心编排引擎
│   │   │   ├── ModelManager.kt          # 模型下载管理
│   │   │   ├── BootReceiver.kt          # 开机自启
│   │   │   ├── audio/AudioPlayer.kt     # 音频播放
│   │   │   └── llm/LlmEngine.kt         # LLM 引擎(规则引擎)
│   │   ├── java/com/k2fsa/sherpa/onnx/ # sherpa-onnx Kotlin API
│   │   └── res/                         # 布局和资源
│   └── build.gradle.kts
├── keystore/peektts.keystore.p12        # 签名密钥库
├── scripts/download_native_libs.sh      # 原生库下载脚本
├── .github/workflows/build-apk.yml      # CI 构建脚本
└── apk/                                 # 构建产物存放
```

## 🗺️ 开发路线图

### Phase 1: MVP (当前 ✅)
- [x] Foreground Service 后台保活
- [x] VAD 无唤醒词持续监听
- [x] 流式 ASR 语音识别
- [x] Kokoro TTS 语音合成
- [x] 规则引擎意图识别
- [x] 模型下载管理
- [x] CI 自动构建 + APK 归档

### Phase 2: 智能对话 (规划中)
- [ ] 集成 llama.cpp + Qwen2.5-1.5B 本地 LLM
- [ ] LLM function calling 控制页面跳转
- [ ] 语音打断 (说话时停止 TTS 播放)
- [ ] 对话上下文记忆

### Phase 3: 交互增强 (规划中)
- [ ] 悬浮窗快速交互
- [ ] 应用控制 (打开微信/支付宝等)
- [ ] 系统功能 (设闹钟/查天气/打电话)
- [ ] 多音色切换

### Phase 4: 优化 (规划中)
- [ ] 模型量化加速
- [ ] 耗电优化
- [ ] 多机型适配
- [ ] 回声消除算法

## ⚠️ 注意事项

- 模型较大(~350MB)，建议在 WiFi 环境下首次下载
- 后台保活在国产 ROM (小米/华为/OPPO/vivo) 上需要手动设置白名单
- App 需要麦克风和通知权限
- 持续监听会增加电池消耗

## 📄 License

MIT License - 详见 [LICENSE](LICENSE)

## 🙏 鸣谢

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) — 新一代 Kaldi 语音处理框架
- [Kokoro-82M](https://github.com/hexgrad/kokoro) — 开源 TTS 模型
- [llama.cpp](https://github.com/ggml-org/llama.cpp) — LLM 推理引擎
