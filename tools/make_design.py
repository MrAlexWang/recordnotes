"""用 App 实际页面重建 design/index.html，并注入浏览器演示桥（无原生环境时可用）。"""
import io
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP_PAGE = os.path.join(ROOT, "app", "src", "main", "assets", "www", "index.html")
OUT = os.path.join(ROOT, "design", "index.html")

SHIM = r"""
<script>
/* =====================================================================
   浏览器演示桥：没有 Android 原生环境时，用这份假数据把界面跑起来
   （真机 App 里这段不会生效，因为 window.Android 已存在）
   ===================================================================== */
(function () {
  if (window.Android) return;

  function silentWav(seconds, rate) {
    rate = rate || 16000;
    var n = Math.max(1, Math.floor(seconds * rate));
    var bytes = 44 + n * 2;
    var buf = new ArrayBuffer(bytes);
    var v = new DataView(buf);
    function wstr(off, s) { for (var i = 0; i < s.length; i++) v.setUint8(off + i, s.charCodeAt(i)); }
    wstr(0, 'RIFF'); v.setUint32(4, bytes - 8, true); wstr(8, 'WAVE');
    wstr(12, 'fmt '); v.setUint32(16, 16, true); v.setUint16(20, 1, true); v.setUint16(22, 1, true);
    v.setUint32(24, rate, true); v.setUint32(28, rate * 2, true); v.setUint16(32, 2, true); v.setUint16(34, 16, true);
    wstr(36, 'data'); v.setUint32(40, n * 2, true);
    return URL.createObjectURL(new Blob([buf], { type: 'audio/wav' }));
  }

  function stamp(ms) {
    var d = new Date(ms || Date.now());
    function p(n) { return String(n).padStart(2, '0'); }
    return '' + d.getFullYear() + p(d.getMonth() + 1) + p(d.getDate()) + p(d.getHours()) + p(d.getMinutes());
  }
  function env(count, seed) {
    var out = [], s = seed || 7;
    for (var i = 0; i < count; i++) {
      s = (s * 9301 + 49297) % 233280;
      var r = s / 233280;
      var e = 0.35 + 0.65 * Math.sin((i / count) * Math.PI);
      out.push(Math.round(Math.min(100, (0.15 + r * 0.85) * e * 100)));
    }
    return out;
  }
  function pad(n) { return String(n).padStart(2, '0'); }

  var now = Date.now();
  /* 演示用译文对照（真机上由腾讯 HY-MT1.5-1.8B 完成中英互译） */
  var DEMO_TR = {
    '好，人都到齐了，我们开始今天的第三次产品评审，主要过一下录音详情页。': 'Okay, everyone is here. Let us start today\'s third product review, focusing on the recording detail page.',
    '核心交互定为「原声和逐字稿放同一页」，播放的时候字跟着走。': 'The core interaction is "audio and transcript on the same page", with the text following the audio.',
    '自动滚动不能跟用户手动滑动打架，用户一滑动就暂停跟随，三秒后恢复。': 'Auto-scroll must not fight manual scrolling: pause following on touch, resume after three seconds.',
    '四种底色不是简单换色值，要重新调文字对比度和波形颜色。': 'The four themes are not just color swaps; text contrast and waveform colors need retuning.',
    '逐字稿正文字号建议十五，行高一点六倍，长文本读起来不累。': 'Use 15px body text with 1.6 line height for the transcript so long text stays readable.',
    '端侧转写中端机是实时的，但内存峰值两百多兆，要做按需加载。': 'On-device transcription is real-time on mid-range phones, but peak memory is 200MB+, so load on demand.',
    '长录音先出前三十秒结果，让用户看到进度，后台继续跑。': 'For long recordings, return the first thirty seconds first so users see progress while it keeps running.',
    '喂，早上好，我这边刚开完会，我们抓紧说十分钟。': 'Hi, good morning. I just finished a meeting, let us keep this to ten minutes.',
    '好的，主要想确认接口联调的时间点，前端已经跑通录音上传。': 'Sure, mainly to confirm the integration timeline. The front end already has recording upload working.',
    '上传你们先走 Mock，转写回调我周三之前把环境给你们。': 'Use a mock for upload first; I will give you the transcription callback environment by Wednesday.',
    'SDK 现在走内测通道，下午让测试同学把包发到你们测试机。': 'The SDK is on the internal channel now; QA will push the build to your test device this afternoon.',
    '突然想到一个点，记一下。': 'A quick idea just came to mind, let me note it down.',
    '详情页可以按说话人折叠，每个人一条时间线。': 'The detail page could collapse by speaker, one timeline per person.',
    '这样参会的人多，就不用从头听到尾了。': 'That way, with many attendees you do not have to listen from start to finish.',
    '这是演示模式生成的示例转写内容。': 'This is sample transcript text generated in demo mode.',
    '真机上这里会是 SenseVoice 的离线识别结果。': 'On a real device this would be the offline SenseVoice result.'
  };

  var RECS = [
    {
      id: 'demo1', title: '产品评审会 · 第 3 次',
      createdAt: now - 3600 * 1000, dateLabel: '今天', time: '10:24',
      duration: 758, size: '11.8 MB', lang: '普通话', speakers: 3,
      topic: '会议', fav: true, status: 'done', progress: 100,
      speakerPool: ['说话人 1', '说话人 2', '说话人 3', '旁听同事'],
      summary: '**V2 录音详情页**方案通过：原声与逐字稿联动、离线转写保证隐私、四套配色方案两周内交付。',
      transcript: [
        { t: 0, who: '说话人 1', text: '好，人都到齐了，我们开始今天的第三次产品评审，主要过一下录音详情页。' },
        { t: 14, who: '说话人 1', text: '核心交互定为「原声和逐字稿放同一页」，播放的时候字跟着走。' },
        { t: 35, who: '说话人 2', text: '自动滚动不能跟用户手动滑动打架，用户一滑动就暂停跟随，三秒后恢复。' },
        { t: 66, who: '说话人 3', text: '四种底色不是简单换色值，要重新调文字对比度和波形颜色。' },
        { t: 112, who: '说话人 3', text: '逐字稿正文字号建议十五，行高一点六倍，长文本读起来不累。' },
        { t: 156, who: '说话人 2', text: '端侧转写中端机是实时的，但内存峰值两百多兆，要做按需加载。' },
        { t: 205, who: '说话人 1', text: '长录音先出前三十秒结果，让用户看到进度，后台继续跑。' }
      ]
    },
    {
      id: 'demo2', title: '和 Ian 的电话沟通',
      createdAt: now - 2 * 3600 * 1000, dateLabel: '今天', time: '08:05',
      duration: 252, size: '4.1 MB', lang: '普通话 · 英文夹杂', speakers: 2,
      topic: '通话', fav: false, status: 'done', progress: 100,
      speakerPool: ['说话人 1', '说话人 2'],
      summary: 'Ian 周三前给联调环境，SDK 走内测通道，先用 Mock 数据打通上传与回调。',
      transcript: [
        { t: 0, who: '说话人 2', text: '喂，早上好，我这边刚开完会，我们抓紧说十分钟。' },
        { t: 9, who: '说话人 1', text: '好的，主要想确认接口联调的时间点，前端已经跑通录音上传。' },
        { t: 24, who: '说话人 2', text: '上传你们先走 Mock，转写回调我周三之前把环境给你们。' },
        { t: 52, who: '说话人 2', text: 'SDK 现在走内测通道，下午让测试同学把包发到你们测试机。' }
      ]
    },
    {
      id: 'demo3', title: '地铁上的灵感速记',
      createdAt: now - 26 * 3600 * 1000, dateLabel: '昨天', time: '21:40',
      duration: 47, size: '0.8 MB', lang: '普通话', speakers: 1,
      topic: '灵感', fav: true, status: 'done', progress: 100,
      speakerPool: ['说话人 1'],
      summary: '按说话人折叠录音：每个人一条时间线，先看谁说了什么，再点开听原声。',
      transcript: [
        { t: 0, who: '说话人 1', text: '突然想到一个点，记一下。' },
        { t: 4, who: '说话人 1', text: '详情页可以按说话人折叠，每个人一条时间线。' },
        { t: 20, who: '说话人 1', text: '这样参会的人多，就不用从头听到尾了。' }
      ]
    },
    {
      id: 'demo4', title: '客户需求访谈 · 王总',
      createdAt: now - 27 * 3600 * 1000, dateLabel: '昨天', time: '15:20',
      duration: 1565, size: '24.6 MB', lang: '普通话', speakers: 2,
      topic: '客户', fav: false, status: 'processing', progress: 62,
      speakerPool: [],
      summary: '', transcript: []
    }
  ];

  RECS.forEach(function (r, i) {
    r.env = env(160, 11 + i * 7);
    r.url = silentWav(Math.max(5, r.duration));
  });

  function stateObj() {
    return { records: RECS, model: { ready: true, busy: false, progress: 100, message: '演示模式' }, port: 0 };
  }
  function state() { return JSON.stringify(stateObj()); }
  function emit(ev, payload) {
    if (window.__native) window.__native(ev, payload);
  }
  function push() { emit('state', stateObj()); }
  function find(id) { for (var i = 0; i < RECS.length; i++) if (RECS[i].id === id) return RECS[i]; return null; }

  function fakeTranscribe(rec) {
    rec.status = 'processing';
    rec.progress = 0;
    push();
    var step = 0;
    var timer = setInterval(function () {
      step += 12 + Math.round(Math.random() * 10);
      if (step >= 100) {
        step = 100;
        clearInterval(timer);
        rec.status = 'done';
        rec.progress = 100;
        rec.lang = rec.lang || '普通话';
        rec.speakers = rec.speakers || 1;
        rec.transcript = rec.transcript.length ? rec.transcript : [
          { t: 0, who: '说话人 1', text: '这是演示模式生成的示例转写内容。' },
          { t: 6, who: '说话人 2', text: '真机上这里会是 SenseVoice 的离线识别结果。' }
        ];
        rec.summary = rec.summary || '演示模式：这里是自动生成的摘要。';
        push();
        emit('asrDone', { id: rec.id });
        return;
      }
      rec.progress = step;
      push();
      emit('asrProgress', { id: rec.id, progress: step, message: '正在转写' });
    }, 420);
  }

  window.Android = {
    getState: function () { return state(); },
    getModel: function () { return JSON.stringify({ ready: true, busy: false, progress: 100, message: '演示模式' }); },
    setSystemTheme: function () {},
    startRecord: function () {
      this._rec = true;
      this._t0 = Date.now();
      emit('recordStarted', '{}');
    },
    pauseRecord: function () {
      if (!this._rec) return;
      this._paused = true;
      this._pausedAt = Date.now();
      emit('recordPaused', { elapsed: Date.now() - this._t0 });
    },
    resumeRecord: function () {
      if (!this._rec) return;
      if (this._pausedAt) { this._t0 += Date.now() - this._pausedAt; this._pausedAt = 0; }
      this._paused = false;
      emit('recordResumed', { elapsed: Date.now() - this._t0 });
    },
    getRecordingState: function () {
      return JSON.stringify({
        running: !!this._rec,
        paused: !!this._paused,
        elapsed: this._rec ? Date.now() - this._t0 - (this._pausedAt ? Date.now() - this._pausedAt : 0) : 0
      });
    },
    stopRecord: function () {
      if (!this._rec) return;
      this._rec = false;
      this._paused = false;
      var dur = Math.max(2, Math.round((Date.now() - this._t0) / 1000));
      var rec = {
        id: 'demo' + Date.now(), title: '录音' + stamp(),
        createdAt: Date.now(), dateLabel: '今天', time: stamp().slice(8, 10) + ':' + stamp().slice(10, 12),
        duration: dur, size: '0.6 MB', lang: '普通话', speakers: 1,
        topic: '未分类', fav: false, status: 'processing', progress: 0,
        speakerPool: [], summary: '', transcript: [],
        env: env(160, 3), url: silentWav(Math.max(5, dur))
      };
      RECS.unshift(rec);
      push();
      emit('recordStopped', { id: rec.id, duration: dur });
      fakeTranscribe(rec);
    },
    pickAudio: function () {
      var rec = {
        id: 'demoimp' + Date.now(), title: '导入的音频（演示）',
        createdAt: Date.now(), dateLabel: '今天', time: stamp().slice(8, 10) + ':' + stamp().slice(10, 12),
        duration: 96, size: '3.2 MB', lang: '普通话', speakers: 2,
        topic: '导入', fav: false, status: 'processing', progress: 0,
        speakerPool: [], summary: '', transcript: [],
        env: env(160, 5), url: silentWav(96)
      };
      RECS.unshift(rec);
      push();
      fakeTranscribe(rec);
    },
    transcribe: function (id) { var r = find(id); if (r) fakeTranscribe(r); },
    translateRecord: function (id) {
      var r = find(id);
      if (!r || !r.transcript.length) return;
      var i = 0;
      var timer = setInterval(function () {
        if (i >= r.transcript.length) {
          clearInterval(timer);
          emit('translated', { id: id });
          return;
        }
        var seg = r.transcript[i++];
        if (!seg.tr) {
          var hit = DEMO_TR[seg.text];
          seg.tr = hit || (/[\u4e00-\u9fff]/.test(seg.text)
            ? '[EN] ' + seg.text
            : '[中文] ' + seg.text);
        }
        push();
      }, 240);
    },
    patch: function (id, json) {
      var r = find(id);
      if (!r) return;
      try {
        var p = JSON.parse(json);
        Object.keys(p).forEach(function (k) { r[k] = p[k]; });
      } catch (e) {}
      push();
    },
    remove: function (id) {
      for (var i = 0; i < RECS.length; i++) {
        if (RECS[i].id === id) { RECS.splice(i, 1); break; }
      }
      push();
    },
    exportText: function (name, mime, content) {
      var blob = new Blob([content], { type: mime || 'text/plain' });
      var a = document.createElement('a');
      a.href = URL.createObjectURL(blob);
      a.download = name;
      a.click();
    },
    share: function (content) {
      if (navigator.share) { navigator.share({ text: content }).catch(function () {}); return; }
      if (navigator.clipboard) navigator.clipboard.writeText(content).catch(function () {});
    },
    toast: function (msg) { console.log('[toast]', msg); }
  };

})();
</script>
"""


