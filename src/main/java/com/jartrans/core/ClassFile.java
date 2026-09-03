package com.jartrans.core;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * class 文件常量池解析与「追加式」重写。
 *
 * 核心思路（保证字节码不被破坏的关键）：
 * - 常量池原有条目一律原地保留，所有既有索引保持有效，因此常量池之后的
 *   字段/方法/属性字节可以原样拷贝，完全不需要重索引；
 * - Java 字符串字面量在 class 中是 CONSTANT_String -&gt; CONSTANT_Utf8 的两级引用。
 *   替换某字面量时不动原 Utf8 条目，只把对应 CONSTANT_String 条目的
 *   2 字节 string_index 改指向新条目；
 * - 新译文作为新的 CONSTANT_Utf8 条目追加到常量池末尾（索引 = 原最大索引 + 1），
 *   并把 constant_pool_count 加 1。追加条目只被 CONSTANT_String 的 u2 引用，
 *   不受 ldc 1 字节索引限制；
 * - 追加前先在池中查找内容完全相同的 Utf8 条目（按原始字节比较），有则复用；
 * - 孤儿 Utf8 条目（不再被引用）对 JVM 完全合法。
 *
 * 写回前做结构校验：重新解析生成结果，逐条比对原有条目、尾部字节，
 * 并用字符串多重集验证所有替换精确生效。
 */
public final class ClassFile {

    public static final long MAGIC = 0xCAFEBABEL;

    public static final int CONSTANT_Utf8 = 1;
    public static final int CONSTANT_Integer = 3;
    public static final int CONSTANT_Float = 4;
    public static final int CONSTANT_Long = 5;
    public static final int CONSTANT_Double = 6;
    public static final int CONSTANT_Class = 7;
    public static final int CONSTANT_String = 8;
    public static final int CONSTANT_Fieldref = 9;
    public static final int CONSTANT_Methodref = 10;
    public static final int CONSTANT_InterfaceMethodref = 11;
    public static final int CONSTANT_NameAndType = 12;
    public static final int CONSTANT_MethodHandle = 15;
    public static final int CONSTANT_MethodType = 16;
    public static final int CONSTANT_Dynamic = 17;
    public static final int CONSTANT_InvokeDynamic = 18;
    public static final int CONSTANT_Module = 19;
    public static final int CONSTANT_Package = 20;

    /** tag 字节之后的固定长度（CONSTANT_Utf8 为变长，单独处理）；未知 tag 返回 -1。 */
    private static int fixedSize(int tag) {
        return switch (tag) {
            case CONSTANT_Integer, CONSTANT_Float -> 4;
            case CONSTANT_Long, CONSTANT_Double -> 8;
            case CONSTANT_Class, CONSTANT_String -> 2;
            case CONSTANT_Fieldref, CONSTANT_Methodref, CONSTANT_InterfaceMethodref,
                 CONSTANT_NameAndType -> 4;
            case CONSTANT_MethodHandle -> 3;
            case CONSTANT_MethodType -> 2;
            case CONSTANT_Dynamic, CONSTANT_InvokeDynamic -> 4;
            case CONSTANT_Module, CONSTANT_Package -> 2;
            default -> -1;
        };
    }

    private static final int CP_MAX_COUNT = 0xFFFF; // constant_pool_count 是 u2，最大索引 0xFFFE

    public final byte[] data;     // 原始完整字节
    public final int minor;
    public final int major;
    public final Map<Integer, CpEntry> entries; // 常量池索引 -&gt; 条目（Long/Double 只占一个 key）
    public final int count;       // 原 constant_pool_count
    public final byte[] tail;     // 常量池之后的全部字节（原样保留）

    private ClassFile(byte[] data, int minor, int major,
                      Map<Integer, CpEntry> entries, int count, byte[] tail) {
        this.data = data;
        this.minor = minor;
        this.major = major;
        this.entries = entries;
        this.count = count;
        this.tail = tail;
    }

    // ---------- 解析 ----------

