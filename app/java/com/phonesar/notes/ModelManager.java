package com.phonesar.notes;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地模型市场。
 *
 * - 内置若干可下载的转写 / 翻译模型，模型文件不打进 APK；
 * - 用户可以在设置里切换，也可以用「自定义」填自己的直链；
 * - 下载支持断点续传，两种下载源（hf-mirror / huggingface / ModelScope）自动回退。
 */
public class ModelManager {

    private static final String TAG = "ModelManager";
    private static final String SP = "models";

    private static final String BASE_HF = "https://hf-mirror.com/";
    private static final String BASE_HF2 = "https://huggingface.co/";

    /** 下载 / 准备进度，percent < 0 表示失败 */
    public interface Progress {
        void onProgress(int percent, String message);
    }

    /** 一个模型的完整描述 */
    public static class Spec {
        public String id = "";
        public String kind = "asr";      // asr | tr
        public String family = "";       // sensevoice | paraformer | hymt | chat
        public String name = "";
        public String note = "";
        public String size = "";         // 展示用，例如 "228 MB"
        public String file = "";         // 主模型文件名
        public String[] urls = new String[0];
        public String extraFile = "";    // 附加文件（tokens.txt）
        public String[] extraUrls = new String[0];
        public long bytes = 0;           // 主文件字节数，0 = 未知
        public long extraBytes = 0;
        public boolean builtin = true;

