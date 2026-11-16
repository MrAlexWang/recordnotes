package com.phonesar.notes;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 跨录音的声纹档案：记住每个名字的声音特征。
 * 再次出现同一把声音时直接复用名字，没见过的按「说话人 N」递增编号。
 */
public class VoiceGallery {

    private static final String TAG = "VoiceGallery";
    private static final Pattern SPEAKER_NUM = Pattern.compile("^说话人\\s*(\\d+)$");

    public static class Entry {
        public String name;
        public float[] emb;
        public int count;

        Entry(String name, float[] emb, int count) {
            this.name = name;
            this.emb = emb;
            this.count = count;
        }
    }

    private final File file;
    private final List<Entry> entries = new ArrayList<>();

    public VoiceGallery(Context ctx) {
        file = new File(ctx.getFilesDir(), "voiceprints.json");
        load();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized String info() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (sb.length() > 0) sb.append('|');
            sb.append(e.name).append(':').append(e.count);
        }
        return sb.toString();
    }

    /** 命中历史声纹就返回对应名字；没命中返回 null。用户自己命名过的人放宽阈值。 */
    public synchronized String match(float[] emb, float baseThreshold) {
        if (emb == null || emb.length == 0) return null;
        String bestName = null;
        float bestSim = -1f;
        float bestTh = baseThreshold;
        for (Entry e : entries) {
            if (e.emb == null || e.emb.length != emb.length) continue;
            float sim = cosine(emb, e.emb);
            float th = isAutoName(e.name) ? baseThreshold : baseThreshold * 0.78f;
            if (sim >= th && sim > bestSim) {
                bestSim = sim;
                bestName = e.name;
                bestTh = th;
            }
        }
        if (bestName != null) {
            Log.w(TAG, "voice matched " + bestName + " sim="
                    + String.format(java.util.Locale.US, "%.3f", bestSim) + " th="
                    + String.format(java.util.Locale.US, "%.3f", bestTh));
            return bestName;
        }
        return null;
    }

    /** 「说话人 N」这种自动编号，视为用户还没确认身份。 */
    public static boolean isAutoName(String name) {
        return name != null && SPEAKER_NUM.matcher(name.trim()).matches();
    }

    /** 下一个可用的「说话人 N」，会避开本次已经用掉的名字。 */
    public synchronized String nextSpeakerName(java.util.Collection<String> reserved) {
        int max = 0;
        for (Entry e : entries) {
            Matcher m = SPEAKER_NUM.matcher(e.name);
            if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
        }
        if (reserved != null) {
            for (String r : reserved) {
                Matcher m = SPEAKER_NUM.matcher(String.valueOf(r));
                if (m.matches()) max = Math.max(max, Integer.parseInt(m.group(1)));
            }
        }
        int n = max + 1;
        String name = "说话人 " + n;
        while (reserved != null && reserved.contains(name)) {
            n++;
            name = "说话人 " + n;
        }
        return name;
    }

    /** 记录/更新某个名字的声纹质心。weight 越大越偏向新样本。 */
    public synchronized void observe(String name, float[] emb, int weight) {
        if (name == null || emb == null || emb.length == 0) return;
        int w = Math.max(1, weight);
        for (Entry e : entries) {
            if (!e.name.equals(name)) continue;
            if (e.emb == null || e.emb.length != emb.length) {
                e.emb = emb.clone();
            } else {
                int c = Math.max(1, e.count);
                for (int k = 0; k < e.emb.length; k++) {
                    e.emb[k] = (e.emb[k] * c + emb[k] * w) / (c + w);
                }
                normalize(e.emb);
            }
            e.count += w;
            save();
            return;
        }
        float[] copy = emb.clone();
        normalize(copy);
        entries.add(new Entry(name, copy, w));
        save();
    }

    public synchronized void observe(String name, float[] emb) {
        observe(name, emb, 1);
    }

    /** 全局改名，目标已存在则合并。 */
    public synchronized void rename(String from, String to) {
        if (from == null || to == null || from.equals(to)) return;
        Entry src = null;
        Entry dst = null;
        for (Entry e : entries) {
            if (e.name.equals(from)) src = e;
            if (e.name.equals(to)) dst = e;
        }
        if (src == null) return;
        if (dst == null) {
            src.name = to;
        } else {
            if (src.emb != null && dst.emb != null && src.emb.length == dst.emb.length) {
                int c = Math.max(1, dst.count);
                int sc = Math.max(1, src.count);
                for (int k = 0; k < dst.emb.length; k++) {
                    dst.emb[k] = (dst.emb[k] * c + src.emb[k] * sc) / (c + sc);
                }
                normalize(dst.emb);
            }
            dst.count += src.count;
            entries.remove(src);
        }
        save();
        Log.w(TAG, "renamed voice " + from + " -> " + to);
    }

    public synchronized void clear() {
        entries.clear();
        save();
    }

    private void load() {
        entries.clear();
        if (!file.exists()) return;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("voices");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String name = o.optString("name");
                JSONArray ea = o.optJSONArray("emb");
                if (name.isEmpty() || ea == null) continue;
                float[] emb = new float[ea.length()];
                for (int k = 0; k < ea.length(); k++) emb[k] = (float) ea.optDouble(k, 0);
                entries.add(new Entry(name, emb, o.optInt("count", 1)));
            }
            Log.w(TAG, "gallery loaded " + entries.size() + " voices");
        } catch (Exception e) {
            Log.e(TAG, "load failed", e);
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("count", e.count);
                JSONArray ea = new JSONArray();
                for (float v : e.emb) ea.put((double) v);
                o.put("emb", ea);
                arr.put(o);
            }
            JSONObject root = new JSONObject();
            root.put("voices", arr);
            File tmp = new File(file.getParentFile(), "voiceprints.json.tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            if (file.exists() && !file.delete()) Log.w(TAG, "delete old voiceprints failed");
            if (!tmp.renameTo(file)) Log.w(TAG, "rename voiceprints failed");
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
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
}