    public static ClassFile parse(byte[] data) throws ClassFileException {
        if (data.length < 10) {
            throw new ClassFileException("文件太小，不是有效的 class 文件");
        }
        if (u4(data, 0) != MAGIC) {
            throw new ClassFileException("magic 不正确（不是 class 文件，或已损坏）");
        }
        int minor = u2(data, 4);
        int major = u2(data, 6);
        int count = u2(data, 8);
        int pos = 10;
        int n = data.length;
        Map<Integer, CpEntry> entries = new LinkedHashMap<>();
        int index = 1;
        while (index < count) {
            if (pos >= n) {
                throw new ClassFileException("常量池数据不完整");
            }
            int tag = data[pos] & 0xFF;
            pos += 1;
            byte[] raw;
            if (tag == CONSTANT_Utf8) {
                if (pos + 2 > n) {
                    throw new ClassFileException("Utf8 条目长度前缀不完整");
                }
                int ln = u2(data, pos);
                pos += 2;
                if (pos + ln > n) {
                    throw new ClassFileException("Utf8 条目内容不完整");
                }
                raw = Arrays.copyOfRange(data, pos, pos + ln);
                pos += ln;
            } else if (fixedSize(tag) >= 0) {
                int size = fixedSize(tag);
                if (pos + size > n) {
                    throw new ClassFileException("标签 " + tag + " 的条目数据不完整");
                }
                raw = Arrays.copyOfRange(data, pos, pos + size);
                pos += size;
            } else {
                throw new ClassFileException("未知的常量池标签 " + tag + "（偏移 " + (pos - 1) + "）");
            }
            entries.put(index, new CpEntry(index, tag, raw));
            index += (tag == CONSTANT_Long || tag == CONSTANT_Double) ? 2 : 1;
        }
        return new ClassFile(Arrays.copyOf(data, data.length), minor, major,
                entries, count, Arrays.copyOfRange(data, pos, data.length));
    }

    // ---------- 提取 ----------

    /** 所有字符串字面量，按常量池索引顺序。损坏引用的 text 为 null。 */
    public List<Literal> literals() {
        List<Literal> result = new ArrayList<>();
        for (CpEntry entry : entries.values()) {
            if (entry.tag != CONSTANT_String) {
                continue;
            }
            int si = u2(entry.raw, 0);
            CpEntry target = entries.get(si);
            String text = (target != null && target.tag == CONSTANT_Utf8) ? target.text() : null;
            result.add(new Literal(entry.index, si, text));
        }
        return result;
    }