APP_OUT = os.path.join(ROOT, "design", "app.html")
SHOWCASE_OUT = os.path.join(ROOT, "design", "index.html")

# 变更记录（最新在前）
CHANGELOG = [
    ("v17", "手动转写 · 搜索全文 · 话题持久化", [
        "录完 / 导入后不再自动转写，卡片右上角改成「开始转写」按钮",
        "搜索支持逐字稿与译文内容，忽略空格与大小写",
        "话题与配色改存原生，重启 App 不再丢失；历史话题自动出现在筛选里",
    ], True),
    ("v16", "回收站 · 话题精简 · 翻译即时", [
        "删除改为进回收站，设置里可恢复 / 彻底删除；示例录音删掉后不再自动重建",
        "话题只保留「未分类」，其余由用户自己添加；旧内置话题一次性归入未分类",
        "「打开」去掉底部「浏览其它位置」，只列本机受支持音频",
        "删除只删记录，不动手机里的原始音频文件",
        "多选时隐藏左滑按钮，修复收藏/重命名/删除重复显示",
        "翻译完成后立即刷新界面，不用重启",
        "修改逐字稿后旧译文自动失效，再次翻译会重新生成",
    ]),
    ("v15", "界面精简 · 本机音频列表", [
        "列表卡片去掉播放图标，点卡片直接进详情",
        "逐字稿标题精简为「逐字稿」，复制按钮不再被挤成竖排",
        "摘要页去掉引擎前缀与「转写信息」整块",
        "Markdown 去掉引擎前缀与页脚说明",
        "「打开」改为直接列出本机音频（只含受支持格式）",
    ], True),
    ("v14", "打开本地音频 · 原文+译文并存", [
        "顶栏「导入」移入设置抽屉，改名为「打开」",
        "文件选择器按音频 MIME 过滤，只显示受支持的格式",
        "翻译后原文与译文同时显示，译文缓存在录音里",
        "再次点「翻译」直接展开缓存结果，不重新推理",
    ]),
    ("v13", "详情页精简", [
        "移除标签行里的「说话人 N」按钮，标签行只保留 话题 / 翻译 / 收藏 / 重新转写",
        "说话人管理入口统一为：点逐字稿里的说话人名字",
        "字幕与 Markdown 同步保留，功能未减少",
    ]),
    ("v12", "翻译 · HY-MT1.5-1.8B", [
        "详情页新增「翻译」按钮，位于「说话人」右侧，原按钮保留",
        "中文→英文 / 英文→中文，逐句翻译并在本地缓存",
        "首次使用下载 Q4 模型 1.08 GB（魔搭镜像），之后完全离线",
        "翻译中按钮显示进度，可随时切回「原文」",
    ]),
    ("v11", "录音暂停 + 后台录音", [
        "录音胶囊右侧新增「暂停 / 继续」按钮，暂停时计时停走、电平冻结",
        "新增前台录音服务：桌面长按「开始录音」直接后台录音",
        "通知栏可暂停 / 继续 / 结束，回到 App 自动同步录音状态",
    ]),
    ("v10", "声纹记忆 + 横排配色", [
        "同一把声音跨录音复用名字；用户命名过的名字匹配阈值再放宽",
        "设置抽屉改为一排四个配色色块，点选即切换并自动收起",
        "人声管理支持跨录音改名、清空声纹档案",
    ]),
    ("v9", "说话人管理", [
        "单条录音：已有发言者按句数列出，可直接改名",
        "可新增「还没有句子」的空说话人，再把勾选的句子批量标识给他",
    ]),
    ("v8", "导入与列表修复", [
        "导入前说明支持的 12 种格式，选择器放宽到 ogg / flac / mp4 等容器",
        "解不出音轨的文件自动跳过并提示，不留在列表里",
        "修复卡片被 flex 压缩、只剩标题行的显示问题",
    ]),
    ("v7", "单句编辑 / 桌面快捷方式 / 示例录音", [
        "逐字稿每句可改说话人与文字（铅笔按钮或长按整句）",
        "桌面长按 App：录音开关、导入录音",
        "新建录音命名 录音202611151352；首次启动自动导入示例对话",
    ]),
    ("v6", "时间戳 / 禁止选中 / 语种与分人 / 后台停播", [
        "录音时间统一显示 202611151352（年月日时分）",
        "全局禁止文字选中，长按不再弹出选区菜单（输入框除外）",
        "语种检测：普通话 · 英文夹杂；CAM++ 声纹聚类自动分人",
        "退到后台自动停止播放，录音继续",
    ]),
    ("v5", "Android App · 离线转写", [
        "SenseVoice-Small int8 全离线转写，录音不出手机",
        "原生录音 / 导入 / 播放，本地 HTTP 支持拖动进度",
    ]),
    ("v4", "左滑与一键复制", [
        "左滑卡片出「收藏 / 重命名 / 删除」",
        "逐字稿、摘要、Markdown 三个页签各一键复制",
    ]),
    ("v3", "全面屏避让 + 长按多选", [
        "注入系统栏高度，内容不再被状态栏遮挡",
        "长按进入多选：收藏 / 分享 / 批量重命名（自动追加时间）/ 删除",
    ]),
    ("v2", "详情页增强", [
        "时间筛选支持自定义起止日期",
        "纸张色增加真实纸纹；导出集中到导出面板",
        "详情标签行：话题可编辑、收藏、重新转写",
    ]),
    ("v1", "原型定型", [
        "录音列表 + 详情（原声波形 + 逐字稿）",
        "四套配色：中心灰 / 米白 / 豆沙绿 / 纸张色",
        "Markdown 渲染、0.5–3.0 倍速、弹簧录音键",
    ]),
]

