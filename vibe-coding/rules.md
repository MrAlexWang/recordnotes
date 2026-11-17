# 工程规则 · vibe-coding

> 这是示例模板，规则可以按需要改。改动方式：加一条 `R-xx`，或直接修改现有条目。
> 编号只增不复用，删掉的规则标记 ~~删除线~~ 并写原因。

## 0. 一句话背景

RecordNotes 是一个**完全离线**的 Android 语音笔记：录音 → 离线转写（SenseVoice / Paraformer）→ 说话人分离 → 离线翻译（llama.cpp + GGUF）→ 导出。
界面是 WebView 里的单页 HTML/JS，功能靠原生桥接。**不用 Gradle**，用 `build.ps1` 直连 aapt2 + javac + d8 + apksigner。

## 1. 目录与真相来源

| 位置 | 说明 | 能不能直接改 |
|---|---|---|
| `app/src/main/assets/www/body.html` | 页面结构 + 全部交互 JS | ✅ 改这里 |
| `app/src/main/assets/www/extra.css` | Android 端样式 | ✅ 改这里 |
| `design/base.css` | 基础样式（配色变量、主题） | ✅ 改这里 |
| `app/src/main/assets/www/index.html` | **生成物**（base.css + extra.css + body.html） | ❌ 会被覆盖 |
| `design/app.html` `design/index.html` | **生成物**（网页预览 + 变更记录） | ❌ 会被覆盖 |
| `app/java/com/phonesar/notes/*.java` | 原生（注意路径没有 `src/main/java`） | ✅ 改这里 |
| `dist/RecordNotes.apk` | 构建产物 | ❌ 不入库 |
| `tools/make_www.py` `tools/make_design.py` | 页面合成 / 网页预览 | ✅ |
| `build-llama/jni/` | 翻译 JNI（llama.cpp） | ✅ 少动 |

## 2. 硬性规则

**R-01** 只改源文件。`index.html`、`design/app.html`、`build/`、`dist/*.apk` 都是产物，改完源码重新生成，不要手改产物。

**R-02** 页面改动一律落在 `body.html` / `extra.css` / `base.css`，然后跑 `python tools/make_www.py`。（`build.ps1` 第 2 步也会自动跑，所以改完直接 build 也行。）

**R-03** 新增任何中文界面文案，必须同步在 `body.html` 的 `EN` 字典里补英文对照；同一句话里带数字的，优先在 `EN_RULES` 加正则。
用户正文（逐字稿、译文、摘要、Markdown）所在的容器必须标 `data-no-i18n`，避免把用户内容当界面文案翻掉。

**R-04** 模型文件一律不打进 APK。新增可选模型时在 `ModelManager` 里加一条 `Spec`：
- 填 `file` / `urls`（主源 + 备用源）/ `bytes` / `family`
- 直链必须先用 `curl -sIL <url>` 验证能返回 `200` 且有 `Content-Length`，不要凭印象写地址
- 转义模型 `family`：`sensevoice` / `paraformer`；翻译模型：`hymt`（翻译专用提示词）/ `chat`（通用对话提示词）

**R-05** 设置抽屉很窄（`max(25vw, 172px)`）。里面：
- 单行文字加 `white-space: nowrap` + `text-overflow: ellipsis` + `min-width: 0`
- 多列网格必须写 `repeat(n, minmax(0, 1fr))`，不能写 `repeat(n, 1fr)`（英文长文案会把列顶出容器）
- 不在抽屉行里塞长副标题，需要的话放 `title` 或放进二级弹层

**R-06** 页面与原生只通过 `MainActivity.JsBridge` 的 `@JavascriptInterface` 通信（JS 侧 `NATIVE.xxx`）。新增接口必须：桥里加方法 → 页面里加 `if (!NATIVE || !NATIVE.xxx) return;` 保护 → 网页演示桥 `tools/make_design.py` 里补一个假的实现。

