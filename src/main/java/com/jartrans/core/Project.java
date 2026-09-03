package com.jartrans.core;

import com.jartrans.core.jar.JarPacker;
import com.jartrans.core.jar.JarReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 会话状态：当前 jar + 解析结果 + 编辑缓存 + 语言包/词典联动。
 *
 * 翻译数据分两层：
 * - edits: 用户手动填写的译文（优先级高）；
 * - auto:  语言包导入 / 词典批量填充的译文（标记为"自动填充"）。
 * 合并时用户编辑覆盖自动填充；空译文视为未翻译。
 *
 * 类的状态（class_state）同样是两层：默认按翻译进度自动判定，
 * 用户在界面上手动标记后以手动值为准（存于 class_status）。
 */
public final class Project {

    /** 类的状态：无字符串 / 未开始 / 翻译中 / 已完成 / 已忽略。 */
    public static final List<String> CLASS_STATES =
            List.of("empty", "todo", "doing", "done", "ignore");
    /** 可手动标记的状态。 */
    public static final List<String> MANUAL_STATES =
            List.of("todo", "doing", "done", "ignore");

    /** 一条原文 + 出现次数。 */
    public record TextCount(String text, int count) {
    }

    /** 一个字符串的编辑状态。 */
    public enum Status {
        UNTRANSLATED("untranslated"), TRANSLATED("translated"), AUTO("auto");

        public final String key;

        Status(String key) {
            this.key = key;
        }
    }

    /** 解析失败的类：类路径 + 原因。 */
    public record FailedClass(String name, String reason) {
    }

    private final DictionaryManager dicts;   // 可为 null（单词典模式）
    private Dictionary dictionary;
    private final String dictionaryLabelFallback;

    private JarReader jar;
    private final Map<String, ClassFile> classes = new LinkedHashMap<>();
    private final List<String> classOrder = new ArrayList<>();
    private final Map<String, List<TextCount>> texts = new LinkedHashMap<>();
    private final Map<String, Set<String>> textSets = new LinkedHashMap<>();
    private final List<FailedClass> failed = new ArrayList<>();
    private final Map<String, Map<String, String>> edits = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> auto = new LinkedHashMap<>();
    private List<LangPack.MissingEntry> lastMissing = new ArrayList<>();
    private final Map<String, Map<String, List<String>>> methodUsage = new LinkedHashMap<>();
    private final Map<String, String> classStatus = new LinkedHashMap<>();
    /** 「不翻译」的原字符串集合：这些文本不写入导出的 jar、不套用词典/语言包译文。 */
    private final Set<String> skipTexts = new LinkedHashSet<>();
    private String sha256;

    public Project() {
        this(null, null, null);
    }

    /** 单词典模式（与 Python 版 selftest 场景一致）。 */
    public Project(Path dictionaryPath) {
        this(null, dictionaryPath, null);
    }

    /** 多词典模式。 */
    public Project(DictionaryManager dicts) {
        this(dicts, null, null);
    }

    private Project(DictionaryManager dicts, Path dictionaryPath, String labelFallback) {
        if (dicts != null) {
            this.dicts = dicts;
            this.dictionary = dicts.activeDictionary();
            this.dictionaryLabelFallback = null;
        } else if (dictionaryPath != null) {
            this.dicts = null;
            this.dictionary = new Dictionary(dictionaryPath);
            this.dictionaryLabelFallback = null;
        } else {
            DictionaryManager manager;
            try {
                manager = new DictionaryManager(AppDirs.baseDir());
            } catch (IOException e) {
                throw new RuntimeException("初始化词典目录失败：" + e.getMessage(), e);
            }
            this.dicts = manager;
            this.dictionary = manager.activeDictionary();
            this.dictionaryLabelFallback = null;
        }
    }

    public DictionaryManager dicts() {
        return dicts;
    }

    public Dictionary dictionary() {
        return dictionary;
    }

    public String dictionaryLabel() {
        if (dicts != null) {
            return dicts.activeName();
        }
        String p = dictionary.path().getFileName().toString();
        int dot = p.lastIndexOf('.');
        return dot > 0 ? p.substring(0, dot) : p;
    }

