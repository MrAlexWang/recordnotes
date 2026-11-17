package com.phonesar.notes;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * SenseVoice-Small（sherpa-onnx，int8 量化）离线转写。
 * 模型随 APK 打包在 assets/model，首次启动拷贝到私有目录后完全离线运行。
 */
public class AsrEngine {

    private static final String TAG = "AsrEngine";
    private static final String MODEL_ASSET = "model/model.int8.onnx";
    private static final String TOKENS_ASSET = "model/tokens.txt";
    private static final String SPEAKER_ASSET = "model/campplus.onnx";

    public interface Progress {
        void onProgress(int percent, String message);
    }

    public static class Seg {
        public final int startSec;
        public final String text;
        public final int startSample;
        public final int endSample;
        public String who = "说话人 1";
        public String lang = "";

        Seg(int startSec, String text, int startSample, int endSample) {
            this.startSec = startSec;
            this.text = text;
            this.startSample = startSample;
            this.endSample = endSample;
        }
    }

    private final Context ctx;
    /** 模型市场：具体用哪个转写模型由用户在设置里决定 */
    private final ModelManager models;
    private final File modelDir;
    private final File speakerFile;
    /** 当前已加载的模型 id，切换模型时需要重建识别器 */
    private String loadedSpecId = "";
    private OfflineRecognizer recognizer;
    private com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor speakerExtractor;
    private volatile boolean extracting = false;
    /** 跨录音的声纹档案 */
    private final VoiceGallery gallery;
    /** 最近一次转写检测到的语种展示文本，例如「普通话 · 英文夹杂」。 */
    public String lastLangLabel = "自动识别";

    public AsrEngine(Context ctx, ModelManager models) {
        this.ctx = ctx.getApplicationContext();
        this.models = models;
        this.modelDir = models.asrDir();
        this.speakerFile = new File(modelDir, "campplus.onnx");
        this.gallery = new VoiceGallery(this.ctx);
    }

    /** 当前生效的转写模型 */
    public ModelManager.Spec spec() {
        return models.active("asr");
    }

    public VoiceGallery gallery() {
        return gallery;
    }

    public boolean isSpeakerReady() {
        return speakerFile.exists() && speakerFile.length() > 10L * 1024 * 1024;
    }

    /** 当前选中的转写模型是否已下载完成 */
    public boolean isModelReady() {
        return models.ready(spec());
    }

    public boolean isExtracting() {
        return extracting;
    }

    /** 准备当前选中的转写模型（没有就联网下载）+ 可选的声纹模型。 */
    public synchronized void extractIfNeeded(Progress p) {
        ModelManager.Spec s = spec();
        if (models.ready(s) && isSpeakerReady()) {
            if (p != null) p.onProgress(100, "模型已就绪");
            return;
        }
        extracting = true;
        try {
            if (!modelDir.exists() && !modelDir.mkdirs()) Log.w(TAG, "mkdir model failed");
            if (!models.ready(s)) {
                Log.w(TAG, "downloading asr model " + s.id);
                if (!models.download(s, (pct, msg) -> {
                    if (p != null) p.onProgress(pct, msg);
                })) {
                    if (p != null) p.onProgress(-1, "转写模型下载失败，请检查网络后重试");
                    return;
                }
            }
            if (!isSpeakerReady()) downloadSpeaker(p);
            Log.w(TAG, "extract done main=" + isModelReady() + " speaker=" + isSpeakerReady());
            if (p != null) p.onProgress(100, "模型已就绪");
        } catch (Exception e) {
            Log.e(TAG, "extract failed", e);
            if (p != null) p.onProgress(-1, "模型准备失败：" + e.getMessage());
        } finally {
            extracting = false;
        }
    }

    /** 声纹模型（可选）：失败不影响转写。 */
    private void downloadSpeaker(Progress p) {
        final String path = "csukuangfj/speaker-embedding-models/resolve/main/"
                + "3dspeaker_speech_campplus_sv_zh_en_16k-common_advanced.onnx";
        final long expect = 27L * 1024 * 1024;
        String[] urls = {"https://hf-mirror.com/" + path, "https://huggingface.co/" + path};
        File part = new File(modelDir, "campplus.onnx.part");
        for (String url : urls) {
            try {
                download(url, part, expect, p, "正在下载声纹模型");
                if (part.length() < expect / 2) throw new java.io.IOException("文件不完整");
                if (speakerFile.exists() && !speakerFile.delete()) Log.w(TAG, "delete speaker failed");
                if (!part.renameTo(speakerFile)) Log.w(TAG, "rename speaker failed");
                return;
            } catch (Exception e) {
                Log.w(TAG, "speaker download failed " + url + " -> " + e.getMessage());
            }
        }
    }