SHOWCASE_HEAD = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1" />
<title>录音笔记 · 界面与变更记录</title>
<style>
  :root {
    --bg: #0b0d12;
    --panel: #14171e;
    --panel-2: #1a1e26;
    --line: rgba(255,255,255,.08);
    --text: #e8ecf3;
    --text-2: #9aa4b2;
    --text-3: #6f7a8a;
    --accent: #85aef4;
    --accent-soft: rgba(133,174,244,.14);
  }
  * { box-sizing: border-box; }
  html, body { margin: 0; height: 100%; }
  body {
    background:
      radial-gradient(120% 80% at 12% -10%, rgba(133,174,244,.10), transparent 60%),
      radial-gradient(90% 60% at 105% 105%, rgba(164,79,40,.10), transparent 65%),
      var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "HarmonyOS Sans SC",
                 "MiSans", "Noto Sans SC", "Microsoft YaHei", sans-serif;
    -webkit-font-smoothing: antialiased;
    overflow: hidden;
  }
  header {
    height: 62px; display: flex; align-items: center; gap: 14px;
    padding: 0 26px; border-bottom: 1px solid var(--line);
  }
  header h1 { font-size: 16px; margin: 0; letter-spacing: .2px; font-weight: 700; }
  header .sub { font-size: 12px; color: var(--text-3); }
  header .grow { flex: 1; }
  header .ver {
    font-size: 11px; font-weight: 700; color: var(--accent);
    background: var(--accent-soft); padding: 4px 9px; border-radius: 7px;
  }
  header a {
    font-size: 12px; color: var(--text-2); text-decoration: none;
    border: 1px solid var(--line); padding: 7px 12px; border-radius: 10px;
  }
  header a:hover { color: var(--text); border-color: rgba(255,255,255,.2); }

  main {
    display: flex; gap: 34px; align-items: flex-start;
    padding: 22px 26px 26px;
    height: calc(100vh - 62px);
  }

  /* ---------- 左：手机 + 页面 ---------- */
  .stage { flex: 1; min-width: 0; display: flex; flex-direction: column; align-items: center; gap: 12px; }
  .phone-frame {
    height: calc(100vh - 150px);
    max-height: 812px;
    aspect-ratio: 390 / 812;
    border-radius: 46px;
    overflow: hidden;
    background: #ede2cd;
    box-shadow:
      0 0 0 10px #1b1f27,
      0 0 0 12px #2a303b,
      0 30px 60px rgba(0,0,0,.62);
  }
  .phone-frame iframe {
    width: 100%; height: 100%; border: 0; display: block; background: #ede2cd;
  }
  .stage-hint { font-size: 11.5px; color: var(--text-3); text-align: center; line-height: 1.6; }
  .stage-hint b { color: var(--text-2); font-weight: 600; }

  /* ---------- 右：变更记录 ---------- */
  .log {
    flex: 0 0 396px; width: 396px;
    height: calc(100vh - 150px);
    background: linear-gradient(180deg, var(--panel), var(--panel-2));
    border: 1px solid var(--line);
    border-radius: 20px;
    padding: 16px 16px 6px;
    display: flex; flex-direction: column;
  }
  .log-head { display: flex; align-items: baseline; gap: 10px; padding: 0 4px 10px; }
  .log-head h2 { font-size: 14px; margin: 0; font-weight: 700; letter-spacing: .2px; }
  .log-head span { font-size: 11px; color: var(--text-3); }
  .log-body { overflow-y: auto; padding-right: 6px; flex: 1; }
  .log-body::-webkit-scrollbar { width: 6px; }
  .log-body::-webkit-scrollbar-thumb { background: rgba(255,255,255,.12); border-radius: 3px; }

  ol.timeline { list-style: none; margin: 0; padding: 0 0 0 6px; }
  ol.timeline li {
    position: relative;
    padding: 0 0 15px 20px;
    border-left: 1px dashed rgba(255,255,255,.10);
  }
  ol.timeline li:last-child { border-left-color: transparent; padding-bottom: 4px; }
  ol.timeline li .dot {
    position: absolute; left: -5px; top: 5px;
    width: 9px; height: 9px; border-radius: 50%;
    background: #2c333f; border: 1px solid rgba(255,255,255,.18);
  }
  ol.timeline li.current .dot {
    background: var(--accent); border-color: var(--accent);
    box-shadow: 0 0 0 4px rgba(133,174,244,.16);
  }
  .meta { display: flex; align-items: center; gap: 8px; }
  .meta .ver {
    font-size: 10.5px; font-weight: 700; color: var(--accent);
    background: var(--accent-soft); padding: 2px 7px; border-radius: 6px;
  }
  li.current .meta .ver { color: #0f1620; background: var(--accent); }
  .meta .date { font-size: 10.5px; color: var(--text-3); letter-spacing: .3px; }
  .meta .cur {
    margin-left: auto; font-size: 10px; color: var(--accent);
    border: 1px solid rgba(133,174,244,.35); padding: 1px 6px; border-radius: 5px;
  }
  li h3 { font-size: 13px; font-weight: 700; margin: 7px 0 6px; color: var(--text); }
  li ul { margin: 0; padding: 0; list-style: none; }
  li ul li {
    font-size: 11.8px; line-height: 1.62; color: var(--text-2);
    padding: 2px 0 2px 11px; border: 0; position: relative;
  }
  li ul li::before {
    content: ''; position: absolute; left: 2px; top: 10px;
    width: 4px; height: 4px; border-radius: 50%; background: rgba(255,255,255,.22);
  }

  @media (max-width: 1080px) {
    body { overflow: auto; }
    main { flex-direction: column; height: auto; align-items: stretch; gap: 20px; }
    .phone-frame { height: 72vh; }
    .log { flex: none; width: 100%; height: auto; max-height: none; }
    .log-body { overflow: visible; }
  }
</style>
</head>
<body>
<header>
  <h1>录音笔记</h1>
  <span class="sub">界面与变更记录 · 左侧为真实页面，可直接交互</span>
  <span class="grow"></span>
  <span class="ver">Android 已同步</span>
  <a href="app.html" target="_blank" rel="noopener">打开独立页面</a>
</header>
<main>
  <section class="stage">
    <div class="phone-frame">
      <iframe id="appFrame" src="app.html" title="录音笔记"></iframe>
    </div>
    <div class="stage-hint">
      示例数据 · <b>点右下角麦克风</b>可试录音动效，<b>右上齿轮</b>为设置，点任意录音进详情
    </div>
  </section>

  <aside class="log">
    <div class="log-head">
      <h2>变更记录</h2>
      <span>__VCOUNT__ 个版本</span>
    </div>
    <div class="log-body">
      <ol class="timeline">
"""


def render_showcase() -> str:
    rows = []
    for ver, title, bullets, *rest in CHANGELOG:
        cur = bool(rest and rest[0])
        items = "".join("<li>%s</li>" % b for b in bullets)
        rows.append(
            '<li class="%s">\n  <span class="dot"></span>\n'
            '  <div class="meta"><span class="ver">%s</span><span class="date">2026-11-15</span>%s</div>\n'
            '  <h3>%s</h3>\n  <ul>%s</ul>\n</li>\n'
            % ("current" if cur else "", ver, '<span class="cur">当前</span>' if cur else "", title, items)
        )
    body = SHOWCASE_HEAD.replace("__VCOUNT__", str(len(CHANGELOG)))
    return (
        body
        + "".join(rows)
        + "</ol>\n    </div>\n  </aside>\n</main>\n</body>\n</html>\n"
    )


def main() -> None:
    with io.open(APP_PAGE, encoding="utf-8") as f:
        page = f.read()

    # 找到内联的最后一个 <script>，在它前面插入演示桥
    idx = page.rfind("<script>")
    if idx < 0:
        raise SystemExit("找不到 <script> 标签")
    out = page[:idx] + SHIM.strip() + "\n" + page[idx:]

    # 演示页固定使用纸张色并隐藏启动页
    out = out.replace("let saved = 'paper';", "let saved = 'paper';  /* 演示默认纸张色 */", 1)

    os.makedirs(os.path.join(ROOT, "design"), exist_ok=True)
    with io.open(APP_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(out)
    print("wrote", APP_OUT, len(out), "chars")

    showcase = render_showcase()
    with io.open(SHOWCASE_OUT, "w", encoding="utf-8", newline="\n") as f:
        f.write(showcase)
    print("wrote", SHOWCASE_OUT, len(showcase), "chars")


if __name__ == "__main__":
    main()
