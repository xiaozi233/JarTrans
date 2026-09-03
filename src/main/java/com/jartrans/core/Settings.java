package com.jartrans.core;

import com.jartrans.core.json.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** settings.json 读写（java 路径、反编译器信息等）。 */
public final class Settings {

    private final Path path;
    private final Map<String, Object> data = defaults();

    public Settings() {
        this(null);
    }

    public Settings(Path path) {
        this.path = path != null ? path : AppDirs.settingsFile();
        load();
    }

    public static Map<String, Object> defaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("java_path", "");
        d.put("decompiler_path", "");
        d.put("decompiler_type", "");       // 'vineflower' | 'cfr' | 'procyon'
        d.put("decompiler_version", "");
        d.put("theme", "system");           // 'system' | 'light' | 'dark'
        d.put("dictionary", "");            // 当前词典名
        d.put("hide_empty", Boolean.FALSE); // 隐藏无可翻译字符串的类
        d.put("status_filter", "all");      // 类列表状态筛选
        d.put("only_untranslated", Boolean.FALSE); // 翻译表格只看未翻译
        d.put("save_to_dict", Boolean.TRUE);       // 保存译文时记入词典
        d.put("legend_visible", Boolean.TRUE);     // 左栏显示类状态图例
        d.put("dblclick_source", Boolean.TRUE);    // 翻译表双击跳源码
        d.put("auto_save_translation", Boolean.TRUE); // 译文自动保存
        return d;
    }

    public Path path() {
        return path;
    }

    public void load() {
        if (!Files.exists(path)) {
            return;
        }
        Map<String, Object> parsed = Json.object(Json.readFileQuiet(path));
        if (parsed == null) {
            return;
        }
        for (String k : defaults().keySet()) {
            if (parsed.containsKey(k)) {
                data.put(k, parsed.get(k));
            }
        }
        // 额外键（如自定义快捷键 key_*）也一并载入，保证跨会话生效
        for (Map.Entry<String, Object> e : parsed.entrySet()) {
            if (!data.containsKey(e.getKey())) {
                data.put(e.getKey(), e.getValue());
            }
        }
    }

    public void save() throws IOException {
        Json.writeFile(path, data);
    }

    public Object get(String key) {
        Object v = data.get(key);
        return v != null ? v : defaults().getOrDefault(key, "");
    }

    public String getString(String key) {
        return String.valueOf(get(key));
    }

    public boolean getBool(String key) {
        Object value = get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        String s = String.valueOf(value).trim().toLowerCase();
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }

    public void set(String key, Object value) throws IOException {
        data.put(key, value);
        save();
    }

    /** 修改但不立即落盘（如退出时批量保存）。 */
    public void setQuiet(String key, Object value) {
        data.put(key, value);
    }
}