        public JSONObject toJson(boolean ready, boolean active) {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("kind", kind);
                o.put("family", family);
                o.put("name", name);
                o.put("note", note);
                o.put("size", size);
                o.put("bytes", bytes);
                o.put("ready", ready);
                o.put("active", active);
                o.put("builtin", builtin);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private final Context ctx;
    private final SharedPreferences sp;

    public ModelManager(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.sp = this.ctx.getSharedPreferences(SP, Context.MODE_PRIVATE);
    }

    /* -------------------------- 目录 -------------------------- */

    public File asrDir() {
        File d = new File(ctx.getFilesDir(), "model");
        if (!d.exists() && !d.mkdirs()) Log.w(TAG, "mkdir asr dir failed");
        return d;
    }

    public File trDir() {
        File ext = ctx.getExternalFilesDir("translate");
        File d = ext != null ? ext : new File(ctx.getFilesDir(), "translate");
        if (!d.exists() && !d.mkdirs()) Log.w(TAG, "mkdir tr dir failed");
        return d;
    }

    public File dir(String kind) {
        return "tr".equals(kind) ? trDir() : asrDir();
    }

    /* -------------------------- 清单 -------------------------- */

    /** 转写模型清单：第 1 个是默认模型。 */
    public List<Spec> asrPresets() {
        List<Spec> list = new ArrayList<>();

        Spec svInt8 = new Spec();
        svInt8.id = "sv-int8";
        svInt8.kind = "asr";
        svInt8.family = "sensevoice";
        svInt8.name = "SenseVoice-Small int8";
        svInt8.note = "默认 · 中文 / 英文 / 日文 / 韩文 / 粤语，速度最快";
        svInt8.size = "228 MB";
        svInt8.file = "model.int8.onnx";
        svInt8.extraFile = "tokens.txt";
        svInt8.bytes = 228L * 1024 * 1024;
        svInt8.urls = hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.int8.onnx");
        svInt8.extraUrls = hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt");
        list.add(svInt8);

        Spec pf = new Spec();
        pf.id = "paraformer-zh";
        pf.kind = "asr";
        pf.family = "paraformer";
        pf.name = "Paraformer-zh int8";
        pf.note = "纯中文优化，标点更自然，适合会议 / 访谈";
        pf.size = "217 MB";
        pf.file = "paraformer-zh.int8.onnx";
        pf.extraFile = "paraformer-zh.tokens.txt";
        pf.bytes = 227330205L;
        pf.urls = hf("csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main/model.int8.onnx");
        pf.extraUrls = hf("csukuangfj/sherpa-onnx-paraformer-zh-2024-03-09/resolve/main/tokens.txt");
        list.add(pf);

        Spec svFp32 = new Spec();
        svFp32.id = "sv-fp32";
        svFp32.kind = "asr";
        svFp32.family = "sensevoice";
        svFp32.name = "SenseVoice-Small fp32";
        svFp32.note = "高精度版本，体积大、速度慢一些，适合性能好的手机";
        svFp32.size = "894 MB";
        svFp32.file = "model.fp32.onnx";
        svFp32.extraFile = "tokens.txt";
        svFp32.bytes = 937617178L;
        svFp32.urls = hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/model.onnx");
        svFp32.extraUrls = hf("csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/main/tokens.txt");
        list.add(svFp32);

        list.add(customSpec("asr"));
        return list;
    }

    /** 翻译模型清单：第 1 个是默认模型。 */
    public List<Spec> trPresets() {
        List<Spec> list = new ArrayList<>();

        Spec hy = new Spec();
        hy.id = "hy-mt15";
        hy.kind = "tr";
        hy.family = "hymt";
        hy.name = "腾讯混元 HY-MT1.5-1.8B";
        hy.note = "默认 · 翻译专用模型，中英互译质量最好";
        hy.size = "1.08 GB";
        hy.file = "HY-MT1.5-1.8B-Q4_K_M.gguf";
        hy.bytes = 1133080512L;
        hy.urls = new String[]{
                "https://modelscope.cn/models/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/resolve/master/" + hy.file,
                "https://hf-mirror.com/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/resolve/main/" + hy.file,
                "https://huggingface.co/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/resolve/main/" + hy.file
        };
        list.add(hy);

        Spec q15 = new Spec();
        q15.id = "qwen25-1.5b";
        q15.kind = "tr";
        q15.family = "chat";
        q15.name = "Qwen2.5-1.5B-Instruct";
        q15.note = "通用小模型，术语和长句更灵活，速度略慢于默认模型";
        q15.size = "1.04 GB";
        q15.file = "qwen2.5-1.5b-instruct-q4_k_m.gguf";
        q15.bytes = 1117320736L;
        q15.urls = hf("Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/" + q15.file);
        list.add(q15);

        Spec q05 = new Spec();
        q05.id = "qwen25-0.5b";
        q05.kind = "tr";
        q05.family = "chat";
        q05.name = "Qwen2.5-0.5B-Instruct";
        q05.note = "轻量版，占内存小、出词快，适合低配手机";
        q05.size = "469 MB";
        q05.file = "qwen2.5-0.5b-instruct-q4_k_m.gguf";
        q05.bytes = 491400032L;
        q05.urls = hf("Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/" + q05.file);
        list.add(q05);

        list.add(customSpec("tr"));
        return list;
    }

    private static String[] hf(String path) {
        return new String[]{BASE_HF + path, BASE_HF2 + path};
    }

    /** 「自定义」占位项：不带 url，具体内容由用户填写 */
    private Spec customSpec(String kind) {
        Spec s = new Spec();
        s.id = "custom";
        s.kind = kind;
        s.family = "custom";
        s.name = "自定义模型";
        s.note = "asr".equals(kind)
                ? "填入 ONNX 直链 + 词表直链，可选 sensevoice / paraformer"
                : "填入任意 GGUF 直链（llama.cpp 可加载的模型）";
        s.size = "";
        s.builtin = false;
        JSONObject c = customJson(kind);
        if (c != null) {
            s.file = c.optString("file", "");
            s.extraFile = c.optString("extraFile", "");
            s.bytes = c.optLong("bytes", 0);
            String url = c.optString("url", "");
            String extra = c.optString("extraUrl", "");
            s.urls = url.isEmpty() ? new String[0] : new String[]{url};
            s.extraUrls = extra.isEmpty() ? new String[0] : new String[]{extra};
            String fam = c.optString("family", "");
            if (!fam.isEmpty()) s.family = fam;
        }
        return s;
    }

    /* -------------------------- 选择与自定义 -------------------------- */

    public String selectedId(String kind) {
        String def = "asr".equals(kind) ? "sv-int8" : "hy-mt15";
        return sp.getString(kind + "Id", def);
    }

    public void select(String kind, String id) {
        sp.edit().putString(kind + "Id", id == null ? "" : id).apply();
    }

    public JSONObject customJson(String kind) {
        String raw = sp.getString(kind + "Custom", "");
        if (raw == null || raw.isEmpty()) return null;
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public void saveCustom(String kind, String json) {
        sp.edit().putString(kind + "Custom", json == null ? "" : json)
                .putString(kind + "Id", "custom").apply();
    }

    /** 当前生效的模型 */
    public Spec active(String kind) {
        List<Spec> list = "tr".equals(kind) ? trPresets() : asrPresets();
        String id = selectedId(kind);
        for (Spec s : list) {
            if (!s.id.equals(id)) continue;
            /* 自定义项还没填直链时回退到默认模型，避免把功能弄坏 */
            if (!s.builtin && (s.urls == null || s.urls.length == 0 || s.file == null || s.file.isEmpty())) {
                return list.get(0);
            }
            return s;
        }
        return list.get(0);
    }

    /* -------------------------- 状态 -------------------------- */

    public File fileOf(Spec s) {
        return new File(dir(s.kind), s.file);
    }

    public File extraOf(Spec s) {
        return s.extraFile == null || s.extraFile.isEmpty() ? null : new File(dir(s.kind), s.extraFile);
    }

    /** 已下载字节数（含 .part 断点文件） */
    public long downloadedBytes(Spec s) {
        if (s == null || s.file == null || s.file.isEmpty()) return 0;
        File f = fileOf(s);
        if (f.exists()) return f.length();
        File part = new File(f.getAbsolutePath() + ".part");
        return part.exists() ? part.length() : 0;
    }

    private boolean mainReady(Spec s) {
        if (s.file == null || s.file.isEmpty()) return false;
        File f = fileOf(s);
        if (!f.exists() || f.length() < 1024 * 1024) return false;
        if (s.bytes > 0 && f.length() < s.bytes * 99 / 100) return false;
        return true;
    }

    private boolean extraReady(Spec s) {
        File f = extraOf(s);
        return f == null || (f.exists() && f.length() > 1000);
    }

    public boolean ready(Spec s) {
        return mainReady(s) && extraReady(s);
    }

    public String stateJson() {
        JSONObject root = new JSONObject();
        try {
            root.put("asr", kindJson("asr"));
            root.put("tr", kindJson("tr"));
        } catch (Exception ignored) {
        }
        return root.toString();
    }

    private JSONObject kindJson(String kind) {
        JSONObject o = new JSONObject();
        List<Spec> list = "tr".equals(kind) ? trPresets() : asrPresets();
        String sel = selectedId(kind);
        Spec act = active(kind);
        JSONArray arr = new JSONArray();
        for (Spec s : list) {
            /* 生效的模型标成「使用中」；自定义项没配置时 act 会回退到默认模型 */
            arr.put(s.toJson(ready(s), s.id.equals(act.id)));
        }
        try {
            o.put("selected", sel);
            o.put("activeName", act.name);
            o.put("activeId", act.id);
            o.put("activeReady", ready(act));
            o.put("downloading", downloading(kind));
            o.put("list", arr);
            JSONObject c = customJson(kind);
            if (c != null) o.put("custom", c);
        } catch (Exception ignored) {
        }
        return o;
    }

    private final java.util.Set<String> busy = java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    public boolean downloading(String kind) {
        return busy.contains(kind);
    }

    /* -------------------------- 下载 -------------------------- */

    public boolean download(Spec s, Progress p) {
        if (ready(s)) {
            if (p != null) p.onProgress(100, "模型已就绪");
            return true;
        }
        busy.add(s.kind);
        try {
            File d = dir(s.kind);
            if (!d.exists() && !d.mkdirs()) return false;
            long total = s.bytes + (s.extraBytes > 0 ? s.extraBytes : 0);
            long[] done = {0};

            if (!mainReady(s)) {
                if (s.urls == null || s.urls.length == 0) {
                    if (p != null) p.onProgress(-1, "还没有配置模型直链");
                    return false;
                }
                File dst = fileOf(s);
                fetch(s.urls, dst, s.bytes, p, done, total, "tr".equals(s.kind) ? "正在下载翻译大模型" : "正在下载转写大模型");
            } else {
                done[0] = Math.max(0, s.bytes);
            }

            if (!extraReady(s)) {
                File dst = extraOf(s);
                fetch(s.extraUrls, dst, s.extraBytes, p, done, total, "正在下载词表");
            }

            boolean ok = ready(s);
            if (p != null) {
                if (ok) p.onProgress(100, "tr".equals(s.kind) ? "翻译模型已就绪" : "转写模型已就绪");
                else p.onProgress(-1, "模型下载不完整，请重试");
            }
            return ok;
        } catch (Exception e) {
            Log.e(TAG, "download failed", e);
            if (p != null) p.onProgress(-1, "模型下载失败：" + e.getMessage());
            return false;
        } finally {
            busy.remove(s.kind);
        }
    }

    /** 依次尝试多个下载源，支持断点续传 */
    private void fetch(String[] urls, File dst, long expectBytes, Progress p,
                       long[] done, long total, String label) throws Exception {
        Exception last = null;
        for (String u : urls) {
            if (u == null || u.isEmpty()) continue;
            try {
                fetchOne(u, dst, p, done, total, label);
                if (expectBytes > 0 && dst.length() < expectBytes * 99 / 100) {
                    throw new java.io.IOException("文件不完整 " + dst.length());
                }
                return;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "download failed " + u + " -> " + e.getMessage());
            }
        }
        throw last != null ? last : new java.io.IOException("下载失败");
    }

    private void fetchOne(String urlStr, File dst, Progress p, long[] done,
                          long total, String label) throws Exception {
        File part = new File(dst.getAbsolutePath() + ".part");
        long base = done[0];
        long have = part.exists() ? part.length() : 0;
        if (dst.exists() && dst.length() > 1024 * 1024) {
            /* 已经有完整文件就不重复下 */
            done[0] = base + dst.length();
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "RecordNotes/1.0");
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
        int code = conn.getResponseCode();
        if (code != 200 && code != 206) {
            try { conn.disconnect(); } catch (Exception ignored) {}
            throw new java.io.IOException("HTTP " + code);
        }
        if (code == 200 && have > 0) {
            have = 0;
            if (part.exists() && !part.delete()) Log.w(TAG, "delete part failed");
        }
        long lastEmit = 0;
        try (InputStream in = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            raf.seek(have);
            byte[] buf = new byte[256 * 1024];
            int r;
            long written = have;
            while ((r = in.read(buf)) > 0) {
                raf.write(buf, 0, r);
                written += r;
                done[0] = base + written;
                long now = System.currentTimeMillis();
                if (p != null && now - lastEmit > 600) {
                    lastEmit = now;
                    long denom = total > 0 ? total : Math.max(done[0], 1);
                    int pct = (int) Math.min(99, done[0] * 100 / denom);
                    p.onProgress(pct, String.format(Locale.US, "%s %d%%（%d / %d MB）",
                            label, pct, done[0] / 1048576, Math.max(done[0], total) / 1048576));
                }
            }
        } finally {
            try { conn.disconnect(); } catch (Exception ignored) {}
        }
        if (dst.exists() && !dst.delete()) Log.w(TAG, "delete old model failed");
        if (!part.renameTo(dst)) throw new java.io.IOException("重命名失败");
    }

    public void delete(Spec s) {
        if (s == null) return;
        File f = fileOf(s);
        deleteFile(f);
        deleteFile(new File(f.getAbsolutePath() + ".part"));
        File e = extraOf(s);
        if (e != null) {
            deleteFile(e);
            deleteFile(new File(e.getAbsolutePath() + ".part"));
        }
    }

    private static void deleteFile(File f) {
        try {
            if (f != null && f.exists() && !f.delete()) Log.w(TAG, "delete failed " + f);
        } catch (Exception ignored) {
        }
    }
}