    /** 切换到另一个词典（仅多词典模式下有效）。返回新的 Dictionary。 */
    public Dictionary useDictionary(String name) throws IOException {
        if (dicts == null) {
            return dictionary;
        }
        dicts.setActive(name);
        dictionary = dicts.get(name);
        return dictionary;
    }

    // ---------- 打开 ----------

    public void openJar(Path path) throws IOException {
        JarReader reader;
        try {
            reader = new JarReader(path);
        } catch (JarReader.JarFileException e) {
            throw new IOException(e.getMessage(), e);
        }
        classes.clear();
        texts.clear();
        textSets.clear();
        classOrder.clear();
        failed.clear();
        methodUsage.clear();

        Map<String, Map<String, Integer>> counts = new LinkedHashMap<>();
        Map<String, List<String>> seqs = new LinkedHashMap<>();
        for (String name : reader.classNames()) {
            try {
                ClassFile cf = ClassFile.parse(reader.readClass(name));
                Map<String, Integer> cnt = new LinkedHashMap<>();
                List<String> seq = new ArrayList<>();
                for (Literal lit : cf.literals()) {
                    if (lit.text == null) {
                        continue;
                    }
                    if (cnt.containsKey(lit.text)) {
                        cnt.merge(lit.text, 1, Integer::sum);
                    } else {
                        cnt.put(lit.text, 1);
                        seq.add(lit.text);
                    }
                }
                classes.put(name, cf);
                List<TextCount> list = new ArrayList<>();
                for (String t : seq) {
                    list.add(new TextCount(t, cnt.get(t)));
                }
                texts.put(name, list);
                textSets.put(name, cnt.keySet());
                classOrder.add(name);
                try {
                    methodUsage.put(name, Bytecode.stringUsage(cf));
                } catch (Exception e) {
                    methodUsage.put(name, new LinkedHashMap<>());
                }
            } catch (ClassFileException exc) {
                failed.add(new FailedClass(name, exc.getMessage()));
            }
        }
        jar = reader;
        sha256 = null;
        edits.clear();
        auto.clear();
        lastMissing = new ArrayList<>();
        skipTexts.clear();
        loadProgress();
    }

    public boolean hasJar() {
        return jar != null;
    }

    public JarReader jar() {
        return jar;
    }

    public String jarName() {
        return jar != null ? jar.name() : "";
    }

    public String jarSha256() throws IOException {
        if (sha256 == null) {
            sha256 = jar != null ? LangPack.computeSha256(jar.path()) : "";
        }
        return sha256;
    }

    public Path jarPath() {
        return jar.path();
    }

    public List<String> classOrder() {
        return classOrder;
    }

    public Map<String, ClassFile> classes() {
        return classes;
    }

    public List<TextCount> texts(String cls) {
        return texts.getOrDefault(cls, List.of());
    }

    public Set<String> textSet(String cls) {
        return textSets.getOrDefault(cls, Set.of());
    }

    public List<FailedClass> failed() {
        return failed;
    }

    public List<LangPack.MissingEntry> lastMissing() {
        return lastMissing;
    }

    public Map<String, String> classStatus() {
        return classStatus;
    }

    public List<String> methodNames(String cls, String orig) {
        List<String> usage = methodUsage.getOrDefault(cls, Map.of()).get(orig);
        return usage == null ? List.of() : usage;
    }

    // ---------- 不翻译名单 ----------

    /** 当前被标记为「不翻译」的原字符串集合（跨类生效）。 */
    public Set<String> skipTexts() {
        return skipTexts;
    }

    public boolean isSkipped(String orig) {
        return skipTexts.contains(orig);
    }

    /** 标记某条原字符串为「不翻译」（skip=true）或恢复翻译（skip=false）。 */
    public void setTextSkipped(String orig, boolean skip) throws IOException {
        boolean changed = skip ? skipTexts.add(orig) : skipTexts.remove(orig);
        if (changed) {
            saveProgress();
        }
    }