**R-07** 耗时操作（转写 / 翻译 / 下载 / 导出）走 `pool` 单线程执行器，进度用 `emit("事件名", json)` 回传，JS 在 `window.__native` 里加分支处理。不要在工作线程碰 UI。

**R-08** 数据统一走 `Store`（`records.json`，删除是软删除进回收站）。**任何情况下不删手机里的原始音频文件**。

**R-09** 不引入 Gradle、不引入前端框架/构建工具（Vue/React/Webpack 等）、不增加除"模型下载 + 用户主动分享"以外的联网行为。

**R-10** 交付前必须跑完「第 4 节 验证清单」，全绿才算改完。

**R-11** 每次有可见变化，在 `tools/make_design.py` 的 `CHANGELOG` 顶部加一条 `vNN`，并在 `requirements.md` 把对应需求状态改成「已完成」。

**R-12** 中文注释、写清"为什么"而不是"做了什么"。改一处就只改那一处，不要顺手重排/重写整个文件。

## 3. 代码风格

- Java：4 空格缩进，类内按「常量 → 字段 → 构造 → 公开方法 → 私有方法 → JNI」排列，异常最多吞到 `Log.w` 级别并带上下文。
- JS：2 空格缩进、单引号、`function` 声明优先（依赖函数提升）；DOM 操作统一 `$` / `$$` 选择器；文本一律 `escapeHtml` 后再拼 HTML。
- CSS：全部规则缩进在 2 空格内（Android 风格），变量用 `--surface` / `--text-3` / `--accent` 等既有变量，不新造色值。

## 4. 验证清单（Definition of Done）

```powershell
# 1) 合成页面（产物被覆盖是正常的）
python tools/make_www.py

# 2) 重新生成网页预览
python tools/make_design.py

# 3) 编译打包
powershell -ExecutionPolicy Bypass -File build.ps1

# 4) 装到设备（adb 在 .toolchain/sdk/platform-tools/adb.exe）
.toolchain\sdk\platform-tools\adb.exe install -r -d dist\RecordNotes.apk
```

- [ ] `build.ps1` 输出「构建完成」，没有 javac 报错
- [ ] JS 语法通过（可选：`pip install esprima` 后用 esprima 解析 `index.html` 里的 `<script>`）
- [ ] i18n：切到 English 后界面上没有残留中文（用户正文除外）
- [ ] 新增/修改的文案在 `rules.md` R-03 的字典里都有对照
- [ ] 涉及模型：直链 curl 过、`ModelManager` 里 `bytes` 与实际一致
- [ ] 涉及原生桥：网页预览里对应入口不报错（演示桥有假实现）
- [ ] `CHANGELOG` 与 `requirements.md` 已更新

## 5. 常见坑（踩过就记下来）

| 现象 | 原因 | 处理 |
|---|---|---|
| 改了样式没生效 | 改的是 `index.html` | 改 `body.html` / `extra.css` 源文件 |
| 英文界面某处还是中文 | `EN` 字典漏了，或该文案在 HTML 模板串里 | 补字典条目 / 补 `EN_RULES` 正则 |
| 英文长文案把布局顶破 | `1fr` 网格 + `nowrap` 子元素 | 改 `minmax(0, 1fr)`，子元素加 `min-width:0` |
| 设置抽屉内容超出屏幕 | 抽屉是固定 25vw 宽 | 抽屉加 `overflow-y:auto`，行内容省略号 |
| 切模型后没生效 | 引擎缓存了已加载的模型 | 切模型后 `asr.release()` / `translator.release()` |
| 下载进度卡在 0 | `bytes` 写错导致 `ready()` 判定失败 | 用 `curl -sIL` 的 `Content-Length` 校准 |
| PowerShell 截图/重定向文件损坏 | `>` 会按文本转码 | 用 `adb shell screencap -p /sdcard/x.png` + `adb pull` |