    /** 未被字符串字面量引用的 Utf8 文本（类名/签名等，仅供查看、禁止修改）。 */
    public List<String> internalTexts(Set<String> literalTexts) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> result = new ArrayList<>();
        for (CpEntry entry : entries.values()) {
            if (entry.tag != CONSTANT_Utf8) {
                continue;
            }
            String text = entry.text();
            if (literalTexts.contains(text) || seen.contains(text)) {
                continue;
            }
            seen.add(text);
            result.add(text);
        }
        return result;
    }

    // ---------- 重写 ----------

    /**
     * 按 {原字符串: 新字符串} 重写，返回新的 class 字节。
     *
     * 无有效修改时原样返回。内部会先做写回校验，失败抛 ClassFileException。
     */
    public byte[] rewrite(Map<String, String> translations) throws ClassFileException {
        Map<String, String> eff = effective(translations);
        if (eff.isEmpty()) {
            return data;
        }

        // 现有 Utf8 条目按原始字节建立复用表（字节比较，避免超长编码误判相同）
        Map<String, Integer> reuse = new LinkedHashMap<>();
        for (CpEntry entry : entries.values()) {
            if (entry.tag == CONSTANT_Utf8) {
                reuse.putIfAbsent(Bytes.key(entry.raw), entry.index);
            }
        }

        Map<String, Integer> appended = new LinkedHashMap<>(); // 新文本 -&gt; 追加索引
        int nextIndex = count;
        Map<Integer, Integer> stringTargets = new LinkedHashMap<>(); // CONSTANT_String 索引 -&gt; 新 Utf8 索引
        for (CpEntry entry : entries.values()) {
            if (entry.tag != CONSTANT_String) {
                continue;
            }
            int si = u2(entry.raw, 0);
            CpEntry target = entries.get(si);
            if (target == null || target.tag != CONSTANT_Utf8) {
                continue;
            }
            String text = target.text();
            if (!eff.containsKey(text)) {
                continue;
            }
            String newText = eff.get(text);
            byte[] raw = ModifiedUTF8.encode(newText);
            if (raw.length > 0xFFFF) {
                throw new ClassFileException(
                        "译文过长（Modified UTF-8 编码 " + raw.length + " 字节，超过 65535 上限）：" + clip(newText, 50));
            }
            Integer newIndex = reuse.get(Bytes.key(raw));
            if (newIndex == null) {
                newIndex = appended.get(newText);
                if (newIndex == null) {
                    newIndex = nextIndex;
                    nextIndex += 1;
                    appended.put(newText, newIndex);
                }
                reuse.put(Bytes.key(raw), newIndex);
            }
            if (newIndex != si) {
                stringTargets.put(entry.index, newIndex);
            }
        }

        if (nextIndex > CP_MAX_COUNT) {
            throw new ClassFileException("常量池条目数超过 65535，无法追加新条目");
        }

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                data.length + 128);
        out.write(data, 0, 8);                 // magic + minor + major
        out.write((nextIndex >>> 8) & 0xFF);   // 新 constant_pool_count
        out.write(nextIndex & 0xFF);
        for (int index = 1; index < count; index++) {
            CpEntry entry = entries.get(index);
            if (entry == null) {               // Long/Double 的第二槽位
                continue;
            }
            out.write(entry.tag);
            if (entry.tag == CONSTANT_Utf8) {
                // 解析时 raw 只保留内容，写回时补上 u2 长度前缀
                out.write((entry.raw.length >>> 8) & 0xFF);
                out.write(entry.raw.length & 0xFF);
                out.write(entry.raw, 0, entry.raw.length);
            } else if (entry.tag == CONSTANT_String && stringTargets.containsKey(entry.index)) {
                int ni = stringTargets.get(entry.index);
                out.write((ni >>> 8) & 0xFF);
                out.write(ni & 0xFF);
            } else {
                out.write(entry.raw, 0, entry.raw.length);
            }
        }
        for (Map.Entry<String, Integer> e : appended.entrySet()) {
            byte[] raw = ModifiedUTF8.encode(e.getKey());
            out.write(CONSTANT_Utf8);
            out.write((raw.length >>> 8) & 0xFF);
            out.write(raw.length & 0xFF);
            out.write(raw, 0, raw.length);
        }
        out.write(tail, 0, tail.length);

        byte[] newData = out.toByteArray();
        List<String> problems = verify(newData, translations);
        if (!problems.isEmpty()) {
            throw new ClassFileException("写回校验失败：" + String.join("；", problems));
        }
        return newData;
    }

    // ---------- 校验 ----------

    /** 校验重写结果的结构完整性，返回问题列表（空列表表示通过）。 */
    public List<String> verify(byte[] newData, Map<String, String> translations) {
        List<String> problems = new ArrayList<>();
        ClassFile cf2;
        try {
            cf2 = parse(newData);
        } catch (ClassFileException exc) {
            problems.add("重写后无法解析：" + exc.getMessage());
            return problems;
        }

        if (!Arrays.equals(Arrays.copyOf(newData, 8), Arrays.copyOf(data, 8))) {
            problems.add("文件头（magic/版本）不一致");
        }
        if (!Arrays.equals(cf2.tail, tail)) {
            problems.add("常量池之后的字节被意外改动");
        }

        for (int index = 1; index < count; index++) {
            CpEntry old = entries.get(index);
            if (old == null) {
                continue;
            }
            CpEntry neu = cf2.entries.get(index);
            if (neu == null) {
                problems.add("常量 #" + index + " 丢失");
                continue;
            }
            if (neu.tag != old.tag) {
                problems.add("常量 #" + index + " 类型改变");
                continue;
            }
            if (old.tag != CONSTANT_String && !Arrays.equals(neu.raw, old.raw)) {
                problems.add("常量 #" + index + " 内容被意外修改");
            }
        }

        // 字符串字面量多重集必须精确等于预期（替换生效且无副作用）
        Map<String, String> eff = effective(translations);
        List<String> expected = new ArrayList<>();
        for (Literal lit : literals()) {
            expected.add(eff.getOrDefault(lit.text, lit.text));
        }
        List<String> actual = new ArrayList<>();
        for (Literal lit : cf2.literals()) {
            actual.add(lit.text);
        }
        if (!multisetEquals(expected, actual)) {
            problems.add("字符串字面量集合与预期不一致");
        }
        return problems;
    }

    // ---------- 工具 ----------

    private static Map<String, String> effective(Map<String, String> translations) {
        Map<String, String> eff = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : translations.entrySet()) {
            String t = e.getValue();
            if (t != null && !t.isEmpty() && !t.equals(e.getKey())) {
                eff.put(e.getKey(), t);
            }
        }
        return eff;
    }

    private static boolean multisetEquals(List<String> a, List<String> b) {
        if (a.size() != b.size()) {
            return false;
        }
        Map<String, Integer> ca = new LinkedHashMap<>();
        for (String s : a) {
            ca.merge(s, 1, Integer::sum);
        }
        for (String s : b) {
            Integer c = ca.get(s);
            if (c == null || c == 0) {
                return false;
            }
            ca.put(s, c - 1);
        }
        return true;
    }

    private static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    public static int u2(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    public static long u4(byte[] b, int off) {
        return (((long) (b[off] & 0xFF)) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 以原始字节为 key（避免 String 相等但编码不同造成的误判）。 */
    static final class Bytes {
        private Bytes() {
        }

        static String key(byte[] raw) {
            return new String(raw, StandardCharsets.ISO_8859_1);
        }
    }
}