    /** 按「不翻译」名单过滤后的生效译文表（这些文本在导出/填充/导入时不参与）。 */
    public Map<String, String> effectiveForExport(String cls) {
        Map<String, String> eff = effective(cls);
        eff.keySet().removeIf(skipTexts::contains);
        return eff;
    }

    /** 该类是否被标记为「已忽略 = 整类不翻译」。 */
    public boolean isIgnored(String cls) {
        return "ignore".equals(classStatus.get(cls));
    }

    // ---------- 编辑 ----------

    /** 该类当前生效的译文表（用户编辑覆盖自动填充，空译文视为未翻译）。 */
    public Map<String, String> effective(String cls) {
        Map<String, String> merged = new LinkedHashMap<>();
        merged.putAll(auto.getOrDefault(cls, Map.of()));
        merged.putAll(edits.getOrDefault(cls, Map.of()));
        merged.values().removeIf(v -> v == null || v.isEmpty());
        return merged;
    }

    public Status status(String cls, String orig) {
        if (edits.getOrDefault(cls, Map.of()).containsKey(orig)) {
            return Status.TRANSLATED;
        }
        if (auto.getOrDefault(cls, Map.of()).containsKey(orig)) {
            return Status.AUTO;
        }
        return Status.UNTRANSLATED;
    }

    public void setTranslation(String cls, String orig, String trans) {
        setTranslation(cls, orig, trans, false);
    }

    public void setTranslation(String cls, String orig, String trans, boolean autoFill) {
        if (trans == null || trans.isEmpty() || trans.equals(orig)) {
            Map<String, String> e = edits.get(cls);
            if (e != null) {
                e.remove(orig);
            }
            Map<String, String> a = auto.get(cls);
            if (a != null) {
                a.remove(orig);
            }
            return;
        }
        Map<String, Map<String, String>> bucket = autoFill ? auto : edits;
        Map<String, Map<String, String>> other = autoFill ? edits : auto;
        bucket.computeIfAbsent(cls, k -> new LinkedHashMap<>()).put(orig, trans);
        Map<String, String> om = other.get(cls);
        if (om != null) {
            om.remove(orig);
        }
    }

    /** 把词典命中的未翻译字符串批量填充为"自动填充"。返回填充条数。 */
    public int fillFromDictionary() {
        return fillFromDictionary(null);
    }

    public int fillFromDictionary(List<String> onlyClasses) {
        int count = 0;
        Iterable<String> scope = onlyClasses != null ? onlyClasses : classOrder;
        for (String cls : scope) {
            if (isIgnored(cls)) {
                continue; // 已忽略 = 整类不翻译
            }
            for (TextCount tc : texts.getOrDefault(cls, List.of())) {
                if (skipTexts.contains(tc.text()) || status(cls, tc.text()) != Status.UNTRANSLATED) {
                    continue;
                }
                String trans = dictionary.get(tc.text());
                if (trans != null && !trans.isEmpty() && !trans.equals(tc.text())) {
                    setTranslation(cls, tc.text(), trans, true);
                    count += 1;
                }
            }
        }
        return count;
    }

    // ---------- 语言包 ----------

