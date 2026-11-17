# 录音笔记 / RecordNotes

一个完全离线的 Android 语音记录 App：录音 → 离线转写 → 说话人分离 → 翻译 → 导出。
An offline-first Android voice-notes app: record → on-device transcription → speaker diarization → translation → export.

> 本仓库为源码工程，使用 aapt2 + javac + d8 + apksigner 直接构建，不依赖 Gradle。
> Source-only project, built with aapt2 + javac + d8 + apksigner (no Gradle).

---

## 中文说明

### 功能

- **录音**：原生录音（AAC / m4a），录音中可暂停 / 继续，计时按有效时长累加
- **后台录音**：桌面长按图标 →「开始录音」，直接后台录制（前台服务 + 通知栏暂停 / 结束），点开 App 自动同步状态
- **离线转写**：SenseVoice-Small（int8），录音不出手机；首次使用才下载模型
- **说话人分离**：CAM++ 声纹聚类 + 跨录音声纹记忆，同一把声音沿用已验证的名字
- **逐字稿编辑**：每句可单独改说话人与文字；支持批量标识句子、跨录音统一改名
- **翻译**：腾讯 HY-MT1.5-1.8B（GGUF，Q4_K_M，1.08 GB），中英互译，原文与译文并存并缓存
- **列表**：长按多选（收藏 / 分享 / 批量重命名 / 删除）、左滑快捷操作、全文搜索（含转写与译文）
- **回收站**：删除先进回收站，可恢复或彻底删除；不会删除手机里的原始音频
- **导出**：Markdown / TXT / SRT，三个页签各自一键复制
- **外观**：四套配色（中心灰 / 米白 / 豆沙绿 / 纸张色）、中英双语（设置内切换）

### 模型（均不打进 APK，按需下载）

| 用途 | 模型 | 体积 | 下载时机 |
|---|---|---|---|
| 转写 | SenseVoice-Small int8 + tokens | ≈ 228 MB | 首次点「开始转写」 |
| 说话人 | 3D-Speaker CAM++（中英） | ≈ 27 MB | 同上（可选，失败不影响转写） |
| 翻译 | 腾讯 HY-MT1.5-1.8B Q4_K_M | ≈ 1.08 GB | 首次点「翻译」（魔搭 ModelScope 源） |

APK 本体约 **27 MB**。

### 构建

前置：Windows + PowerShell；工程内自带工具链时可直接构建。

```powershell
# 1) 生成页面（App 页面 + 设计预览）
python tools/make_www.py
python tools/make_design.py

# 2) 构建签名 APK → dist/RecordNotes.apk
powershell -ExecutionPolicy Bypass -File build.ps1

# 3) 安装到已连接设备
adb install -r -d dist/RecordNotes.apk
```

可选：如需开启翻译功能，先编译原生库（llama.cpp，arm64）：

```powershell
# 见 build-llama/jni/CMakeLists.txt，需 Android NDK + CMake
# 产物 build-llama/out/arm64-v8a/libhytrans.so 会被 build.ps1 自动打包
```

### 工程结构

```
app/
  AndroidManifest.xml              清单（前台服务 / 权限 / 快捷方式）
  java/com/phonesar/notes/
    MainActivity.java              WebView 壳、桥接、导入导出、回收站
    RecordingService.java          前台录音服务（通知栏暂停 / 结束）
    Recorder.java                  录音封装（含暂停与净时长）
    AsrEngine.java                 SenseVoice 转写 + 声纹聚类 + 模型下载
    VoiceGallery.java              跨录音声纹档案
    TranslationEngine.java         HY-MT1.5 下载与推理（JNI）
    AudioDecode.java               任意音频 → 16k 单声道 PCM
    MiniServer.java                本地 HTTP（页面 + 音频，支持 Range）
    Store.java                     记录持久化（records.json，含软删除）
  src/main/assets/www/             界面（body.html / extra.css / index.html）
  src/main/assets/samples/         首次启动导入的示例对话
  res/                             图标、快捷方式、字符串
build-llama/jni/                   翻译引擎 JNI 封装（llama.cpp）
design/                            浏览器预览页（自动生成，含演示数据）
tools/                             生成脚本（页面合成、AAR 处理、APK 合并）
build.ps1                          一键构建
```

### 隐私

- 转写、说话人分离、翻译全部在本机完成，音频不上传
- 仅两处联网：下载模型（转写 / 翻译），以及你主动「分享 / 导出」

### 已知限制

- 说话人分离基于声纹聚类，音色接近或抢话时可能合并
- 转写为整段离线识别（静音切 4–12 秒片段），时间戳是片段起点，非逐字级
- 摘要为本地首句抽取，未接大模型

---

## English

### Features

- **Recording**: native AAC/m4a capture with pause/resume; timer counts effective duration only
- **Background recording**: long-press the launcher icon → "Start recording" runs in a foreground service with notification actions (pause / stop); reopening the app syncs the live state
- **On-device transcription**: SenseVoice-Small (int8); audio never leaves the device; models are downloaded on first use
- **Speaker diarization**: CAM++ embeddings with a cross-recording voiceprint gallery, so a verified voice keeps its name
- **Transcript editing**: edit speaker or text per sentence, bulk-assign sentences, rename a voice across all recordings
- **Translation**: Tencent HY-MT1.5-1.8B (GGUF Q4_K_M, 1.08 GB), bidirectional zh⇄en, original and translation shown together and cached
- **List**: long-press multi-select (favorite / share / batch rename / delete), swipe actions, full-text search including transcripts and translations
- **Trash**: deletions go to a recycle bin with restore or purge; source audio files on the phone are never touched
- **Export**: Markdown / TXT / SRT, plus one-tap copy for each pane
- **Appearance**: four themes, Chinese/English UI (switchable in Settings)

### Models (not bundled in the APK)

| Purpose | Model | Size | When |
|---|---|---|---|
| Transcription | SenseVoice-Small int8 + tokens | ≈ 228 MB | first "Start transcribe" |
| Speakers | 3D-Speaker CAM++ (zh/en) | ≈ 27 MB | same (optional) |
| Translation | Tencent HY-MT1.5-1.8B Q4_K_M | ≈ 1.08 GB | first "Translate" (ModelScope) |

The APK itself is about **27 MB**.

### Build

```powershell
python tools/make_www.py        # bundle the web UI
python tools/make_design.py     # regenerate the browser preview
powershell -ExecutionPolicy Bypass -File build.ps1   # → dist/RecordNotes.apk
adb install -r -d dist/RecordNotes.apk
```

Optionally build the translation engine (llama.cpp, arm64) into `build-llama/out/arm64-v8a/libhytrans.so`;
`build.ps1` picks it up automatically.

### Privacy

Everything (ASR, diarization, translation) runs locally. Network is used only to download models and
when you explicitly share or export content.

### Limitations

- Diarization is embedding-based; similar voices or overlapping speech may merge
- Transcription is chunk-based (4–12 s slices), so timestamps mark chunk starts, not words
- Summaries come from local extractive rules, not an LLM

---

## License / 许可

代码可自由用于学习与个人项目；第三方模型与运行时遵循其各自许可：
SenseVoice（FunASR）、3D-Speaker CAM++、腾讯 HY-MT1.5、sherpa-onnx（Apache-2.0）、llama.cpp（MIT）。

Code is free for study and personal projects. Bundled/downloaded models and runtimes keep their own licenses:
SenseVoice (FunASR), 3D-Speaker CAM++, Tencent HY-MT1.5, sherpa-onnx (Apache-2.0), llama.cpp (MIT).
