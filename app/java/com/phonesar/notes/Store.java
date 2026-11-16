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
import java.util.Comparator;
import java.util.List;

/** 录音元数据存储：records.json，内含转写结果、话题、收藏等。 */
public class Store {

    private static final String TAG = "Store";
    private final File file;
    private final List<JSONObject> items = new ArrayList<>();

    public Store(Context ctx) {
        file = new File(ctx.getFilesDir(), "records.json");
    }

    public synchronized void load() {
        items.clear();
        if (!file.exists()) return;
        try (InputStreamReader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            JSONObject root = new JSONObject(sb.toString());
            JSONArray arr = root.optJSONArray("records");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) items.add(o);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "load failed", e);
        }
    }

    public synchronized void save() {
        try {
            JSONArray arr = new JSONArray();
            for (JSONObject o : items) arr.put(o);
            JSONObject root = new JSONObject();
            root.put("records", arr);
            File tmp = new File(file.getParentFile(), "records.json.tmp");
            try (OutputStreamWriter w = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
                w.write(root.toString());
            }
            if (file.exists() && !file.delete()) Log.w(TAG, "delete old failed");
            if (!tmp.renameTo(file)) Log.w(TAG, "rename failed");
        } catch (Exception e) {
            Log.e(TAG, "save failed", e);
        }
    }

    /** 按创建时间倒序返回快照。 */
    public synchronized List<JSONObject> all() {
        List<JSONObject> copy = new ArrayList<>();
        for (JSONObject o : items) {
            if (o.optLong("deletedAt", 0L) > 0L) continue;
            copy.add(o);
        }
        copy.sort(new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("createdAt"), a.optLong("createdAt"));
            }
        });
        return copy;
    }

    /** 回收站：按删除时间倒序。 */
    public synchronized List<JSONObject> trash() {
        List<JSONObject> list = new ArrayList<>();
        for (JSONObject o : items) {
            if (o.optLong("deletedAt", 0L) > 0L) list.add(o);
        }
        list.sort(new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                return Long.compare(b.optLong("deletedAt", 0L), a.optLong("deletedAt", 0L));
            }
        });
        return list;
    }

    public synchronized JSONObject get(String id) {
        for (JSONObject o : items) {
            if (id.equals(o.optString("id"))) return o;
        }
        return null;
    }

    public synchronized void put(JSONObject rec) {
        String id = rec.optString("id");
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).optString("id"))) {
                items.set(i, rec);
                return;
            }
        }
        items.add(rec);
    }

    public synchronized void remove(String id) {
        for (int i = 0; i < items.size(); i++) {
            if (id.equals(items.get(i).optString("id"))) {
                items.remove(i);
                return;
            }
        }
    }

    public synchronized void patch(String id, JSONObject partial) {
        JSONObject rec = get(id);
        if (rec == null) return;
        java.util.Iterator<String> it = partial.keys();
        while (it.hasNext()) {
            String k = it.next();
            try {
                rec.put(k, partial.get(k));
            } catch (Exception ignored) {
            }
        }
        save();
    }
}