    /** 应用语言包，不覆盖用户已手动编辑的条目。返回 (成功条数, 失效条目)。
        语言包内 skip_texts 名单与 class_status=ignore 的类会一起被尊重（不套用译文）。 */
    public long applyLanguagePack(Map<String, Object> pack) {
        LangPack.MatchResult result = LangPack.matchPack(pack, textSets);
        for (Map.Entry<String, Map<String, String>> e : result.applied().entrySet()) {
            if (isIgnored(e.getKey())) {
                continue;
            }
            Map<String, String> user = edits.get(e.getKey());
            for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                if (skipTexts.contains(p.getKey())) {
                    continue;
                }
                if (user == null || !user.containsKey(p.getKey())) {
                    auto.computeIfAbsent(e.getKey(), k -> new LinkedHashMap<>())
                            .put(p.getKey(), p.getValue());
                }
            }
        }
        lastMissing = result.missing();
        Object statusObj = pack.get("class_status");
        Map<String, Object> status = com.jartrans.core.json.Json.object(statusObj);
        boolean changed = false;
        if (status != null && !status.isEmpty()) {
            for (Map.Entry<String, Object> e : status.entrySet()) {
                String st = String.valueOf(e.getValue());
                if (MANUAL_STATES.contains(st) && textSets.containsKey(e.getKey())) {
                    classStatus.put(e.getKey(), st);
                    changed = true;
                }
            }
        }
        // 语言包里声明的「不翻译」原字符串 → 记入本工程名单
        Object skips = pack.get("skip_texts");
        if (skips instanceof List<?> list) {
            for (Object o : list) {
                String s = String.valueOf(o);
                if (!s.isEmpty() && skipTexts.add(s)) {
                    changed = true;
                }
            }
        }
        if (changed) {
            saveProgress();
        }
        long total = 0;
        for (Map<String, String> pairs : result.applied().values()) {
            total += pairs.size();
        }
        return total;
    }

    public void exportPack(Path path, String language, String author) throws IOException {
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        for (String cls : classOrder) {
            if (isIgnored(cls)) {
                continue; // 已忽略的类整类不导出译文
            }
            Map<String, String> eff = effectiveForExport(cls);
            if (!eff.isEmpty()) {
                entries.put(cls, eff);
            }
        }
        Map<String, Object> pack = LangPack.buildPack(jarName(), jarSha256(), language,
                author, entries, classStatus);
        if (!skipTexts.isEmpty()) {
            List<String> sorted = new ArrayList<>(skipTexts);
            sorted.sort(String::compareTo);
            pack.put("skip_texts", sorted);
        }
        LangPack.writePack(path, pack);
    }

    // ---------- 导出 jar ----------

    /** 写回全部已翻译的类并导出（已忽略类与「不翻译」文本不写入）。rewrite 内部会做写回校验。 */
    public int exportJar(Path outPath, boolean stripSignature) throws IOException {
        Map<String, byte[]> modified = new LinkedHashMap<>();
        for (String cls : classOrder) {
            if (isIgnored(cls)) {
                continue; // 已忽略 = 整类保留原文
            }
            Map<String, String> eff = effectiveForExport(cls);
            if (eff.isEmpty()) {
                continue;
            }
            try {
                modified.put(cls, classes.get(cls).rewrite(eff));
            } catch (ClassFileException e) {
                throw new IOException("重写 " + cls + " 失败：" + e.getMessage(), e);
            }
        }
        JarPacker.save(jar, outPath, modified, stripSignature);
        return modified.size();
    }

    // ---------- 统计 / 搜索 ----------

    public long[] stats() {
        long total = 0;
        for (List<TextCount> list : texts.values()) {
            total += list.size();
        }
        long done = 0;
        for (String c : classOrder) {
            done += effective(c).size();
        }
        return new long[]{total, done};
    }

    /** 返回各状态的类数量。 */
    public Map<String, Integer> classStats() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String st : CLASS_STATES) {
            out.put(st, 0);
        }
        for (String cls : classOrder) {
            out.merge(classState(cls), 1, Integer::sum);
        }
        return out;
    }

    public int untranslatedCount(String cls) {
        return texts.getOrDefault(cls, List.of()).size() - effective(cls).size();
    }

    public List<String> internalTexts(String cls) {
        return classes.get(cls).internalTexts(textSets.get(cls));
    }

    // ---------- 类状态 ----------

    /** 该类是否含有可翻译的字符串字面量。 */
    public boolean hasTranslatable(String cls) {
        return !texts.getOrDefault(cls, List.of()).isEmpty();
    }

    /** 按翻译进度自动判定的状态。 */
    public String autoClassState(String cls) {
        List<TextCount> list = texts.getOrDefault(cls, List.of());
        if (list.isEmpty()) {
            return "empty";
        }
        int done = effective(cls).size();
        if (done == 0) {
            return "todo";
        }
        return done >= list.size() ? "done" : "doing";
    }

    public String classState(String cls) {
        String manual = classStatus.get(cls);
        if (manual != null && MANUAL_STATES.contains(manual)) {
            return manual;
        }
        return autoClassState(cls);
    }

    public boolean isManualState(String cls) {
        String manual = classStatus.get(cls);
        return manual != null && MANUAL_STATES.contains(manual);
    }

    /** state 为 null/"auto" 时恢复自动判定。 */
    public void setClassStatus(String cls, String state) throws IOException {
        setClassStatus(cls, state, true);
    }

    public void setClassStatus(String cls, String state, boolean save) throws IOException {
        if (state == null || state.isEmpty() || state.equals("auto")) {
            if (classStatus.remove(cls) == null) {
                return;
            }
        } else {
            if (!MANUAL_STATES.contains(state)) {
                throw new IllegalArgumentException("未知的类状态：" + state);
            }
            classStatus.put(cls, state);
        }
        if (save) {
            saveProgress();
        }
    }

    public void setClassStatusBulk(List<String> classNames, String state) throws IOException {
        for (String cls : classNames) {
            setClassStatus(cls, state, false);
        }
        saveProgress();
    }

    // ---------- 进度持久化（按 jar 名 + 体积 + 类数量识别同一个 jar） ----------

    private String progressKey() {
        if (jar == null) {
            return null;
        }
        long size;
        try {
            size = Files.size(jar.path());
        } catch (IOException e) {
            size = 0;
        }
        return jarName() + "|" + size + "|" + classOrder.size();
    }

    private Map<String, Object> readProgressFile() {
        Path file = AppDirs.progressFile();
        if (!Files.exists(file)) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> data = com.jartrans.core.json.Json.object(
                com.jartrans.core.json.Json.readFileQuiet(file));
        return data != null ? data : new LinkedHashMap<>();
    }

    private void loadProgress() {
        classStatus.clear();
        skipTexts.clear();
        String key = progressKey();
        if (key == null) {
            return;
        }
        Object got = readProgressFile().get(key);
        Map<String, Object> map = com.jartrans.core.json.Json.object(got);
        if (map == null) {
            return;
        }
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String v = String.valueOf(e.getValue());
            if (MANUAL_STATES.contains(v)) {
                classStatus.put(e.getKey(), v);
            } else if ("__skip_texts".equals(e.getKey()) && e.getValue() instanceof List<?> list) {
                for (Object o : list) {
                    skipTexts.add(String.valueOf(o));
                }
            }
        }
    }

    private void saveProgress() {
        String key = progressKey();
        if (key == null) {
            return;
        }
        Map<String, Object> data = readProgressFile();
        if (!classStatus.isEmpty() || !skipTexts.isEmpty()) {
            Map<String, Object> block = new LinkedHashMap<>(classStatus);
            if (!skipTexts.isEmpty()) {
                List<String> sorted = new ArrayList<>(skipTexts);
                sorted.sort(String::compareTo);
                block.put("__skip_texts", sorted);
            }
            data.put(key, block);
        } else {
            data.remove(key);
        }
        while (data.size() > 50) { // 只保留最近 50 个 jar
            String first = data.keySet().iterator().next();
            data.remove(first);
        }
        Path file = AppDirs.progressFile();
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            com.jartrans.core.json.Json.writeFile(tmp, data);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // 进度写入失败不影响主流程
        }
    }

    /** 跨所有类搜索。scope: 'orig' | 'trans' | 'both'。 */
    public record SearchResult(String cls, String orig, String trans, Status status) {
    }

    public List<SearchResult> search(String keyword, String scope) {
        String q = keyword.toLowerCase();
        List<SearchResult> results = new ArrayList<>();
        for (String cls : classOrder) {
            Map<String, String> eff = effective(cls);
            for (TextCount tc : texts.get(cls)) {
                String trans = eff.getOrDefault(tc.text(), "");
                boolean hit = ("orig".equals(scope) || "both".equals(scope))
                        && tc.text().toLowerCase().contains(q)
                        || ("trans".equals(scope) || "both".equals(scope))
                        && trans.toLowerCase().contains(q);
                if (hit) {
                    results.add(new SearchResult(cls, tc.text(), trans, status(cls, tc.text())));
                }
            }
        }
        return results;
    }
}
