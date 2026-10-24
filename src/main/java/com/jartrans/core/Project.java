package com.jartrans.core;

import com.jartrans.core.io.DeferredSaver;
import com.jartrans.core.jar.JarPacker;
import com.jartrans.core.jar.JarReader;
import com.jartrans.core.json.Json;
import com.jartrans.core.log.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
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

    private static final System.Logger LOG = Log.get(Project.class);

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

    private ProjectArchive archive = ProjectArchive.empty();
    private final TranslationStore translations = new TranslationStore();
    private List<LangPack.MissingEntry> lastMissing = new ArrayList<>();
    private final Map<String, String> classStatus = new LinkedHashMap<>();
    /** 「不翻译」的原字符串集合：这些文本不写入导出的 jar、不套用词典/语言包译文。 */
    private final Set<String> skipTexts = new LinkedHashSet<>();
    private String sha256;
    /** 进度文件路径在构造时绑定（延迟写盘不得跨测试目录串写）。 */
    private final Path progressFile = AppDirs.progressFile();
    /** 进度写盘防抖：连续标记/跳过的读改写合并为一次，flushProgress 强制落盘。 */
    private final DeferredSaver progressSaver =
            new DeferredSaver("progress", SAVE_DELAY_MILLIS, this::writeProgressQuietly);

    /** 与 Settings 的写盘延迟一致。 */
    private static final long SAVE_DELAY_MILLIS = 300;

    public Project() {
        this(null, null);
    }

    /** 单词典模式（与 Python 版 selftest 场景一致）。 */
    public Project(Path dictionaryPath) {
        this(null, dictionaryPath);
    }

    /** 多词典模式。 */
    public Project(DictionaryManager dicts) {
        this(dicts, null);
    }

    private Project(DictionaryManager dicts, Path dictionaryPath) {
        if (dicts != null) {
            this.dicts = dicts;
            this.dictionary = dicts.activeDictionary();
        } else if (dictionaryPath != null) {
            this.dicts = null;
            this.dictionary = new Dictionary(dictionaryPath);
        } else {
            DictionaryManager manager;
            try {
                manager = new DictionaryManager(AppDirs.baseDir());
            } catch (IOException e) {
                throw new RuntimeException("初始化词典目录失败：" + e.getMessage(), e);
            }
            this.dicts = manager;
            this.dictionary = manager.activeDictionary();
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

    public void openJar(Path path) throws IOException, JarReader.JarFileException {
        ProjectArchive loaded = ProjectArchive.load(path);
        archive = loaded;
        sha256 = null;
        translations.clear();
        lastMissing = new ArrayList<>();
        skipTexts.clear();
        loadProgress();
    }

    public boolean hasJar() {
        return archive.reader() != null;
    }

    public JarReader jar() {
        return archive.reader();
    }

    public String jarName() {
        return hasJar() ? jar().name() : "";
    }

    public String jarSha256() throws IOException {
        if (sha256 == null) {
            sha256 = hasJar() ? LangPack.computeSha256(jarPath()) : "";
        }
        return sha256;
    }

    public Path jarPath() {
        if (!hasJar()) {
            throw new IllegalStateException("尚未打开 jar 文件");
        }
        return jar().path();
    }

    public List<String> classOrder() {
        return archive.order();
    }

    public Map<String, ClassFile> classes() {
        return archive.classes();
    }

    public List<TextCount> texts(String cls) {
        return archive.texts(cls);
    }

    public Set<String> textSet(String cls) {
        return archive.textSet(cls);
    }

    public List<FailedClass> failed() {
        return archive.failures();
    }

    public List<LangPack.MissingEntry> lastMissing() {
        return Collections.unmodifiableList(lastMissing);
    }

    public Map<String, String> classStatus() {
        return Collections.unmodifiableMap(classStatus);
    }

    public List<String> methodNames(String cls, String orig) {
        return archive.methodNames(cls, orig);
    }

    // ---------- 不翻译名单 ----------

    /** 当前被标记为「不翻译」的原字符串集合（跨类生效）。 */
    public Set<String> skipTexts() {
        return Collections.unmodifiableSet(skipTexts);
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
        // effective() 返回的是共享只读快照，过滤前必须复制
        Map<String, String> eff = new LinkedHashMap<>(effective(cls));
        eff.keySet().removeIf(skipTexts::contains);
        return eff;
    }

    /** 该类是否被标记为「已忽略 = 整类不翻译」。 */
    public boolean isIgnored(String cls) {
        return "ignore".equals(classStatus.get(cls));
    }

    // ---------- 编辑 ----------

    /** 该类当前生效的译文表（用户编辑覆盖自动填充，空译文视为未翻译）。只读快照，调用方不得修改。 */
    public Map<String, String> effective(String cls) {
        return translations.effective(cls);
    }

    public Status status(String cls, String orig) {
        return translations.status(cls, orig);
    }

    public void setTranslation(String cls, String orig, String trans) {
        setTranslation(cls, orig, trans, false);
    }

    public void setTranslation(String cls, String orig, String trans, boolean autoFill) {
        translations.set(cls, orig, trans, autoFill);
    }

    /** 批量替换的目标条目（类 + 原字符串）。 */
    public record ReplaceTarget(String cls, String orig) {
    }

    /** 一次实际发生的替换变更（撤销时需要原值）。 */
    public record ReplaceChange(String cls, String orig, String before) {
    }

    /**
     * 批量替换译文（类内「全部替换」与全局搜索「替换选中/全部替换」的单一实现）。
     * 统一规则：跳过「不翻译」条目与译文已等于目标的条目；其余全部写入目标译文。
     * 返回实际发生的变更（按输入顺序），供界面合并为单条撤销记录。
     */
    public List<ReplaceChange> replaceTranslations(List<ReplaceTarget> targets, String replacement) {
        List<ReplaceChange> changes = new ArrayList<>();
        for (ReplaceTarget t : targets) {
            if (isSkipped(t.orig())) {
                continue;
            }
            String before = effective(t.cls()).getOrDefault(t.orig(), "");
            if (before.equals(replacement)) {
                continue;
            }
            setTranslation(t.cls(), t.orig(), replacement);
            changes.add(new ReplaceChange(t.cls(), t.orig(), before));
        }
        return changes;
    }

    /** 把词典命中的未翻译字符串批量填充为"自动填充"。返回填充条数。 */
    public int fillFromDictionary() {
        return fillFromDictionary(null);
    }

    public int fillFromDictionary(List<String> onlyClasses) {
        int count = 0;
        Iterable<String> scope = onlyClasses != null ? onlyClasses : classOrder();
        for (String cls : scope) {
            if (isIgnored(cls)) {
                continue; // 已忽略 = 整类不翻译
            }
            for (TextCount tc : texts(cls)) {
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
        LangPack.MatchResult result = LangPack.matchPack(pack, archive.textSets());
        for (Map.Entry<String, Map<String, String>> e : result.applied().entrySet()) {
            if (isIgnored(e.getKey())) {
                continue;
            }
            for (Map.Entry<String, String> p : e.getValue().entrySet()) {
                if (skipTexts.contains(p.getKey())) {
                    continue;
                }
                translations.applyAutomatic(e.getKey(), p.getKey(), p.getValue());
            }
        }
        lastMissing = result.missing();
        boolean changed = applyPackClassStatus(pack);
        changed |= applyPackSkipTexts(pack);
        if (changed) {
            saveProgress();
        }
        long total = 0;
        for (Map<String, String> pairs : result.applied().values()) {
            total += pairs.size();
        }
        return total;
    }

    /** 应用语言包里的类状态手动标记（只接受仍存在的类与合法状态），返回是否有改动。 */
    private boolean applyPackClassStatus(Map<String, Object> pack) {
        Map<String, Object> status = Json.object(pack.get("class_status"));
        if (status == null || status.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Object> e : status.entrySet()) {
            String st = String.valueOf(e.getValue());
            if (MANUAL_STATES.contains(st) && classes().containsKey(e.getKey())) {
                classStatus.put(e.getKey(), st);
                changed = true;
            }
        }
        return changed;
    }

    /** 应用语言包里声明的「不翻译」原字符串，返回是否有改动。 */
    private boolean applyPackSkipTexts(Map<String, Object> pack) {
        if (!(pack.get("skip_texts") instanceof List<?> list)) {
            return false;
        }
        boolean changed = false;
        for (Object o : list) {
            String s = String.valueOf(o);
            if (!s.isEmpty() && skipTexts.add(s)) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 参与导出的内容：{类路径: 生效译文}。
     * 已忽略的类整类剔除；「不翻译」文本已由 effectiveForExport 剔除；无生效译文的类不列出。
     */
    private Map<String, Map<String, String>> translationsForExport() {
        Map<String, Map<String, String>> entries = new LinkedHashMap<>();
        for (String cls : classOrder()) {
            if (isIgnored(cls)) {
                continue; // 已忽略的类整类不导出译文
            }
            Map<String, String> eff = effectiveForExport(cls);
            if (!eff.isEmpty()) {
                entries.put(cls, eff);
            }
        }
        return entries;
    }

    public void exportPack(Path path, String author) throws IOException {
        Map<String, Object> pack = LangPack.buildPack(jarName(), jarSha256(),
                author, translationsForExport(), classStatus);
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
        if (!hasJar()) {
            throw new IllegalStateException("尚未打开 jar 文件");
        }
        Map<String, byte[]> modified = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, String>> e : translationsForExport().entrySet()) {
            try {
                modified.put(e.getKey(), classes().get(e.getKey()).rewrite(e.getValue()));
            } catch (ClassFileException exc) {
                throw new IOException("重写 " + e.getKey() + " 失败：" + exc.getMessage(), exc);
            }
        }
        JarPacker.save(jar(), outPath, modified, stripSignature);
        return modified.size();
    }

    // ---------- 统计 / 搜索 ----------

    public long[] stats() {
        long total = archive.textCount();
        long done = 0;
        for (String c : classOrder()) {
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
        for (String cls : classOrder()) {
            out.merge(classState(cls), 1, Integer::sum);
        }
        return out;
    }

    public int untranslatedCount(String cls) {
        return texts(cls).size() - effective(cls).size();
    }

    public List<String> internalTexts(String cls) {
        return classes().get(cls).internalTexts(textSet(cls));
    }

    // ---------- 类状态 ----------

    /** 该类是否含有可翻译的字符串字面量。 */
    public boolean hasTranslatable(String cls) {
        return !texts(cls).isEmpty();
    }

    /** 按翻译进度自动判定的状态。 */
    public String autoClassState(String cls) {
        List<TextCount> list = texts(cls);
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
        if (!hasJar()) {
            return null;
        }
        long size;
        try {
            size = Files.size(jarPath());
        } catch (IOException e) {
            size = 0;
        }
        return jarName() + "|" + size + "|" + classOrder().size();
    }

    private void loadProgress() {
        ProgressStore.Progress progress = new ProgressStore(progressFile).load(progressKey());
        classStatus.clear();
        classStatus.putAll(progress.classStatus());
        skipTexts.clear();
        skipTexts.addAll(progress.skippedTexts());
    }

    /** 标记进度有变更，延迟合并落盘（见 progressSaver）。 */
    private void saveProgress() {
        progressSaver.requestSave();
    }

    /** 立即写出挂起的进度变更（切档前/退出前调用）。 */
    public void flushProgress() {
        progressSaver.flush();
    }

    private void writeProgressQuietly() {
        try {
            // 快照后交给落盘线程，避免与界面线程的标记操作并发遍历
            Map<String, String> statuses = new LinkedHashMap<>(classStatus);
            Set<String> skipped = new LinkedHashSet<>(skipTexts);
            new ProgressStore(progressFile).save(progressKey(), statuses, skipped);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "翻译进度写盘失败", e);
        }
    }

    /** 跨所有类搜索。scope: 'orig' | 'trans' | 'both'。 */
    public record SearchResult(String cls, String orig, String trans, Status status) {
    }

    public List<SearchResult> search(String keyword, String scope) {
        String q = keyword == null ? "" : keyword.toLowerCase(java.util.Locale.ROOT);
        String normalizedScope = switch (scope) {
            case "orig", "trans", "both" -> scope;
            case null -> "both";
            default -> "both";
        };
        List<SearchResult> results = new ArrayList<>();
        for (String cls : classOrder()) {
            Map<String, String> eff = effective(cls);
            for (TextCount tc : texts(cls)) {
                String trans = eff.getOrDefault(tc.text(), "");
                boolean origHit = ("orig".equals(normalizedScope) || "both".equals(normalizedScope))
                        && tc.text().toLowerCase(java.util.Locale.ROOT).contains(q);
                boolean transHit = ("trans".equals(normalizedScope) || "both".equals(normalizedScope))
                        && trans.toLowerCase(java.util.Locale.ROOT).contains(q);
                if (origHit || transHit) {
                    results.add(new SearchResult(cls, tc.text(), trans, status(cls, tc.text())));
                }
            }
        }
        return results;
    }
}
