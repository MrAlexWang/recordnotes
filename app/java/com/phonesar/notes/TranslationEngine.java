package com.phonesar.notes;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Locale;

/**
 * 离线翻译引擎：腾讯 HY-MT1.5-1.8B（GGUF，llama.cpp）。
 * 模型不打进 APK，首次使用时从魔搭（ModelScope）下载，之后完全离线。
 */
public class TranslationEngine {

    private static final String TAG = "Translate";

    /** Q4_K_M 量化版本：1,133,080,512 字节 */
    static final long MODEL_BYTES = 1133080512L;
    static final String MODEL_NAME = "HY-MT1.5-1.8B-Q4_K_M.gguf";
    static final String MODEL_SHA256 = "4383ac0c3c8e476de98ff979c2a3f069f8c4fb385e7860cf2d28da896cc477c7";

    /** 主源走魔搭（国内直连），备用走 HuggingFace */
    static final String[] MODEL_URLS = {
            "https://modelscope.cn/models/Tencent-Hunyuan/HY-MT1.5-1.8B-GGUF/resolve/master/" + MODEL_NAME,
            "https://huggingface.co/tencent/HY-MT1.5-1.8B-GGUF/resolve/main/" + MODEL_NAME
    };

    public interface Progress {
        /** percent < 0 表示失败 */
        void onProgress(int percent, String message);
    }

    private final Context ctx;
    private final File dir;
    private final File modelFile;
    private final File partFile;
    private long nativeHandle = 0;
    private volatile boolean downloading = false;

    public TranslationEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        File ext = this.ctx.getExternalFilesDir("translate");
        this.dir = ext != null ? ext : new File(this.ctx.getFilesDir(), "translate");
        this.modelFile = new File(dir, MODEL_NAME);
        this.partFile = new File(dir, MODEL_NAME + ".part");
        if (!dir.exists() && !dir.mkdirs()) Log.w(TAG, "mkdir translate failed");
    }

    public boolean isReady() {
        return modelFile.exists() && modelFile.length() == MODEL_BYTES;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public long downloadedBytes() {
        return partFile.exists() ? partFile.length() : (modelFile.exists() ? modelFile.length() : 0);
    }

    public String statusJson() {
        return "{\"ready\":" + isReady() + ",\"downloading\":" + downloading
                + ",\"downloaded\":" + downloadedBytes() + ",\"total\":" + MODEL_BYTES + "}";
    }

    /** 断点续传下载翻译模型。 */
    public boolean download(Progress p) {
        if (isReady()) {
            if (p != null) p.onProgress(100, "翻译模型已就绪");
            return true;
        }
        downloading = true;
        boolean ok = false;
        try {
            for (String urlStr : MODEL_URLS) {
                try {
                    if (fetch(urlStr, p)) {
                        ok = true;
                        break;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "download from " + urlStr + " failed: " + e.getMessage());
                }
            }
            if (ok) {
                if (partFile.exists() && !partFile.renameTo(modelFile)) {
                    Log.e(TAG, "rename model failed");
                    ok = false;
                }
                if (ok && modelFile.length() != MODEL_BYTES) {
                    Log.e(TAG, "size mismatch " + modelFile.length());
                    modelFile.delete();
                    ok = false;
                }
            }
        } finally {
            downloading = false;
        }
        if (p != null) {
            if (ok) p.onProgress(100, "翻译模型已就绪");
            else p.onProgress(-1, "翻译模型下载失败，请检查网络后重试");
        }
        return ok;
    }

    private boolean fetch(String urlStr, Progress p) throws Exception {
        long have = partFile.exists() ? partFile.length() : 0;
        if (have >= MODEL_BYTES) return true;

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "RecordNotes/1.0");
        if (have > 0) conn.setRequestProperty("Range", "bytes=" + have + "-");
        int code = conn.getResponseCode();
        if (code != 200 && code != 206) {
            conn.disconnect();
            throw new java.io.IOException("HTTP " + code);
        }
        // 服务器不支持断点续传就从 0 开始
        if (code == 200 && have > 0) {
            have = 0;
            if (partFile.exists() && !partFile.delete()) Log.w(TAG, "delete part failed");
        }

        long total = MODEL_BYTES;
        long done = have;
        long lastEmit = 0;
        try (InputStream in = conn.getInputStream();
             RandomAccessFile raf = new RandomAccessFile(partFile, "rw")) {
            raf.seek(have);
            byte[] buf = new byte[256 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) {
                raf.write(buf, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (p != null && now - lastEmit > 700) {
                    lastEmit = now;
                    int pct = (int) (done * 100 / total);
                    p.onProgress(Math.min(99, pct), String.format(Locale.US,
                            "正在下载翻译大模型 %d%%（%d / %d MB）", pct, done / 1048576, total / 1048576));
                }
            }
        } finally {
            conn.disconnect();
        }
        return partFile.length() >= MODEL_BYTES;
    }

    /* -------------------------- 推理 -------------------------- */

    public synchronized void load(int threads) throws Exception {
        if (nativeHandle != 0) return;
        if (!isReady()) throw new IllegalStateException("翻译模型未下载");
        long t0 = System.currentTimeMillis();
        nativeHandle = nativeInit(modelFile.getAbsolutePath(), threads);
        if (nativeHandle == 0) throw new IllegalStateException("翻译模型加载失败");
        Log.w(TAG, "translation model loaded in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 逐句翻译：中文→英文，非中文→中文。 */
    public synchronized String translate(String text) throws Exception {
        if (nativeHandle == 0) throw new IllegalStateException("翻译模型未加载");
        if (text == null || text.trim().isEmpty()) return "";
        boolean cjk = containsCJK(text);
        String target = cjk ? "English" : "Chinese";
        String prompt = buildPrompt(text, target);
        String out = nativeTranslate(nativeHandle, prompt);
        return out == null ? "" : out.trim();
    }

    public synchronized void translateAll(List<String> texts, Progress p) throws Exception {
        int n = texts.size();
        for (int i = 0; i < n; i++) {
            if (p != null) p.onProgress((i * 100) / Math.max(1, n), "正在翻译 " + (i + 1) + "/" + n);
        }
    }

    public synchronized void release() {
        if (nativeHandle != 0) {
            try {
                nativeFree(nativeHandle);
            } catch (Throwable ignored) {
            }
            nativeHandle = 0;
        }
    }

    static boolean containsCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return true;
        }
        return false;
    }

    private static String buildPrompt(String text, String targetLang) {
        return "Translate the following segment into " + targetLang
                + ", without additional explanation.\n\n" + text;
    }

    /* -------------------------- JNI -------------------------- */

    private static native long nativeInit(String modelPath, int threads);

    private static native String nativeTranslate(long handle, String prompt);

    private static native void nativeFree(long handle);

    private static boolean nativeOk = false;

    public static boolean hasNative() {
        return nativeOk;
    }

    static {
        try {
            System.loadLibrary("hytrans");
            nativeOk = true;
        } catch (Throwable t) {
            Log.e(TAG, "load hytrans failed", t);
        }
    }
}
