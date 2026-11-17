package com.phonesar.notes;

import android.Manifest;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 录音笔记主界面：WebView + 原生录音/导入 + SenseVoice 离线转写。 */
public class MainActivity extends Activity {

    private static final String TAG = "Main";
    private static final int REQ_PERM = 10;
    private static final int REQ_PICK = 11;

    private WebView web;
    private MiniServer server;
    private Store store;
    private ModelManager models;
    private AsrEngine asr;
    private File audioDir;
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    private final Set<String> running = Collections.synchronizedSet(new HashSet<String>());
    private final Set<String> translating = Collections.synchronizedSet(new HashSet<String>());
    private TranslationEngine translator;
    private final Map<String, Integer> lastPct = Collections.synchronizedMap(new HashMap<String, Integer>());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);

        store = new Store(this);
        store.load();

        audioDir = new File(getFilesDir(), "audio");
        if (!audioDir.exists() && !audioDir.mkdirs()) Log.w(TAG, "mkdir audio failed");

        models = new ModelManager(this);
        asr = new AsrEngine(this, models);
        translator = new TranslationEngine(this, models);
        try {
            server = new MiniServer(this, audioDir);
        } catch (Exception e) {
            Log.e(TAG, "server start failed", e);
        }

        web = new WebView(this);
        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setMediaPlaybackRequiresUserGesture(false);
        ws.setAllowFileAccess(false);
        ws.setTextZoom(100);
        ws.setSupportZoom(false);
        web.setBackgroundColor(0xFF2B2F35);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                pushInsets();
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new Bridge(), "Android");
        setContentView(web);
        setupEdgeToEdge();

        String url = server != null
                ? "http://127.0.0.1:" + server.port() + "/www/index.html"
                : "file:///android_asset/www/index.html";
        web.loadUrl(url);

        requestAudioPermission();
        requestNotifyPermission();
        prepareModelAsync();
        ensureSampleImported();
        consumePendingRecord();

        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            runSelfTest();
        }
        if (getIntent() != null && getIntent().getBooleanExtra("rectest", false)) {
            runRecordTest();
        }
        if (getIntent() != null && getIntent().getBooleanExtra("trtest", false)) {
            runTranslateTest();
        }
        if (getIntent() != null && getIntent().getBooleanExtra("trrec", false)) {
            runTranslateRecordTest();
        }
        handleOpenAction(getIntent());
    }

    /* ---------------- 桌面快捷方式 ---------------- */

    private boolean pageReady = false;
    /** 待页面消费的动作（冷启动时页面还没加载好，等它来取）。 */
    private String pendingAction = null;

    private void handleOpenAction(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("openAction");
        if (action == null) return;
        intent.removeExtra("openAction");
        if ("recordStart".equals(action)) {
            /* 快捷方式：直接后台开始录音，界面退到后台，通知栏可暂停/结束 */
            if (!RecordingService.isRunning()) startRecord();
            runOnUiThread(() -> moveTaskToBack(true));
        } else if ("recordToggle".equals(action)) {
            if (RecordingService.isRunning()) stopRecord();
            else startRecord();
        } else if ("import".equals(action)) {
            /* 和界面里点「导入」一致：先弹出导入面板（格式说明 + 选择文件） */
            if (pageReady) {
                emit("openImport", "{}");
            } else {
                pendingAction = "import";
            }
        }
    }

    /** 读取本机音频列表需要媒体权限（Android 13+ 为 READ_MEDIA_AUDIO）。 */
    void ensureAudioReadPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.READ_MEDIA_AUDIO") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.READ_MEDIA_AUDIO"}, 13);
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 13);
            }
        }
    }

    private void requestNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 12);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleOpenAction(intent);
    }

    /* ---------------- 首次启动导入示例录音 ---------------- */

    private void ensureSampleImported() {
        final android.content.SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
        if (sp.getBoolean("sample_removed", false)) return;
        pool.execute(() -> {
            if (sp.getBoolean("sample_removed", false)) return;
            try {
                File dst = new File(audioDir, "sample_demo.wav");
                long assetSize = 0;
                try (InputStream probe = getAssets().open("samples/demo_dialog.wav")) {
                    byte[] b = new byte[1 << 16];
                    int r;
                    while ((r = probe.read(b)) > 0) assetSize += r;
                }
                boolean needCopy = !dst.exists() || dst.length() != assetSize;
                if (!needCopy && store.get("sample_demo") != null) return;
                if (needCopy) copyAssetTo("samples/demo_dialog.wav", dst);

                JSONObject rec = store.get("sample_demo");
                if (rec == null) {
                    rec = newRecord("sample_demo", "示例对话（普通话 / 粤语 / 英语）", dst);
                }
                store.put(rec);
                store.save();
                emitState();
                sp.edit().putBoolean("sample_imported", true).apply();
                Log.w(TAG, "sample imported size=" + assetSize);
            } catch (Exception e) {
                Log.e(TAG, "sample import failed", e);
            }
        });
    }

    /* 录音链路自检：开始 → 暂停 → 继续 → 结束 → 落库转写 */
    private void runRecordTest() {
        final StringBuilder sb = new StringBuilder();
        pool.execute(() -> {
            try {
                startRecord();
                Thread.sleep(2500);
                sb.append("running=").append(RecordingService.isRunning());
                sb.append(" elapsed=").append(RecordingService.elapsedMs() / 1000).append('s');
                long beforePause = RecordingService.elapsedMs();

                pauseRecord();
                Thread.sleep(900);
                sb.append(" paused=").append(RecordingService.isPaused());
                long paused1 = RecordingService.elapsedMs();
                Thread.sleep(1200);
                long paused2 = RecordingService.elapsedMs();
                sb.append(" frozenWhilePaused=").append(paused2 - paused1 < 300);

                resumeRecord();
                Thread.sleep(1500);
                sb.append(" resumed=").append(!RecordingService.isPaused());
                sb.append(" growth=").append((RecordingService.elapsedMs() - paused2) / 1000).append('s');

                int before = store.all().size();
                stopRecord();
                Thread.sleep(2500);
                int after = store.all().size();
                sb.append(" stopping=").append(!RecordingService.isRunning());
                sb.append(" recordsDelta=").append(after - before);
                sb.append(" beforePause=").append(beforePause / 1000).append('s');
            } catch (Throwable t) {
                sb.append(" FAILED ").append(t);
            }
            Log.w(TAG, "rectest " + sb);
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    try (OutputStream o = new FileOutputStream(new File(dir, "rectest.txt"))) {
                        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /* 翻译落库自检：整条录音翻译，确认译文写进存储（下次打开可直接显示） */
    private void runTranslateRecordTest() {
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                JSONObject rec = store.get("sample_demo");
                if (rec == null) throw new IllegalStateException("缺少 sample_demo");
                translateRecord("sample_demo");
                long t0 = System.currentTimeMillis();
                while (System.currentTimeMillis() - t0 < 180000) {
                    Thread.sleep(1000);
                    JSONObject cur = store.get("sample_demo");
                    JSONArray tr = cur == null ? null : cur.optJSONArray("transcript");
                    if (tr == null || tr.length() == 0) continue;
                    int with = 0;
                    for (int i = 0; i < tr.length(); i++) {
                        JSONObject o = tr.optJSONObject(i);
                        if (o != null && !o.optString("tr", "").isEmpty()) with++;
                    }
                    if (with >= tr.length()) break;
                }
                JSONObject cur = store.get("sample_demo");
                JSONArray tr = cur.optJSONArray("transcript");
                int with = 0;
                for (int i = 0; i < tr.length(); i++) {
                    JSONObject o = tr.optJSONObject(i);
                    String txt = o.optString("text");
                    String trt = o.optString("tr", "");
                    if (!trt.isEmpty()) with++;
                    sb.append("[").append(i).append("] ").append(txt).append('\n')
                            .append("    -> ").append(trt).append('\n');
                }
                sb.append("segs=").append(tr.length()).append(" withTr=").append(with);
            } catch (Throwable t) {
                sb.append("FAILED ").append(t);
            }
            Log.w(TAG, "trrec " + sb.toString().replace('\n', ' '));
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    try (OutputStream o = new FileOutputStream(new File(dir, "trrec.txt"))) {
                        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
        }).start();
    }

    /* 翻译自检：加载 HY-MT1.5-1.8B，试翻中英各一句 */
    private void runTranslateTest() {
        pool.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                sb.append("nativeLib=").append(TranslationEngine.hasNative()).append('\n');
                sb.append("modelReady=").append(translator.isReady()).append('\n');
                long t0 = System.currentTimeMillis();
                translator.load(4);
                sb.append("loadMs=").append(System.currentTimeMillis() - t0).append('\n');

                long t1 = System.currentTimeMillis();
                String en = translator.translate("开饭时间早上9点至下午5点。");
                sb.append("zh2en(").append(System.currentTimeMillis() - t1).append("ms)=").append(en).append('\n');

                long t2 = System.currentTimeMillis();
                String zh = translator.translate("The tribal chieftain called for the boy.");
                sb.append("en2zh(").append(System.currentTimeMillis() - t2).append("ms)=").append(zh).append('\n');
            } catch (Throwable t) {
                Log.e(TAG, "trtest failed", t);
                sb.append("FAILED ").append(t);
            }
            Log.w(TAG, "trtest " + sb.toString().replace('\n', ' '));
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    try (OutputStream o = new FileOutputStream(new File(dir, "trtest.txt"))) {
                        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void copyAssetTo(String asset, File dst) throws java.io.IOException {
        try (InputStream in = getAssets().open(asset);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    /* 自检：用内置测试音频跑一遍完整链路，结果写到外部文件方便 adb 取回 */
    private void runSelfTest() {
        pool.execute(() -> {
            StringBuilder sb = new StringBuilder();
            try {
                if (!asr.isModelReady()) asr.extractIfNeeded((p, c) -> { });
                sb.append("modelReady=").append(asr.isModelReady()).append('\n');
                File wav = new File(getCacheDir(), "selftest.wav");
                copyAssetTo("test/zh.wav", wav);
                AudioDecode.Pcm pcm = AudioDecode.decode(wav);
                sb.append("pcmSeconds=").append(pcm.durationSec()).append('\n');
                asr.init();
                List<AsrEngine.Seg> segs = asr.transcribe(pcm, (p, c) -> { });
                for (AsrEngine.Seg s : segs) {
                    sb.append('[').append(s.startSec).append("s ").append(s.who).append("] ").append(s.text).append('\n');
                }
                sb.append("segments=").append(segs.size())
                        .append(" lang=").append(asr.lastLangLabel).append('\n');

                /* 双人双语样音：验证语种混合与说话人分离 */
                File mix = new File(getCacheDir(), "selftest_mix.wav");
                copyAssetTo("test/mix.wav", mix);
                AudioDecode.Pcm mpcm = AudioDecode.decode(mix);
                List<AsrEngine.Seg> msegs = asr.transcribe(mpcm, null);
                sb.append("[mix] seconds=").append(mpcm.durationSec())
                        .append(" lang=").append(asr.lastLangLabel).append('\n');
                for (AsrEngine.Seg s : msegs) {
                    sb.append("  [").append(s.startSec).append("s ").append(s.who).append("] ")
                            .append(s.text).append('\n');
                }
                sb.append("mixSegments=").append(msegs.size()).append('\n');

                /* 四段不同音色：验证能分出多个说话人 */
                File mix4 = new File(getCacheDir(), "selftest_mix4.wav");
                copyAssetTo("test/mix4.wav", mix4);
                AudioDecode.Pcm p4 = AudioDecode.decode(mix4);
                List<AsrEngine.Seg> s4 = asr.transcribe(p4, null);
                sb.append("[mix4] lang=").append(asr.lastLangLabel).append('\n');
                for (AsrEngine.Seg s : s4) {
                    sb.append("  [").append(s.startSec).append("s ").append(s.who).append("] ")
                            .append(s.text).append('\n');
                }
                java.util.LinkedHashSet<String> spk4 = new java.util.LinkedHashSet<>();
                for (AsrEngine.Seg s : s4) spk4.add(s.who);
                sb.append("mix4Segments=").append(s4.size()).append(" mix4Speakers=").append(spk4.size())
                        .append(" names=").append(spk4).append('\n');

                /* 第二遍同样的音频：应当直接复用上一遍记下的名字（跨录音声纹记忆） */
                List<AsrEngine.Seg> s4b = asr.transcribe(p4, null);
                java.util.LinkedHashSet<String> spk4b = new java.util.LinkedHashSet<>();
                for (AsrEngine.Seg s : s4b) spk4b.add(s.who);
                sb.append("[mix4-pass2] names=").append(spk4b)
                        .append(" gallery=").append(asr.gallery().info());
            } catch (Throwable t) {
                Log.e(TAG, "selftest failed", t);
                sb.append("FAILED: ").append(t);
            }
            Log.w(TAG, "selftest " + sb.toString().replace('\n', ' '));
            runOnUiThread(() -> {
                if (web == null) return;
                web.evaluateJavascript(
                        "(function(){try{"
                        + "var res={insetTop:getComputedStyle(document.documentElement).getPropertyValue('--inset-top'),"
                        + "insetBottom:getComputedStyle(document.documentElement).getPropertyValue('--inset-bottom'),"
                        + "listSub:(document.getElementById('listSub')||{}).textContent,"
                        + "swipeWraps:document.querySelectorAll('.swipe-wrap').length,"
                        + "swActions:document.querySelectorAll('.sw-act').length,"
                        + "copyBtns:[!!document.getElementById('copyText'),!!document.getElementById('copySummary'),!!document.getElementById('copyMd')].join('/')};"
                        + "var r=(typeof RECORDINGS!=='undefined'&&RECORDINGS.length)?RECORDINGS[0]:null;"
                        + "if(r){enterSelect(r.id);res.selecting=document.body.classList.contains('selecting');"
                        + "res.selCount=(document.getElementById('selCount')||{}).textContent;"
                        + "res.hasShare=!!document.getElementById('selShare');"
                        + "startRename([r.id,r.id]);"
                        + "res.renameTitle=(document.getElementById('renameTitle')||{}).textContent;"
                        + "res.renameExample='会议 '+stampOf(r);"
                        + "renameIds=[];closeSheets();exitSelect();"
                        + "res.exited=!document.body.classList.contains('selecting');"
                        + "res.newestTitle=r.title;res.newestStamp=stampOf(r);"
                        + "var s=byId('sample_demo');"
                        + "if(s){res.sampleTitle=s.title;res.sampleLang=s.lang;res.sampleSpk=s.speakers;"
                        + "res.sampleSegs=(s.transcript||[]).length;"
                        + "openDetail(s);openSegEditor(0);"
                        + "res.segSheet=document.getElementById('segSheet').classList.contains('show');"
                        + "res.segWhoVal=document.getElementById('segWho').value;"
                        + "res.segTextLen=document.getElementById('segText').value.length;"
                        + "res.segChips=document.querySelectorAll('#segSpeakers .chip').length;"
                        + "closeSheets();"
                        + "openSpeakerEditor(s,null);"
                        + "var chips=document.querySelectorAll('#speakerChips .chip');"
                        + "res.spkChips=chips.length;"
                        + "res.spkHint=(document.getElementById('speakerHint')||{}).textContent;"
                        + "if(chips.length){chips[chips.length-1].click();}"
                        + "res.assignBtnText=(document.getElementById('assignBtn')||{}).textContent;"
                        + "openAssignSheet();"
                        + "res.assignRows=document.querySelectorAll('#assignList .assign-row').length;"
                        + "res.assignSel=assignSel.size;"
                        + "res.assignSaveText=(document.getElementById('assignSave')||{}).textContent;"
                        + "closeSheets();closeDetail();"
                        + "res.hasSettings=!!document.getElementById('settingsBtn');"
                        + "var prevTheme=document.documentElement.getAttribute('data-theme');"
                        + "document.getElementById('settingsBtn').click();"
                        + "res.drawerOpen=document.getElementById('drawer').classList.contains('show');"
                        + "var drawerW=document.getElementById('drawer').getBoundingClientRect().width;"
                        + "res.drawerW=Math.round(drawerW);"
                        + "var topt=document.querySelector('#themeGrid .theme-opt');if(topt)topt.click();"
                        + "res.drawerAutoClosed=!document.getElementById('drawer').classList.contains('show');"
                        + "applyTheme(prevTheme);"
                        + "closeSheets();"
                        + "res.kbVar=getComputedStyle(document.documentElement).getPropertyValue('--kb');"
                        + "onRecStarted({elapsed:0});"
                        + "res.pauseVisible=getComputedStyle(document.getElementById('hudPause')).display;"
                        + "res.pauseLabel=document.getElementById('hudPauseLabel').textContent.trim();"
                        + "onRecPaused({elapsed:0});"
                        + "res.pauseLabelPaused=document.getElementById('hudPauseLabel').textContent.trim();"
                        + "onRecIdle();"
                        + "res.pauseHidden=getComputedStyle(document.getElementById('hudPause')).display;"
                        + "window.__native('openImport',{});"
                        + "res.importOpen=document.getElementById('importSheet').classList.contains('show');"
                        + "res.importOpts=document.querySelectorAll('#importSheet .fmt-list span').length;"
                        + "closeSheets();"
                        + "res.gallery0=NATIVE.getVoiceGallery();"
                        + "if(s&&(s.transcript||[]).length){"
                        + "openDetail(s);"
                        + "res.tagTexts=[].map.call(document.querySelectorAll('#detailTags .tag'),function(e){return e.textContent.trim();}).join('/');"
                        + "res.tagCount=document.querySelectorAll('#detailTags .tag').length;"
                        + "res.hasImportBtn=!!document.getElementById('importBtn');"
                        + "res.hasOpenRow=!!document.getElementById('openLocalBtn');"
                        + "res.fmtItems=document.querySelectorAll('#fmtList span').length;"
                        + "res.textNodes=document.querySelectorAll('#transcript .txt').length;"
                        + "res.trNodes=document.querySelectorAll('#transcript .tr-text').length;"
                        + "res.btnTr=document.querySelector('#detailTags .tag.translate').textContent.trim();"
                        + "res.playHints=document.querySelectorAll('#recList .play-hint').length;"
                        + "res.transHint=(document.getElementById('transHint')||{}).textContent;"
                        + "res.sumHasInfo=/转写信息/.test(document.getElementById('sumMd').innerHTML);"
                        + "res.mdHasFooter=/由 SenseVoice-Small/.test(buildMd(s));"
                        + "res.audioJson=(NATIVE.listAudio(3)||'').length;"
                        + "res.transcribeBtns=document.querySelectorAll('#recList .act-transcribe').length;"
                        + "var oldWho=s.transcript[0].who;"
                        + "editingSpeakersRec=s;selectedSpeaker=oldWho;applySpeakerRename('我');"
                        + "res.renamedWho=s.transcript[0].who;res.gallery1=NATIVE.getVoiceGallery();"
                        + "editingSpeakersRec=s;selectedSpeaker='我';applySpeakerRename(oldWho);"
                        + "res.gallery2=NATIVE.getVoiceGallery();"
                        + "res.restoredWho=s.transcript[0].who;closeDetail();}"
                        + "var sr=document.getElementById('searchInput').getBoundingClientRect();"
                        + "res.searchRect=[Math.round(sr.left),Math.round(sr.top),Math.round(sr.width),Math.round(sr.height)].join(',');}"
                        + "res.mdLen=buildMd(r).length;res.sumLen=summaryPlainText(r).length;"
                        + "res.cards=document.querySelectorAll('#recList .swipe-wrap').length;"
                        + "res.cardSubs=document.querySelectorAll('#recList .card-sub').length;"
                        + "res.miniWaves=document.querySelectorAll('#recList .mini-wave').length;"
                        + "var sub=document.querySelector('#recList .card-sub');"
                        + "var wv=document.querySelector('#recList .mini-wave');"
                        + "var cd=document.querySelector('#recList .card');"
                        + "var wr=document.querySelector('#recList .swipe-wrap');"
                        + "if(sub){var ss=getComputedStyle(sub);res.subDis=ss.display;res.subH=ss.height;res.subColor=ss.color;res.subFont=ss.fontSize;}"
                        + "if(wv){var ws=getComputedStyle(wv);res.waveH=ws.height;res.waveDis=ws.display;}"
                        + "if(cd){res.cardH=getComputedStyle(cd).height;}"
                        + "if(wr){res.wrapH=getComputedStyle(wr).height;}"
                        + "res.theme=document.documentElement.getAttribute('data-theme');res.bodyCls=document.body.className;"
                        + "res.prefs0=NATIVE.getUiPrefs();"
                        + "NATIVE.setTopics('[\"软件bug\",\"工作思考\"]');"
                        + "res.prefs1=NATIVE.getUiPrefs();"
                        + "res.topicsNow=allTopics().join('/');"
                        + "state.keyword='测试';res.searchHit=(function(){var n=0;RECORDINGS.forEach(function(r){if(matches(r))n++;});return n;})();"
                        + "state.keyword='';}"
                        + "return JSON.stringify(res);}catch(e){return 'ERR '+e;}})()",
                        v -> Log.w(TAG, "uiProbe " + v));
            });
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    File out = new File(dir, "selftest.txt");
                    try (OutputStream o = new FileOutputStream(out)) {
                        o.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    /* ------------------------- 与网页通信 ------------------------- */

    void emit(String event, String json) {
        final String js = "window.__native && window.__native('" + event + "'," + json + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    void emitState() {
        emit("state", stateJson());
    }

    void toastMsg(final String msg) {
        runOnUiThread(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
    }

    /* ------------------- 全面屏 / 系统栏避让 ------------------- */

    private WindowInsets lastInsets;

    private void setupEdgeToEdge() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
        applyBarAppearance(true);
        if (web != null) {
            web.setOnApplyWindowInsetsListener((v, insets) -> {
                lastInsets = insets;
                pushInsets();
                return insets;
            });
        }
    }

    /** 把系统栏高度以 CSS 变量注入页面，避免内容被状态栏/导航栏遮住。 */
    private void pushInsets() {
        if (web == null || lastInsets == null) return;
        float d = getResources().getDisplayMetrics().density;
        if (d <= 0) d = 1f;
        int top = 0, bottom = 0, keyboard = 0;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets b = lastInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = b.top;
            bottom = b.bottom;
            keyboard = lastInsets.getInsets(WindowInsets.Type.ime()).bottom;
        } else {
            top = lastInsets.getSystemWindowInsetTop();
            bottom = lastInsets.getSystemWindowInsetBottom();
        }
        Log.w(TAG, "insets top=" + top + " bottom=" + bottom + " kb=" + keyboard);
        final String js = "document.documentElement.style.setProperty('--inset-top','"
                + Math.round(top / d) + "px');"
                + "document.documentElement.style.setProperty('--inset-bottom','"
                + Math.round(bottom / d) + "px');"
                + "document.documentElement.style.setProperty('--kb','"
                + Math.round(keyboard / d) + "px');";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    /** darkIcons=true 表示浅色底、状态栏图标用深色。 */
    private void applyBarAppearance(boolean darkIcons) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                c.setSystemBarsAppearance(darkIcons ? mask : 0, mask);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
            if (darkIcons) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private String modelObj() {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", asr.isModelReady());
            o.put("busy", asr.isExtracting());
            o.put("progress", asr.isModelReady() ? 100 : 0);
            o.put("name", asr.spec().name);
            o.put("message", asr.isModelReady() ? asr.spec().name + " 已就绪" : "正在准备离线模型");
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    private String modelObj(int pct, String msg) {
        JSONObject o = new JSONObject();
        try {
            o.put("ready", asr.isModelReady());
            o.put("busy", true);
            o.put("progress", Math.max(0, pct));
            o.put("message", msg == null ? "" : msg);
        } catch (Exception ignored) {
        }
        return o.toString();
    }

    String stateJson() {
        JSONObject root = new JSONObject();
        JSONArray arr = new JSONArray();
        try {
            for (JSONObject r : store.all()) {
                JSONObject o = new JSONObject();
                Iterator<String> it = r.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    o.put(k, r.get(k));
                }
                String file = r.optString("file");
                if (!file.isEmpty() && server != null) {
                    o.put("url", "http://127.0.0.1:" + server.port() + "/audio/" + Uri.encode(file));
                }
                arr.put(o);
            }
            root.put("records", arr);
            root.put("model", new JSONObject(modelObj()));
            root.put("port", server == null ? 0 : server.port());
            root.put("trashCount", store.trash().size());
        } catch (Exception e) {
            Log.e(TAG, "stateJson", e);
        }
        return root.toString();
    }

    /* -------------------------- 权限 -------------------------- */

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERM);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERM) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            emit("permission", "{\"mic\":" + ok + "}");
            if (!ok) toastMsg("没有麦克风权限，录音功能不可用");
        } else if (requestCode == 13) {
            /* 媒体读取权限回调：让页面刷新本机音频列表 */
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!ok) toastMsg("未授权读取音频，「打开」里只列出已授权的文件");
            emit("audioPermission", "{}");
        }
    }

    /* -------------------------- 模型 -------------------------- */

    private void prepareModelAsync() {
        emit("model", modelObj(0, "正在准备离线模型"));
        pool.execute(() -> {
            asr.extractIfNeeded((pct, msg) -> emit("model", modelObj(pct, msg)));
            Log.w(TAG, "model ready=" + asr.isModelReady());
            emit("model", modelObj());
            emitState();
        });
    }

    /* -------------------------- 录音（前台服务） -------------------------- */

    void startRecord() {
        if (Build.VERSION.SDK_INT >= 23
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission();
            toastMsg("请先允许麦克风权限");
            return;
        }
        if (RecordingService.isRunning()) {
            emitRecordingState();
            return;
        }
        RecordingService.send(this, RecordingService.ACTION_START);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void stopRecord() {
        if (!RecordingService.isRunning()) return;
        RecordingService.send(this, RecordingService.ACTION_STOP);
    }

    void pauseRecord() {
        if (!RecordingService.isRunning()) return;
        RecordingService.send(this, RecordingService.ACTION_PAUSE);
    }

    void resumeRecord() {
        if (!RecordingService.isRunning()) return;
        RecordingService.send(this, RecordingService.ACTION_RESUME);
    }

    /** 把服务当前的录音状态推给界面（回到前台时同步用）。 */
    private void emitRecordingState() {
        if (RecordingService.isRunning()) {
            emit(RecordingService.isPaused() ? "recordPaused" : "recordStarted",
                    "{\"elapsed\":" + RecordingService.elapsedMs() + "}");
        } else {
            emit("recordIdle", "{}");
        }
    }

    /* -------------------- 服务状态广播 -------------------- */

    private boolean receiverOn = false;

    private final android.content.BroadcastReceiver recReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String state = intent.getStringExtra(RecordingService.EXTRA_STATE);
            long dur = intent.getLongExtra("duration", 0L);
            if (state == null) return;
            switch (state) {
                case "recording":
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    emit("recordStarted", "{\"elapsed\":" + dur + "}");
                    break;
                case "paused":
                    emit("recordPaused", "{\"elapsed\":" + dur + "}");
                    break;
                case "resumed":
                    emit("recordResumed", "{\"elapsed\":" + dur + "}");
                    break;
                case "nopause":
                    toastMsg("这台手机不支持录音暂停");
                    break;
                case "error":
                    toastMsg("录音启动失败");
                    break;
                case "stopped":
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    emit("recordStopped", "{\"duration\":" + Math.max(1, dur / 1000) + "}");
                    consumePendingRecord();
                    break;
                default:
                    break;
            }
        }
    };

    private void registerRecReceiver() {
        if (receiverOn) return;
        android.content.IntentFilter f = new android.content.IntentFilter(RecordingService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(recReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recReceiver, f);
        }
        receiverOn = true;
    }

    private void unregisterRecReceiver() {
        if (!receiverOn) return;
        try {
            unregisterReceiver(recReceiver);
        } catch (Exception ignored) {
        }
        receiverOn = false;
    }

    /** 录音服务停止后留下的文件 → 生成记录并转写。 */
    private void consumePendingRecord() {
        final android.content.SharedPreferences sp = getSharedPreferences(RecordingService.PREF, MODE_PRIVATE);
        final String fileName = sp.getString(RecordingService.PENDING_FILE, null);
        final long durMs = sp.getLong(RecordingService.PENDING_DUR, 0L);
        if (fileName == null) return;
        sp.edit().remove(RecordingService.PENDING_FILE)
                .remove(RecordingService.PENDING_DUR)
                .remove(RecordingService.PENDING_TS).apply();

        final File f = new File(audioDir, fileName);
        if (!f.exists() || f.length() <= 0) return;

        String id = "r" + System.currentTimeMillis();
        String title = "录音" + new SimpleDateFormat("yyyyMMddHHmm", Locale.CHINA).format(new Date());
        JSONObject rec = newRecord(id, title, f);
        try {
            rec.put("duration", Math.max(1, durMs / 1000));
        } catch (Exception ignored) {
        }
        store.put(rec);
        store.save();
        emitState();
        /* 不再自动转写：由用户在列表点「开始转写」 */
        Log.w(TAG, "pending record saved id=" + id + " dur=" + durMs);
    }

    /* -------------------------- 翻译（HY-MT1.5-1.8B） -------------------------- */

    void translateRecord(final String id) {
        if (id == null || translating.contains(id)) return;
        final JSONObject rec = store.get(id);
        if (rec == null) return;
        JSONArray tr = rec.optJSONArray("transcript");
        if (tr == null || tr.length() == 0) {
            toastMsg("还没有转写内容");
            return;
        }
        translating.add(id);
        pool.execute(() -> {
            try {
                if (!translator.isReady()) {
                    emitTranslateProgress(id, 0, "准备下载翻译模型（" + translator.spec().size + "）");
                    boolean ok = translator.download((pct, msg) -> emitTranslateProgress(id, pct, msg));
                    if (!ok) throw new java.io.IOException("翻译模型下载失败");
                }
                emitTranslateProgress(id, 0, "正在加载翻译模型…");
                translator.load(4);

                int n = tr.length();
                for (int i = 0; i < n; i++) {
                    JSONObject seg = tr.optJSONObject(i);
                    if (seg == null) continue;
                    String text = seg.optString("text", "");
                    if (text.isEmpty()) continue;
                    /* 译文与原文一致才复用缓存；原文改过就重新翻译 */
                    String cached = seg.optString("tr", "");
                    String cachedSrc = seg.optString("trSrc", "");
                    if (cached != null && !cached.isEmpty() && text.equals(cachedSrc)) {
                        emitTranslateProgress(id, (i + 1) * 100 / Math.max(1, n),
                                "正在翻译 " + (i + 1) + "/" + n);
                        continue;
                    }
                    String zh = translator.translate(text);
                    if (zh != null && !zh.isEmpty()) {
                        seg.put("tr", zh);
                        seg.put("trSrc", text);
                    }
                    emitTranslateProgress(id, (i + 1) * 100 / Math.max(1, n),
                            "正在翻译 " + (i + 1) + "/" + n);
                }
                rec.put("transcript", tr);
                rec.put("translated", true);
                rec.put("trEngine", translator.spec().name);
                store.put(rec);
                store.save();
                Log.w(TAG, "translate ok id=" + id + " segs=" + n);
                /* 先把新数据推给界面，再通知翻译完成，译文才能立刻显示 */
                emitState();
                emit("translated", "{\"id\":" + JSONObject.quote(id) + "}");
            } catch (Throwable t) {
                Log.e(TAG, "translate failed", t);
                emit("translateFailed", "{\"id\":" + JSONObject.quote(id)
                        + ",\"message\":" + JSONObject.quote(String.valueOf(t.getMessage())) + "}");
            } finally {
                translating.remove(id);
            }
        });
    }

    /* -------------------------- 模型管理 -------------------------- */

    /** 下载 / 切换模型：后台线程执行，进度通过 modelDl 事件回传页面 */
    void downloadModel(final String kind) {
        if (kind == null) return;
        if (models.downloading(kind)) {
            toastMsg("正在下载中，请稍候");
            return;
        }
        pool.execute(() -> {
            ModelManager.Spec s = models.active(kind);
            boolean ok = models.download(s, (pct, msg) -> emit("modelDl",
                    "{\"kind\":" + JSONObject.quote(kind) + ",\"progress\":" + pct
                            + ",\"message\":" + JSONObject.quote(msg == null ? "" : msg) + "}"));
            emit("modelDl", "{\"kind\":" + JSONObject.quote(kind) + ",\"progress\":" + (ok ? 100 : -1)
                    + ",\"done\":true}");
            if (ok) {
                if ("asr".equals(kind)) asr.release();
                if ("tr".equals(kind)) translator.release();
            }
            emit("modelMarket", models.stateJson());
            emitState();
        });
    }

    private void openExternal(String url) {
        if (url == null || url.isEmpty()) return;
        try {
            android.content.Intent i = new android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Log.w(TAG, "open url failed", e);
            toastMsg("无法打开链接");
        }
    }

    private void emitTranslateProgress(String id, int pct, String msg) {
        emit("translateProgress", "{\"id\":" + JSONObject.quote(id)
                + ",\"progress\":" + pct
                + ",\"message\":" + JSONObject.quote(msg == null ? "" : msg) + "}");
    }

    /* -------------------------- 导入 -------------------------- */

    /** 支持导入的扩展名（是否真能解码由 AudioDecode.canDecode 再校验） */
    static final String[] SUPPORTED_EXT = {
            "mp3", "m4a", "m4b", "aac", "wav", "flac", "ogg", "oga", "opus",
            "amr", "3gp", "3gpp", "mp4", "mov", "mka", "mkv", "aif", "aiff", "wma", "mid"
    };

    void pickAudio() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        /* 只要音频类型：图片、文档、视频都不会出现在选择器里 */
        i.setType("audio/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            toastMsg("没有可用的文件选择器");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        importUris(uris);
    }

    /** 统一的导入执行：拷贝 → 校验可解码 → 建记录 → 转写。 */
    void importUris(final List<Uri> uris) {
        pool.execute(() -> {
            for (Uri u : uris) {
                try {
                    String ext = extOf(u);
                    String name = "imp_" + System.currentTimeMillis() + "." + ext;
                    File dst = new File(audioDir, name);
                    try (InputStream in = getContentResolver().openInputStream(u);
                         OutputStream out = new FileOutputStream(dst)) {
                        if (in == null) throw new java.io.IOException("无法读取文件");
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    String title = queryName(u, name);
                    /* 本机解不出音轨就拒绝，避免列表里出现打不开的条目 */
                    if (!AudioDecode.canDecode(dst)) {
                        if (!dst.delete()) Log.w(TAG, "delete unsupported file failed");
                        toastMsg("「" + title + "」格式不受支持，已跳过");
                        continue;
                    }
                    String id = "i" + System.currentTimeMillis();
                    JSONObject rec = newRecord(id, title, dst);
                    store.put(rec);
                    store.save();
                    emitState();
                    /* 不再自动转写：在列表点「开始转写」才转 */
                } catch (Exception e) {
                    Log.e(TAG, "import failed", e);
                    emit("asrError", "{\"id\":\"\",\"message\":" + JSONObject.quote("导入失败：" + e.getMessage()) + "}");
                }
            }
        });
    }

    /** 扫描本机音频（只返回受支持的格式），供「打开」面板直接列出。 */
    String listAudioJson(int limit) {
        JSONArray arr = new JSONArray();
        android.database.Cursor c = null;
        try {
            Uri uri = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] proj = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.DISPLAY_NAME,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.SIZE,
                    MediaStore.Audio.Media.DATE_ADDED
            };
            c = getContentResolver().query(uri, proj, null, null,
                    MediaStore.Audio.Media.DATE_ADDED + " DESC");
            if (c != null) {
                while (c.moveToNext() && arr.length() < limit) {
                    String display = c.getString(2);
                    String title = c.getString(1);
                    long dur = c.getLong(3);
                    long size = c.getLong(4);
                    if (display == null) display = title == null ? "" : title;
                    if (!isSupportedName(display)) continue;
                    if (size <= 0 || dur <= 0) continue;
                    JSONObject o = new JSONObject();
                    o.put("id", c.getLong(0));
                    o.put("name", title == null || title.isEmpty() ? display : title);
                    o.put("dur", dur);
                    o.put("size", size);
                    arr.put(o);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "listAudio failed", e);
        } finally {
            if (c != null) c.close();
        }
        return arr.toString();
    }

    private static boolean isSupportedName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.US);
        for (String ext : SUPPORTED_EXT) {
            if (n.endsWith("." + ext)) return true;
        }
        return false;
    }

    private String extOf(Uri uri) {
        String type = getContentResolver().getType(uri);
        if (type == null) return "m4a";
        if (type.contains("mpeg")) return "mp3";
        if (type.contains("wav")) return "wav";
        if (type.contains("aac")) return "aac";
        if (type.contains("flac")) return "flac";
        if (type.contains("ogg")) return "ogg";
        if (type.contains("amr")) return "amr";
        return "m4a";
    }

    private String queryName(Uri uri, String fallback) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = c.getString(idx);
                    if (n != null && !n.isEmpty()) {
                        int dot = n.lastIndexOf('.');
                        return dot > 0 ? n.substring(0, dot) : n;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    /* -------------------------- 记录 -------------------------- */

    private JSONObject newRecord(String id, String title, File file) {
        JSONObject rec = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            rec.put("id", id);
            rec.put("title", title);
            rec.put("file", file.getName());
            rec.put("createdAt", now);
            rec.put("dateLabel", dateLabel(now));
            rec.put("time", new SimpleDateFormat("HH:mm", Locale.CHINA).format(new Date(now)));
            rec.put("duration", 0);
            rec.put("sizeBytes", file.length());
            rec.put("size", humanSize(file.length()));
            rec.put("status", "none");
            rec.put("progress", 0);
            rec.put("topic", "未分类");
            rec.put("fav", false);
            rec.put("lang", "自动识别");
            rec.put("speakers", 1);
            rec.put("summary", "");
            rec.put("todos", new JSONArray());
            rec.put("transcript", new JSONArray());
            rec.put("env", new JSONArray());
        } catch (Exception ignored) {
        }
        return rec;
    }

    private static String humanSize(long bytes) {
        if (bytes >= 1024L * 1024) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private static String dateLabel(long ts) {
        Calendar now = Calendar.getInstance();
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(ts);
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "今天";
        Calendar y = Calendar.getInstance();
        y.add(Calendar.DAY_OF_YEAR, -1);
        if (y.get(Calendar.YEAR) == t.get(Calendar.YEAR)
                && y.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR)) return "昨天";
        if (now.get(Calendar.YEAR) == t.get(Calendar.YEAR)) {
            return (t.get(Calendar.MONTH) + 1) + "月" + t.get(Calendar.DAY_OF_MONTH) + "日";
        }
        return new SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(t.getTime());
    }

    /* -------------------------- 转写 -------------------------- */

    void transcribe(final String id) {
        if (id == null || running.contains(id)) return;
        JSONObject rec = store.get(id);
        if (rec == null) return;
        try {
            rec.put("status", "processing");
            rec.put("progress", 0);
        } catch (Exception ignored) {
        }
        store.put(rec);
        store.save();
        emitState();
        running.add(id);
        lastPct.remove(id);

        pool.execute(() -> {
            try {
                if (!asr.isModelReady()) {
                    asr.extractIfNeeded((pct, msg) -> emit("model", modelObj(pct, msg)));
                }
                emitProgress(id, 3, "解码音频…");
                File f = new File(audioDir, rec.optString("file"));
                if (!f.exists()) throw new java.io.IOException("音频文件不存在");
                AudioDecode.Pcm pcm = AudioDecode.decode(f);
                int[] env = AudioDecode.envelope(pcm.samples, 200);

                emitProgress(id, 12, "加载离线模型…");
                asr.init();
                if (!asr.isModelReady() || pcm.samples.length == 0) throw new java.io.IOException("模型或音频不可用");

                List<AsrEngine.Seg> segs = asr.transcribe(pcm, (pct, msg) -> emitProgress(id, pct, msg));

                JSONArray tr = new JSONArray();
                java.util.LinkedHashSet<String> speakers = new java.util.LinkedHashSet<>();
                for (AsrEngine.Seg s : segs) {
                    JSONObject o = new JSONObject();
                    o.put("t", s.startSec);
                    o.put("who", s.who);
                    o.put("text", s.text);
                    tr.put(o);
                    speakers.add(s.who);
                }
                JSONArray envArr = new JSONArray();
                for (int v : env) envArr.put(v);

                rec.put("transcript", tr);
                rec.put("env", envArr);
                rec.put("duration", pcm.durationSec());
                rec.put("status", "done");
                rec.put("progress", 100);
                rec.put("summary", buildSummary(tr));
                rec.put("todos", new JSONArray());
                rec.put("lang", asr.lastLangLabel);
                rec.put("speakers", Math.max(1, speakers.size()));
                store.put(rec);
                store.save();
                Log.w(TAG, "transcribe ok id=" + id + " segs=" + tr.length()
                        + " dur=" + pcm.durationSec() + "s first="
                        + (tr.length() > 0 ? tr.optJSONObject(0).optString("text") : ""));
                emit("asrDone", "{\"id\":" + JSONObject.quote(id) + "}");
                emitState();
            } catch (Throwable t) {
                Log.e(TAG, "transcribe failed", t);
                try {
                    rec.put("status", "none");
                    rec.put("progress", 0);
                    store.put(rec);
                    store.save();
                } catch (Exception ignored) {
                }
                emit("asrError", "{\"id\":" + JSONObject.quote(id) + ",\"message\":"
                        + JSONObject.quote(String.valueOf(t.getMessage())) + "}");
                emitState();
            } finally {
                running.remove(id);
            }
        });
    }

    private void emitProgress(String id, int pct, String msg) {
        Integer prev = lastPct.get(id);
        if (prev != null && prev == pct) return;
        lastPct.put(id, pct);
        JSONObject rec = store.get(id);
        if (rec != null) {
            try {
                rec.put("progress", pct);
                store.put(rec);
            } catch (Exception ignored) {
            }
        }
        emit("asrProgress", "{\"id\":" + JSONObject.quote(id) + ",\"progress\":" + pct
                + ",\"message\":" + JSONObject.quote(msg == null ? "" : msg) + "}");
    }

    private String buildSummary(JSONArray tr) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tr.length() && sb.length() < 80; i++) {
            JSONObject o = tr.optJSONObject(i);
            if (o != null) sb.append(o.optString("text"));
        }
        String s = sb.toString().trim();
        if (s.length() > 100) s = s.substring(0, 100) + "…";
        if (s.isEmpty()) s = "（未识别到有效语音）";
        return s;
    }

    /* -------------------------- 编辑 / 导出 -------------------------- */

    void patchRecord(String id, String json) {
        try {
            store.patch(id, new JSONObject(json));
        } catch (Exception e) {
            Log.e(TAG, "patch failed", e);
        }
        emitState();
    }

    /** 删除 = 移入回收站（软删除），可恢复；源音频文件一律不动。 */
    void removeRecord(String id) {
        JSONObject rec = store.get(id);
        if (rec != null) {
            try {
                rec.put("deletedAt", System.currentTimeMillis());
            } catch (Exception ignored) {
            }
            store.put(rec);
            store.save();
            if ("sample_demo".equals(id)) {
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putBoolean("sample_removed", true).apply();
            }
        }
        emitState();
    }

    /** 回收站列表。 */
    String trashJson() {
        JSONArray arr = new JSONArray();
        try {
            for (JSONObject r : store.trash()) {
                JSONObject o = new JSONObject();
                o.put("id", r.optString("id"));
                o.put("title", r.optString("title"));
                o.put("size", r.optString("size"));
                o.put("duration", r.optLong("duration", 0));
                o.put("createdAt", r.optLong("createdAt", 0));
                o.put("deletedAt", r.optLong("deletedAt", 0));
                arr.put(o);
            }
        } catch (Exception e) {
            Log.e(TAG, "trashJson failed", e);
        }
        return arr.toString();
    }

    void restoreRecord(String id) {
        JSONObject rec = store.get(id);
        if (rec == null) return;
        rec.remove("deletedAt");
        store.put(rec);
        store.save();
        emitState();
    }

    /** 彻底删除：删记录；只有 App 自己录的文件才删文件，导入的链接不动源文件。 */
    void purgeRecord(String id) {
        JSONObject rec = store.get(id);
        if (rec != null) {
            String srcUri = rec.optString("srcUri", "");
            String file = rec.optString("file", "");
            if (srcUri.isEmpty() && !file.isEmpty()) {
                File f = new File(audioDir, file);
                if (f.exists() && !f.delete()) Log.w(TAG, "delete audio failed");
            }
            store.remove(id);
            store.save();
        }
        emitState();
    }

    void purgeAll() {
        for (JSONObject rec : store.trash()) {
            purgeRecord(rec.optString("id"));
        }
        emitState();
    }

    void exportText(String filename, String mime, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.Downloads.MIME_TYPE, mime);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                        if (out != null) {
                            out.write(content.getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    }
                    toastMsg("已保存到 下载/" + filename);
                    return;
                }
            }
            File f = new File(getCacheDir(), filename);
            try (OutputStream out = new FileOutputStream(f)) {
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            shareText(content);
        } catch (Exception e) {
            Log.e(TAG, "export failed", e);
            toastMsg("导出失败：" + e.getMessage());
        }
    }

    void shareText(String content) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_TEXT, content);
        startActivity(Intent.createChooser(i, "分享转写内容"));
    }

    /* -------------------------- 生命周期 -------------------------- */

    @Override
    public void onBackPressed() {
        web.evaluateJavascript("(window.__appBack && window.__appBack()) ? '1' : '0'", value -> {
            if (value == null || !value.contains("1")) finish();
        });
    }

    private boolean uiReady = false;

    @Override
    protected void onStart() {
        super.onStart();
        registerRecReceiver();
        uiReady = true;
        /* 回到前台时把服务里的录音状态同步给界面 */
        web.postDelayed(this::emitRecordingState, 400);
    }

    @Override
    protected void onStop() {
        super.onStop();
        uiReady = false;
        unregisterRecReceiver();
        /* 退到后台不再播放音频；录音由前台服务继续 */
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript("window.__appPause && window.__appPause()", null);
        });
    }

    @Override
    protected void onDestroy() {
        unregisterRecReceiver();
        asr.release();
        if (translator != null) translator.release();
        if (server != null) server.stop();
        pool.shutdownNow();
        super.onDestroy();
    }

    /* -------------------------- JS 桥 -------------------------- */

    private class Bridge {

        @JavascriptInterface
        public String getState() {
            return stateJson();
        }

        @JavascriptInterface
        public String getModel() {
            return modelObj();
        }

        /** 全局改名时同步声纹档案，保证下次录音仍认得出这是同一个人。 */
        @JavascriptInterface
        public void syncVoiceName(String from, String to) {
            try {
                asr.gallery().rename(from, to);
            } catch (Exception e) {
                Log.e(TAG, "syncVoiceName failed", e);
            }
        }

        /** 清空声纹档案，之后的声音重新从「说话人 1」开始学习。 */
        @JavascriptInterface
        public void clearVoiceGallery() {
            try {
                asr.gallery().clear();
                toastMsg("声纹档案已清空");
            } catch (Exception e) {
                Log.e(TAG, "clearVoiceGallery failed", e);
            }
        }

        /** 当前声纹档案概况，供调试查看。 */
        @JavascriptInterface
        public String getVoiceGallery() {
            return asr.gallery().info();
        }

        /** 页面切换深浅底色时调用，保证状态栏图标可见。 */
        @JavascriptInterface
        public void setSystemTheme(String mode) {
            final boolean darkIcons = "light".equals(mode);
            runOnUiThread(() -> applyBarAppearance(darkIcons));
        }

        @JavascriptInterface
        public void startRecord() {
            MainActivity.this.startRecord();
        }

        @JavascriptInterface
        public void stopRecord() {
            MainActivity.this.stopRecord();
        }

        @JavascriptInterface
        public void pauseRecord() {
            MainActivity.this.pauseRecord();
        }

        @JavascriptInterface
        public void resumeRecord() {
            MainActivity.this.resumeRecord();
        }

        /** 页面启动时来取一次：冷启动从快捷方式进来要执行的动作。 */
        @JavascriptInterface
        public String consumePendingAction() {
            String a = pendingAction;
            pendingAction = null;
            Log.w(TAG, "consumePendingAction -> " + a);
            return a == null ? "" : a;
        }

        @JavascriptInterface
        public String getRecordingState() {
            boolean running = RecordingService.isRunning();
            return "{\"running\":" + running
                    + ",\"paused\":" + RecordingService.isPaused()
                    + ",\"elapsed\":" + RecordingService.elapsedMs() + "}";
        }

        @JavascriptInterface
        public void pickAudio() {
            runOnUiThread(MainActivity.this::pickAudio);
        }

        /** 「打开」面板里直接列出本机音频（只含受支持格式）。 */
        @JavascriptInterface
        public String listAudio(int limit) {
            return listAudioJson(limit <= 0 ? 60 : limit);
        }

        @JavascriptInterface
        public void requestAudioAccess() {
            runOnUiThread(MainActivity.this::ensureAudioReadPermission);
        }

        @JavascriptInterface
        public void importAudioId(long id) {
            try {
                Uri u = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);
                List<Uri> one = new ArrayList<>();
                one.add(u);
                importUris(one);
            } catch (Exception e) {
                Log.e(TAG, "importAudioId failed", e);
            }
        }

        @JavascriptInterface
        public void transcribe(String id) {
            MainActivity.this.transcribe(id);
        }

        @JavascriptInterface
        public void translateRecord(String id) {
            MainActivity.this.translateRecord(id);
        }

        /** 界面偏好（话题 / 配色）：存到原生，避免 WebView 端口变化导致 localStorage 丢失。 */
        @JavascriptInterface
        public String getUiPrefs() {
            android.content.SharedPreferences sp = getSharedPreferences("app", MODE_PRIVATE);
            JSONObject o = new JSONObject();
            try {
                String topics = sp.getString("topics", "[]");
                JSONArray arr;
                try {
                    arr = new JSONArray(topics);
                } catch (Exception e) {
                    arr = new JSONArray();
                }
                o.put("topics", arr);
                o.put("theme", sp.getString("theme", "paper"));
                o.put("lang", sp.getString("lang", "zh"));
            } catch (Exception ignored) {
            }
            return o.toString();
        }

        @JavascriptInterface
        public void setTopics(String json) {
            try {
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putString("topics", json == null ? "[]" : json).apply();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void setLang(String lang) {
            try {
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putString("lang", "en".equals(lang) ? "en" : "zh").apply();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void setTheme(String id) {
            try {
                getSharedPreferences("app", MODE_PRIVATE).edit()
                        .putString("theme", id == null ? "paper" : id).apply();
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public String listTrash() {
            return trashJson();
        }

        @JavascriptInterface
        public void restoreRecord(String id) {
            MainActivity.this.restoreRecord(id);
        }

        @JavascriptInterface
        public void purgeRecord(String id) {
            MainActivity.this.purgeRecord(id);
        }

        @JavascriptInterface
        public void purgeAllTrash() {
            MainActivity.this.purgeAll();
        }

        @JavascriptInterface
        public String getTranslateState() {
            return translator == null ? "{\"ready\":false}" : translator.statusJson();
        }

        /* ---------------------- 模型管理 ---------------------- */

        @JavascriptInterface
        public String getModelMarket() {
            return models.stateJson();
        }

        @JavascriptInterface
        public void selectModel(String kind, String id) {
            models.select(kind, id);
            if ("asr".equals(kind)) asr.release();
            if ("tr".equals(kind)) translator.release();
            emit("modelMarket", models.stateJson());
            emitState();
        }

        @JavascriptInterface
        public void saveCustomModel(String kind, String json) {
            models.saveCustom(kind, json);
            if ("asr".equals(kind)) asr.release();
            if ("tr".equals(kind)) translator.release();
            emit("modelMarket", models.stateJson());
        }

        @JavascriptInterface
        public void downloadModel(String kind) {
            MainActivity.this.downloadModel(kind);
        }

        @JavascriptInterface
        public void deleteModel(String kind) {
            models.delete(models.active(kind));
            if ("asr".equals(kind)) asr.release();
            if ("tr".equals(kind)) translator.release();
            emit("modelMarket", models.stateJson());
            emitState();
        }

        @JavascriptInterface
        public void openUrl(String url) {
            runOnUiThread(() -> openExternal(url));
        }

        @JavascriptInterface
        public void patch(String id, String json) {
            MainActivity.this.patchRecord(id, json);
        }

        @JavascriptInterface
        public void remove(String id) {
            MainActivity.this.removeRecord(id);
        }

        @JavascriptInterface
        public void exportText(String filename, String mime, String content) {
            MainActivity.this.exportText(filename, mime, content);
        }

        @JavascriptInterface
        public void share(String content) {
            runOnUiThread(() -> shareText(content));
        }

        @JavascriptInterface
        public void toast(String msg) {
            toastMsg(msg);
        }
    }
}