    private void download(String url, File dst, long expectBytes, Progress p, String label) throws Exception {
        java.net.HttpURLConnection conn =
                (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "RecordNotes/1.0");
        int code = conn.getResponseCode();
        if (code != 200) {
            try { conn.disconnect(); } catch (Exception ignored) {}
            throw new java.io.IOException("HTTP " + code);
        }
        long total = conn.getContentLength();
        if (total <= 0) total = expectBytes;
        long lastEmit = 0;
        try (java.io.InputStream in = conn.getInputStream();
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[256 * 1024];
            int r;
            long written = 0;
            while ((r = in.read(buf)) > 0) {
                out.write(buf, 0, r);
                written += r;
                long now = System.currentTimeMillis();
                if (p != null && now - lastEmit > 600) {
                    lastEmit = now;
                    int pct = (int) Math.min(99, written * 100 / Math.max(1, total));
                    p.onProgress(pct, label + " " + pct + "%（"
                            + (written / 1048576) + " / " + (total / 1048576) + " MB）");
                }
            }
        } finally {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** 初始化识别器（首次约 1-3 秒）。切换模型后会重建。 */
    public synchronized void init() throws Exception {
        ModelManager.Spec s = spec();
        if (recognizer != null && s.id.equals(loadedSpecId)) return;
        if (!models.ready(s)) throw new IllegalStateException("模型尚未就绪");
        release();
        long t0 = System.currentTimeMillis();

        File model = models.fileOf(s);
        File tokens = models.extraOf(s);

        OfflineRecognizerConfig config = new OfflineRecognizerConfig();
        config.setFeatConfig(new FeatureConfig(16000, 80, 0f));

        OfflineModelConfig mc = new OfflineModelConfig();
        if ("paraformer".equals(s.family)) {
            OfflineParaformerModelConfig pf = new OfflineParaformerModelConfig();
            pf.setModel(model.getAbsolutePath());
            mc.setParaformer(pf);
        } else {
            OfflineSenseVoiceModelConfig sv = new OfflineSenseVoiceModelConfig();
            sv.setModel(model.getAbsolutePath());
            sv.setLanguage("auto");
            sv.setUseInverseTextNormalization(true);
            mc.setSenseVoice(sv);
        }
        if (tokens != null) mc.setTokens(tokens.getAbsolutePath());
        mc.setNumThreads(4);
        mc.setDebug(false);
        mc.setProvider("cpu");
        config.setModelConfig(mc);

        recognizer = new OfflineRecognizer(null, config);
        loadedSpecId = s.id;
        Log.w(TAG, "recognizer(" + s.id + ") ready in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 分片转写，返回带起始时间的片段。 */
    public List<Seg> transcribe(AudioDecode.Pcm pcm, Progress p) {
        List<Seg> segs = new ArrayList<>();
        if (recognizer == null) return segs;

        List<int[]> chunks = split(pcm.samples);
        int n = Math.max(1, chunks.size());
        java.util.LinkedHashSet<String> langs = new java.util.LinkedHashSet<>();

        for (int i = 0; i < n; i++) {
            int[] c = chunks.get(i);
            float[] samples = pcm.slice(c[0], c[1]);
            if (samples.length < 1600) continue;
            OfflineStream stream = recognizer.createStream();
            try {
                stream.acceptWaveform(samples, AudioDecode.TARGET_RATE);
                recognizer.decode(stream);
                com.k2fsa.sherpa.onnx.OfflineRecognizerResult r = recognizer.getResult(stream);
                String raw = r.getText();
                String text = clean(raw);
                String lg = r.getLang() == null ? "" : r.getLang().trim();
                if (lg.isEmpty() && raw != null) {
                    if (raw.contains("<|en|>")) lg = "en";
                    else if (raw.contains("<|yue|>")) lg = "yue";
                    else if (raw.contains("<|ja|>")) lg = "ja";
                    else if (raw.contains("<|ko|>")) lg = "ko";
                    else if (raw.contains("<|zh|>")) lg = "zh";
                }
                if (!lg.isEmpty()) langs.add(lg);
                if (!text.isEmpty()) {
                    Seg seg = new Seg(c[0] / AudioDecode.TARGET_RATE, text, c[0], c[1]);
                    seg.lang = lg;
                    segs.add(seg);
                }
            } catch (Throwable t) {
                Log.e(TAG, "chunk " + i + " failed", t);
            } finally {
                stream.release();
            }
            if (p != null) {
                int pct = 10 + (int) ((i + 1) * 62.0 / n);
                p.onProgress(Math.min(72, pct), "正在转写 " + (i + 1) + "/" + n);
            }
        }

        lastLangLabel = langLabel(langs);
        int speakers = assignSpeakers(pcm, segs);
        if (p != null) p.onProgress(90, "正在区分说话人（检测到 " + speakers + " 位）");
        Log.w(TAG, "transcribe done chunks=" + chunks.size() + " segs=" + segs.size()
                + " lang=" + lastLangLabel + " speakers=" + speakers);
        return segs;
    }

    private String langLabel(java.util.Set<String> langs) {
        if (langs.isEmpty()) return "自动识别";
        java.util.List<String> names = new ArrayList<>();
        for (String l : langs) {
            // SenseVoice 可能返回 <|zh|> 这种带标签的语种，这里统一取字母部分
            String key = l.toLowerCase().replaceAll("[^a-z]", "");
            String nm;
            if (key.startsWith("zh")) nm = "普通话";
            else if (key.startsWith("en")) nm = "英文";
            else if (key.startsWith("yue")) nm = "粤语";
            else if (key.startsWith("ja")) nm = "日语";
            else if (key.startsWith("ko")) nm = "韩语";
            else nm = l;
            if (!names.contains(nm)) names.add(nm);
        }
        if (names.isEmpty()) return "自动识别";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(names.get(i));
        }
        if (names.size() > 1) sb.append("夹杂");
        return sb.toString();
    }

    /* ---------------- 说话人分离（CAM++ 声纹聚类） ---------------- */

    private synchronized void initSpeaker() {
        if (speakerExtractor != null || !isSpeakerReady()) return;
        try {
            long t0 = System.currentTimeMillis();
            com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig cfg =
                    new com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig(
                            speakerFile.getAbsolutePath(), 2, false, "cpu");
            speakerExtractor = new com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor(null, cfg);
            Log.w(TAG, "speaker extractor ready in " + (System.currentTimeMillis() - t0) + "ms dim=" + speakerExtractor.dim());
        } catch (Throwable t) {
            Log.e(TAG, "initSpeaker failed", t);
            speakerExtractor = null;
        }
    }

    /** 用声纹聚类给每个片段打说话人标签，返回检测到的说话人数。 */
    private int assignSpeakers(AudioDecode.Pcm pcm, List<Seg> segs) {
        if (segs.isEmpty()) return 1;
        initSpeaker();
        if (speakerExtractor == null) {
            String nm = gallery.nextSpeakerName(null);
            for (Seg s : segs) s.who = nm;
            return 1;
        }

        final float KNOWN_TH = 0.45f;   // 与历史声纹匹配（用户命名过的名字内部还会再放宽 22%）
        final float LOCAL_TH = 0.46f;   // 本次录音内归簇
        final int MAX_SPK = 6;

        List<String> names = new ArrayList<>();
        List<float[]> centroids = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        int last = -1;

        for (int i = 0; i < segs.size(); i++) {
            Seg seg = segs.get(i);
            float[] emb = embed(pcm, seg);
            if (emb == null) {
                if (last >= 0) seg.who = names.get(last);
                continue;
            }

            /* 1) 先查历史档案：以前标记过的声音直接用原名字 */
            String known = gallery.match(emb, KNOWN_TH);
            if (known != null) {
                int idx = names.indexOf(known);
                if (idx < 0) {
                    names.add(known);
                    centroids.add(normalizeCopy(emb));
                    counts.add(1);
                    idx = names.size() - 1;
                } else {
                    mergeCentroid(centroids, counts, idx, emb);
                }
                seg.who = known;
                last = idx;
                continue;
            }

            /* 2) 没见过的声音：在本次录音里找最像的簇 */
            int best = -1;
            float bestSim = -1f;
            for (int c = 0; c < centroids.size(); c++) {
                float sim = cosine(emb, centroids.get(c));
                if (sim > bestSim) {
                    bestSim = sim;
                    best = c;
                }
            }
            Log.w(TAG, "seg" + i + " newVoice bestSim="
                    + String.format(java.util.Locale.US, "%.3f", bestSim) + " clusters=" + centroids.size());

            if (best >= 0 && (bestSim >= LOCAL_TH || centroids.size() >= MAX_SPK)) {
                seg.who = names.get(best);
                mergeCentroid(centroids, counts, best, emb);
                last = best;
            } else {
                String nm = gallery.nextSpeakerName(names);
                names.add(nm);
                centroids.add(normalizeCopy(emb));
                counts.add(1);
                seg.who = nm;
                last = names.size() - 1;
            }
        }

        /* 3) 把本次的声音写回档案：新声音权重高一些，已认识的声音只做微调，避免质心被污染 */
        for (int c = 0; c < names.size(); c++) {
            boolean auto = VoiceGallery.isAutoName(names.get(c));
            gallery.observe(names.get(c), centroids.get(c), auto ? 2 : 1);
        }
        Log.w(TAG, "diarization voices=" + names.size() + " gallery=" + gallery.size());
        return Math.max(1, names.size());
    }

    private void mergeCentroid(List<float[]> centroids, List<Integer> counts, int idx, float[] emb) {
        float[] cen = centroids.get(idx);
        int cnt = counts.get(idx);
        if (cen.length != emb.length) return;
        for (int k = 0; k < cen.length; k++) cen[k] = (cen[k] * cnt + emb[k]) / (cnt + 1);
        normalize(cen);
        counts.set(idx, cnt + 1);
    }

    private static float[] normalizeCopy(float[] v) {
        float[] c = v.clone();
        normalize(c);
        return c;
    }

    private float[] embed(AudioDecode.Pcm pcm, Seg seg) {
        try {
            int start = seg.startSample;
            int end = Math.min(seg.endSample, start + AudioDecode.TARGET_RATE * 8);
            if (end - start < AudioDecode.TARGET_RATE / 2) return null;
            float[] samples = pcm.slice(start, end);
            com.k2fsa.sherpa.onnx.OnlineStream st = speakerExtractor.createStream();
            try {
                st.acceptWaveform(samples, AudioDecode.TARGET_RATE);
                if (!speakerExtractor.isReady(st)) return null;
                float[] e = speakerExtractor.compute(st);
                return e == null || e.length == 0 ? null : e;
            } finally {
                st.release();
            }
        } catch (Throwable t) {
            Log.e(TAG, "embed failed", t);
            return null;
        }
    }

    private static float cosine(float[] a, float[] b) {
        int n = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na <= 0 || nb <= 0) return -1f;
        return (float) (dot / (Math.sqrt(na) * Math.sqrt(nb)));
    }

    private static void normalize(float[] v) {
        double n = 0;
        for (float x : v) n += (double) x * x;
        n = Math.sqrt(n);
        if (n <= 0) return;
        for (int i = 0; i < v.length; i++) v[i] /= (float) n;
    }

    /** 基于能量的静音切分：6-25 秒一片。 */
    private List<int[]> split(short[] pcm) {
        List<int[]> out = new ArrayList<>();
        int frame = 320; // 20ms
        int nFrames = pcm.length / frame;
        if (nFrames <= 0) return out;

        double[] rms = new double[nFrames];
        double sum = 0;
        for (int f = 0; f < nFrames; f++) {
            double acc = 0;
            int base = f * frame;
            for (int i = 0; i < frame; i++) {
                double v = pcm[base + i] / 32768.0;
                acc += v * v;
            }
            rms[f] = Math.sqrt(acc / frame);
            sum += rms[f];
        }
        double mean = sum / nFrames;
        double thr = Math.max(0.006, mean * 0.55);

        int start = 0;
        int silence = 0;
        for (int f = 0; f < nFrames; f++) {
            if (rms[f] < thr) silence++;
            else silence = 0;

            int curSamples = (f + 1) * frame - start;
            double curSec = curSamples / (double) AudioDecode.TARGET_RATE;
            /* 切片更细（4~12 秒），避免一段里混进多个说话人导致声纹被平均 */
            boolean cut = (curSec >= 12) || (curSec >= 4 && silence >= 15);
            if (cut) {
                int end = (f + 1) * frame;
                if (hasVoice(rms, start / frame, f, thr)) out.add(new int[]{start, end});
                start = end;
                silence = 0;
            }
        }
        if (start < pcm.length - 4000 && hasVoice(rms, start / frame, nFrames - 1, thr)) {
            out.add(new int[]{start, pcm.length});
        }
        if (out.isEmpty() && pcm.length > 4000) out.add(new int[]{0, pcm.length});
        return out;
    }

    private boolean hasVoice(double[] rms, int from, int to, double thr) {
        int voiced = 0;
        for (int i = Math.max(0, from); i <= to && i < rms.length; i++) {
            if (rms[i] >= thr) voiced++;
        }
        return voiced >= 3;
    }

    /** 去掉 SenseVoice 输出的 <|zh|><|NEUTRAL|> 等标签。 */
    private String clean(String raw) {
        if (raw == null) return "";
        String s = raw.replaceAll("<\\|[^|]*\\|>", " ");
        s = s.replaceAll("[\\uD800-\\uDFFF]", "");
        s = s.replaceAll("\\s+", " ").trim();
        return s;
    }

    public synchronized void release() {
        if (recognizer != null) {
            try {
                recognizer.release();
            } catch (Exception ignored) {
            }
            recognizer = null;
            loadedSpecId = "";
        }
    }
}
