package com.jartrans.core;

import com.jartrans.core.json.Json;
import com.jartrans.core.log.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** 全局翻译词典（翻译记忆），JSON 文件存储。 */
public final class Dictionary {

    private static final System.Logger LOG = Log.get(Dictionary.class);

    private final Path path;
    private final Map<String, String> data = new LinkedHashMap<>(); // 原字符串 -&gt; 译文
    /** search 用的平行小写索引（惰性构建）；数据变更时置空重建。 */
    private List<Map.Entry<String, String>> searchIndex;
    private String[] lowerKeys;
    private String[] lowerValues;

    public Dictionary(Path path) {
        this.path = path;
        load();
    }

    /** 数据变更后必须调用：搜索索引失效。 */
    private void invalidateSearchIndex() {
        searchIndex = null;
        lowerKeys = null;
        lowerValues = null;
    }

    private void ensureSearchIndex() {
        if (searchIndex != null) {
            return;
        }
        int n = data.size();
        searchIndex = new ArrayList<>(data.entrySet());
        lowerKeys = new String[n];
        lowerValues = new String[n];
        int i = 0;
        for (Map.Entry<String, String> e : data.entrySet()) {
            lowerKeys[i] = e.getKey().toLowerCase(Locale.ROOT);
            lowerValues[i] = e.getValue().toLowerCase(Locale.ROOT);
            i++;
        }
    }

    public Path path() {
        return path;
    }

    public void load() {
        if (!Files.exists(path)) {
            return;
        }
        Object parsed;
        try {
            parsed = Json.readFile(path);
        } catch (IOException | RuntimeException e) {
            // 损坏文件先备份再按空词典继续：否则后续 save 会把用户数据悄悄覆盖成空表
            backupCorrupt(e);
            data.clear();
            return;
        }
        Map<String, Object> map = Json.object(parsed);
        if (map == null) {
            backupCorrupt(new IOException("根节点不是 JSON 对象"));
            data.clear();
            return;
        }
        data.clear();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String v = e.getValue() == null ? "" : String.valueOf(e.getValue());
            if (!v.isEmpty()) {
                data.put(e.getKey(), v);
            }
        }
        invalidateSearchIndex();
    }

    /** 把损坏的词典文件复制为同级 .corrupt 备份（已存在则追加序号），原文件保留待覆盖。 */
    private void backupCorrupt(Exception cause) {
        LOG.log(System.Logger.Level.ERROR, "词典文件损坏，已备份并按空词典加载：" + path, cause);
        try {
            Path backup = path.resolveSibling(path.getFileName() + ".corrupt");
            int suffix = 0;
            while (Files.exists(backup)) {
                suffix += 1;
                backup = path.resolveSibling(path.getFileName() + ".corrupt." + suffix);
            }
            Files.copy(path, backup);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "词典损坏备份失败：" + path, e);
        }
    }

    public void save() throws IOException {
        // sort_keys=True：按码点排序
        Map<String, Object> sorted = new TreeMap<>(Json.CODE_POINT_ORDER);
        sorted.putAll(data);
        Json.writeFile(path, sorted);
    }

    /** 落盘但只记录失败：词典写盘失败不应打断界面操作。 */
    public void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "词典写盘失败：" + path, e);
        }
    }

    public String get(String orig) {
        return data.get(orig);
    }

    public void add(String orig, String trans) {
        if (orig != null && !orig.isEmpty() && trans != null && !trans.isEmpty()
                && !trans.equals(orig)) {
            data.put(orig, trans);
            invalidateSearchIndex();
        }
    }

    public void remove(String orig) {
        if (data.remove(orig) != null) {
            invalidateSearchIndex();
        }
    }

    public Map<String, String> entries() {
        return new LinkedHashMap<>(data);
    }

    /** 大小写不敏感子串匹配（键与译文两侧）。索引复用小写形式，避免每次全表分配。 */
    public Map<String, String> search(String keyword) {
        String q = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        ensureSearchIndex();
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < searchIndex.size(); i++) {
            if (lowerKeys[i].contains(q) || lowerValues[i].contains(q)) {
                Map.Entry<String, String> e = searchIndex.get(i);
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    public int size() {
        return data.size();
    }
}
