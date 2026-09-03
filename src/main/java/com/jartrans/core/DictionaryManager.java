package com.jartrans.core;

import com.jartrans.core.json.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 多词典管理：程序目录下 dictionaries/ 保存任意多个词典 JSON。
 *
 * 每个词典是一个独立的 `原字符串 -&gt; 译文` JSON 文件，由 index.json 记录
 * 名称与文件的对应关系以及当前激活的词典名。旧的单词典 `dictionary.json`
 * 会在首次启动时迁移为「默认词典」。
 */
public final class DictionaryManager {

    public static final String INDEX_FILE = "index.json";
    public static final String DEFAULT_NAME = "默认词典";
    public static final String LEGACY_FILE = "dictionary.json";

    private static final Pattern ILLEGAL = Pattern.compile("[<>:\"/\\\\|?*\\x00-\\x1f]");

    public record Item(String name, String file) {
    }

    public final Path baseDir;
    public final Path dir;
    public final Path indexPath;

    private List<Item> items = new ArrayList<>();
    private String active = "";
    private final Map<String, Dictionary> cache = new LinkedHashMap<>();

    public DictionaryManager(Path baseDir) throws IOException {
        this.baseDir = baseDir;
        this.dir = baseDir.resolve("dictionaries");
        this.indexPath = dir.resolve(INDEX_FILE);
        Files.createDirectories(dir);
        load();
    }

    // ---------- 载入 / 保存索引 ----------

    private void load() throws IOException {
        Map<String, Object> data = Json.object(Json.readFileQuiet(indexPath));
        List<Item> list = new ArrayList<>();
        String activeName = "";
        if (data != null) {
            Object rawItems = data.get("items");
            if (rawItems instanceof List<?> l) {
                for (Object o : l) {
                    Map<String, Object> it = Json.object(o);
                    if (it != null && str(it.get("name")).isEmpty() == false
                            && !str(it.get("file")).isEmpty()) {
                        list.add(new Item(str(it.get("name")), str(it.get("file"))));
                    }
                }
            }
            activeName = str(data.get("active"));
        }
        // 丢弃文件已不存在的记录
        list.removeIf(it -> !Files.isRegularFile(dir.resolve(it.file())));
        if (list.isEmpty()) {
            list = scanJsonFiles();
        }
        if (list.isEmpty()) {
            migrateLegacy();
            list = scanJsonFiles();
        }
        if (list.isEmpty()) {
            list.add(new Item(DEFAULT_NAME, DEFAULT_NAME + ".json"));
            Json.writeFile(dir.resolve(list.get(0).file()), new LinkedHashMap<>());
        }
        items = list;
        List<String> names = names();
        active = names.contains(activeName) ? activeName : names.get(0);
        saveIndex();
    }

