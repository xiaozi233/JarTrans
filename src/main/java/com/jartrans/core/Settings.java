package com.jartrans.core;

import com.jartrans.core.io.DeferredSaver;
import com.jartrans.core.json.Json;
import com.jartrans.core.log.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** settings.json 读写（界面偏好、反编译器选择等）。 */
public final class Settings {

    private static final System.Logger LOG = Log.get(Settings.class);

    /**
     * 设置键唯一来源：界面代码一律引用常量，禁止散落字面量
     * （SettingsKeysTest 守护常量与默认值登记的一致性）。
     */
    public static final class Keys {

        public static final String DECOMPILER_TYPE = "decompiler_type"; // vineflower|cfr|procyon
        public static final String THEME = "theme"; // system|light|dark
        public static final String DICTIONARY = "dictionary"; // 当前词典名
        public static final String HIDE_EMPTY = "hide_empty"; // 隐藏无可翻译字符串的类
        public static final String STATUS_FILTER = "status_filter"; // 类列表状态筛选
        public static final String ONLY_UNTRANSLATED = "only_untranslated"; // 翻译表格只看未翻译
        public static final String SAVE_TO_DICT = "save_to_dict"; // 保存译文时记入词典
        public static final String LEGEND_VISIBLE = "legend_visible"; // 左栏显示类状态图例
        public static final String DBLCLICK_SOURCE = "dblclick_source"; // 翻译表双击跳源码
        public static final String AUTO_SAVE_TRANSLATION = "auto_save_translation"; // 译文自动保存
        public static final String PACK_AUTHOR = "pack_author"; // 语言包导出默认作者
        /** 自定义快捷键键前缀（key_ + 动作 id），动态键不进默认表。 */
        public static final String SHORTCUT_PREFIX = "key_";

        private Keys() {}
    }

    /** 外部进程反编译器时代遗留的设置键，加载时剔除（其余未知键如 key_* 仍保留）。 */
    private static final Set<String> LEGACY_KEYS =
            Set.of("java_path", "decompiler_path", "decompiler_version", "legend_compact");

    /** 默认值（不可变静态；曾按次重建，快捷键等缺省键查询每次都重新分配整表）。 */
    private static final Map<String, Object> DEFAULTS = buildDefaults();

    /** 界面高频写入的合并落盘延迟（毫秒）。 */
    private static final long SAVE_DELAY_MILLIS = 300;

    private static Map<String, Object> buildDefaults() {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put(Keys.DECOMPILER_TYPE, "");
        d.put(Keys.THEME, "system");
        d.put(Keys.DICTIONARY, "");
        d.put(Keys.HIDE_EMPTY, Boolean.FALSE);
        d.put(Keys.STATUS_FILTER, "all");
        d.put(Keys.ONLY_UNTRANSLATED, Boolean.FALSE);
        d.put(Keys.SAVE_TO_DICT, Boolean.TRUE);
        d.put(Keys.LEGEND_VISIBLE, Boolean.TRUE);
        d.put(Keys.DBLCLICK_SOURCE, Boolean.TRUE);
        d.put(Keys.AUTO_SAVE_TRANSLATION, Boolean.TRUE);
        d.put(Keys.PACK_AUTHOR, "");
        return Collections.unmodifiableMap(d);
    }

    private final Path path;
    private final Map<String, Object> data = new LinkedHashMap<>(DEFAULTS);
    /** 高频 setOrIgnore 的写盘防抖；flush() 供退出前强制落盘。 */
    private final DeferredSaver saver = new DeferredSaver("settings", SAVE_DELAY_MILLIS, this::writeSnapshotQuietly);

    public Settings() {
        this(null);
    }

    public Settings(Path path) {
        this.path = path != null ? path : AppDirs.settingsFile();
        load();
    }

    public static Map<String, Object> defaults() {
        return DEFAULTS;
    }

    public Path path() {
        return path;
    }

    public void load() {
        if (!Files.exists(path)) {
            return;
        }
        Map<String, Object> parsed = Json.readObjectQuiet(path);
        if (parsed == null) {
            return;
        }
        // data 已含 defaults 全部默认键：parsed 覆盖同名键，
        // 额外键（如自定义快捷键 key_*）保持解析顺序追加到末尾，保证跨会话生效
        synchronized (data) {
            data.putAll(parsed);
            data.keySet().removeAll(LEGACY_KEYS);
        }
    }

    public void save() throws IOException {
        Map<String, Object> snapshot;
        synchronized (data) {
            snapshot = new LinkedHashMap<>(data);
        }
        Json.writeFile(path, snapshot);
    }

    /** 立即写出挂着的设置变更（退出前调用）。 */
    public void flush() {
        saver.flush();
    }

    private void writeSnapshotQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "设置写盘失败：" + path, e);
        }
    }

    public Object get(String key) {
        Object v;
        synchronized (data) {
            v = data.get(key);
        }
        return v != null ? v : DEFAULTS.getOrDefault(key, "");
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
        synchronized (data) {
            data.put(key, value);
        }
        save();
    }

    /**
     * 写入并延迟合并落盘；写盘失败只记录日志。
     * 供界面侧使用——设置写不进磁盘不应中断用户当前操作。
     */
    public void setOrIgnore(String key, Object value) {
        synchronized (data) {
            data.put(key, value);
        }
        saver.requestSave();
    }

    /** 修改但不落盘（如退出时批量保存）。 */
    public void setQuiet(String key, Object value) {
        synchronized (data) {
            data.put(key, value);
        }
    }
}
