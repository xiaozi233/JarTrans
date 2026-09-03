package com.jartrans.core;

import com.jartrans.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** 全局翻译词典（翻译记忆），JSON 文件存储。 */
public final class Dictionary {

    private final Path path;
    private final Map<String, String> data = new LinkedHashMap<>(); // 原字符串 -&gt; 译文

    public Dictionary(Path path) {
        this.path = path;
        load();
    }

    public Path path() {
        return path;
    }

    public void load() {
        if (!Files.exists(path)) {
            return;
        }
        Object parsed = Json.readFileQuiet(path);
        Map<String, Object> map = Json.object(parsed);
        if (map == null) {
            return;
        }
        data.clear();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (!v.isEmpty()) {
                data.put(e.getKey(), v);
            }
        }
    }

    public void save() throws IOException {
        // sort_keys=True：按码点排序
        Map<String, Object> sorted = new TreeMap<>(Json.CODE_POINT_ORDER);
        sorted.putAll(data);
        Json.writeFile(path, sorted);
    }

    public String get(String orig) {
        return data.get(orig);
    }

    public void add(String orig, String trans) {
        if (orig != null && !orig.isEmpty() && trans != null && !trans.isEmpty()
                && !trans.equals(orig)) {
            data.put(orig, trans);
        }
    }

    public void remove(String orig) {
        data.remove(orig);
    }

    public Map<String, String> entries() {
        return new LinkedHashMap<>(data);
    }

    public Map<String, String> search(String keyword) {
        String q = keyword.toLowerCase();
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : data.entrySet()) {
            if (e.getKey().toLowerCase().contains(q)
                    || e.getValue().toLowerCase().contains(q)) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public int size() {
        return data.size();
    }
}