    private List<Item> scanJsonFiles() throws IOException {
        List<Item> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            List<String> fnames = new ArrayList<>();
            files.forEach(p -> fnames.add(p.getFileName().toString()));
            java.util.Collections.sort(fnames);
            for (String fn : fnames) {
                if (fn.toLowerCase(Locale.ROOT).endsWith(".json") && !fn.equals(INDEX_FILE)) {
                    list.add(new Item(stripExt(fn), fn));
                }
            }
        }
        return list;
    }

    private void migrateLegacy() {
        Path legacy = baseDir.resolve(LEGACY_FILE);
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        Map<String, Object> data = Json.object(Json.readFileQuiet(legacy));
        if (data == null) {
            return;
        }
        Path target = dir.resolve(DEFAULT_NAME + ".json");
        if (!Files.exists(target)) {
            try {
                Json.writeFile(target, data);
            } catch (IOException ignored) {
                // 迁移失败不影响启动
            }
        }
    }

    public void saveIndex() throws IOException {
        Map<String, Object> idx = new LinkedHashMap<>();
        idx.put("active", active);
        List<Map<String, Object>> list = new ArrayList<>();
        for (Item it : items) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", it.name());
            m.put("file", it.file());
            list.add(m);
        }
        idx.put("items", list);
        Json.writeFile(indexPath, idx);
    }

    // ---------- 查询 ----------

    public List<String> names() {
        List<String> out = new ArrayList<>();
        for (Item it : items) {
            out.add(it.name());
        }
        return out;
    }

    public boolean exists(String name) {
        return names().contains(name);
    }

    public Path pathOf(String name) {
        for (Item it : items) {
            if (it.name().equals(name)) {
                return dir.resolve(it.file());
            }
        }
        return null;
    }

    public int count(String name) {
        Dictionary d = get(name);
        return d == null ? 0 : d.size();
    }

    /** 返回（缓存的）Dictionary 对象；名字不存在时返回当前激活的。 */
    public Dictionary get(String name) {
        if (!names().contains(name)) {
            name = active;
        }
        Dictionary cached = cache.get(name);
        if (cached != null) {
            return cached;
        }
        Path path = pathOf(name);
        if (path == null) {
            return null;
        }
        Dictionary d = new Dictionary(path);
        cache.put(name, d);
        return d;
    }

    public Dictionary activeDictionary() {
        return get(active);
    }

    public String activeName() {
        return active;
    }

    public String setActive(String name) throws IOException {
        if (names().contains(name) && !name.equals(active)) {
            active = name;
            saveIndex();
        }
        return active;
    }

    // ---------- 增删改 ----------

    private String uniqueFilename(String name) {
        String stem = safeStem(name);
        String candidate = stem + ".json";
        int index = 0;
        Set<String> taken = new LinkedHashSet<>();
        for (Item it : items) {
            taken.add(it.file().toLowerCase(Locale.ROOT));
        }
        while (taken.contains(candidate.toLowerCase(Locale.ROOT))
                || Files.exists(dir.resolve(candidate))) {
            index += 1;
            candidate = stem + "_" + index + ".json";
        }
        return candidate;
    }

    public String create(String name) throws IOException {
        name = name == null ? "" : name.trim();
        if (name.isEmpty()) {
            throw new IllegalArgumentException("词典名称不能为空。");
        }
        if (exists(name)) {
            throw new IllegalArgumentException("已存在名为「" + name + "」的词典。");
        }
        String filename = uniqueFilename(name);
        Json.writeFile(dir.resolve(filename), new LinkedHashMap<>());
        items.add(new Item(name, filename));
        saveIndex();
        cache.put(name, new Dictionary(dir.resolve(filename)));
        return name;
    }

    public String rename(String name, String newName) throws IOException {
        newName = newName == null ? "" : newName.trim();
        if (newName.isEmpty()) {
            throw new IllegalArgumentException("词典名称不能为空。");
        }
        if (!newName.equals(name) && exists(newName)) {
            throw new IllegalArgumentException("已存在名为「" + newName + "」的词典。");
        }
        boolean found = false;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.name().equals(name)) {
                items.set(i, new Item(newName, it.file()));
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalArgumentException("词典不存在。");
        }
        Dictionary moved = cache.remove(name);
        if (moved != null) {
            cache.put(newName, moved);
        }
        if (active.equals(name)) {
            active = newName;
        }
        saveIndex();
        return newName;
    }

    public void remove(String name) throws IOException {
        if (items.size() <= 1) {
            throw new IllegalArgumentException("至少需要保留一个词典。");
        }
        Item target = null;
        for (Item it : items) {
            if (it.name().equals(name)) {
                target = it;
                break;
            }
        }
        if (target == null) {
            throw new IllegalArgumentException("词典不存在。");
        }
        try {
            Files.deleteIfExists(dir.resolve(target.file()));
        } catch (IOException ignored) {
            // 删除失败则保留文件，但索引中仍移除
        }
        items.remove(target);
        cache.remove(name);
        if (active.equals(name)) {
            active = names().get(0);
        }
        saveIndex();
    }

    /** 从外部 JSON 导入。merge=true 时并入同名（或指定名）词典。 */
    public record ImportResult(String name, int count, boolean merged) {
    }

    public ImportResult importFile(Path src, String name, boolean merge) throws IOException {
        Map<String, Object> data = Json.object(Json.readFileQuiet(src));
        if (data == null) {
            throw new IllegalArgumentException("不是有效的词典 JSON（应为 原字符串->译文 对象）。");
        }
        String base = name != null && !name.isEmpty()
                ? name : stripExt(src.getFileName().toString());
        if (base.isEmpty()) {
            base = DEFAULT_NAME;
        }
        if (merge && exists(base)) {
            Dictionary dic = get(base);
            for (Map.Entry<String, Object> e : data.entrySet()) {
                dic.add(e.getKey(), str(e.getValue()));
            }
            dic.save();
            return new ImportResult(base, data.size(), true);
        }
        String newName = base;
        int index = 1;
        while (exists(newName)) {
            index += 1;
            newName = base + " (" + index + ")";
        }
        create(newName);
        Dictionary dic = get(newName);
        for (Map.Entry<String, Object> e : data.entrySet()) {
            dic.add(e.getKey(), str(e.getValue()));
        }
        dic.save();
        return new ImportResult(newName, data.size(), false);
    }

    public int exportFile(String name, Path dest) throws IOException {
        Dictionary dic = get(name);
        if (dic == null) {
            throw new IllegalArgumentException("词典不存在。");
        }
        dic.save();
        Map<String, Object> sorted = new TreeMapLike();
        sorted.putAll(dic.entries());
        Json.writeFile(dest, sorted);
        return dic.size();
    }

    /** 按码点排序的 LinkedHashMap 替代（TreeMap 已够用，直接用 TreeMap）。 */
    private static final class TreeMapLike extends java.util.TreeMap<String, Object> {
        TreeMapLike() {
            super(Json.CODE_POINT_ORDER);
        }
    }

    // ---------- 工具 ----------

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static String stripExt(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    static String safeStem(String name) {
        String stem = ILLEGAL.matcher(name == null ? "" : name).replaceAll("_").trim();
        stem = stripDotsAndSpaces(stem);
        if (stem.length() > 60) {
            stem = stem.substring(0, 60);
        }
        return stem.isEmpty() ? "dict" : stem;
    }

    /** Python 的 strip('. ')：去掉首尾的 '.' 和 ' '。 */
    private static String stripDotsAndSpaces(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && (s.charAt(start) == '.' || s.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (s.charAt(end - 1) == '.' || s.charAt(end - 1) == ' ')) {
            end--;
        }
        return s.substring(start, end);
    }
}
