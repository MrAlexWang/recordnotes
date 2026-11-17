package com.phonesar.notes;

import android.content.Context;
import android.util.Log;

import java.io.File;

/**
 * 离线翻译引擎：llama.cpp + GGUF。
 * 具体用哪个模型由 ModelManager 决定（默认腾讯 HY-MT1.5-1.8B，也可以换成
 * Qwen2.5 这类通用小模型，或用户自己的 GGUF 直链），模型不打进 APK。
 */
public class TranslationEngine {

    private static final String TAG = "Translate";

    public interface Progress {
        /** percent < 0 表示失败 */
        void onProgress(int percent, String message);
    }

    private final Context ctx;
    private final ModelManager models;
    private long nativeHandle = 0;
    private String loadedId = "";

    public TranslationEngine(Context ctx, ModelManager models) {
        this.ctx = ctx.getApplicationContext();
        this.models = models;
    }

    /** 当前生效的翻译模型 */
    public ModelManager.Spec spec() {
        return models.active("tr");
    }

    public boolean isReady() {
        return models.ready(spec());
    }

    public boolean isDownloading() {
        return models.downloading("tr");
    }

    public long downloadedBytes() {
        return models.downloadedBytes(spec());
    }

    public String statusJson() {
        ModelManager.Spec s = spec();
        return "{\"ready\":" + models.ready(s)
                + ",\"downloading\":" + models.downloading("tr")
                + ",\"downloaded\":" + models.downloadedBytes(s)
                + ",\"total\":" + Math.max(s.bytes, 0)
                + ",\"id\":" + org.json.JSONObject.quote(s.id)
                + ",\"name\":" + org.json.JSONObject.quote(s.name) + "}";
    }

    /** 下载当前翻译模型（断点续传）。 */
    public boolean download(Progress p) {
        ModelManager.Spec s = spec();
        if (models.ready(s)) {
            if (p != null) p.onProgress(100, "翻译模型已就绪");
            return true;
        }
        return models.download(s, (pct, msg) -> {
            if (p != null) p.onProgress(pct, msg);
        });
    }

    /* -------------------------- 推理 -------------------------- */

    public synchronized void load(int threads) throws Exception {
        ModelManager.Spec s = spec();
        if (nativeHandle != 0 && s.id.equals(loadedId)) return;
        if (!models.ready(s)) throw new IllegalStateException("翻译模型未下载");
        release();
        long t0 = System.currentTimeMillis();
        File model = models.fileOf(s);
        nativeHandle = nativeInit(model.getAbsolutePath(), threads);
        if (nativeHandle == 0) throw new IllegalStateException("翻译模型加载失败");
        loadedId = s.id;
        Log.w(TAG, "translation model(" + s.id + ") loaded in " + (System.currentTimeMillis() - t0) + "ms");
    }

    /** 逐句翻译：中文→英文，非中文→中文。 */
    public synchronized String translate(String text) throws Exception {
        if (nativeHandle == 0) throw new IllegalStateException("翻译模型未加载");
        if (text == null || text.trim().isEmpty()) return "";
        boolean cjk = containsCJK(text);
        String target = cjk ? "English" : "Chinese";
        String prompt = buildPrompt(spec().family, text, target);
        String out = nativeTranslate(nativeHandle, prompt);
        return out == null ? "" : out.trim();
    }

    public synchronized void release() {
        if (nativeHandle != 0) {
            try {
                nativeFree(nativeHandle);
            } catch (Throwable ignored) {
            }
            nativeHandle = 0;
            loadedId = "";
        }
    }

    static boolean containsCJK(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fff) return true;
        }
        return false;
    }

    /** 不同模型的提示词不一样：翻译专用模型要求极简指令，通用对话模型要交代角色。 */
    private static String buildPrompt(String family, String text, String targetLang) {
        if ("hymt".equals(family)) {
            return "Translate the following segment into " + targetLang
                    + ", without additional explanation.\n\n" + text;
        }
        return "You are a professional translator. Translate the text below into " + targetLang
                + ". Output only the translation, without explanation or quotes.\n\n"
                + "Text: " + text + "\nTranslation:";
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
