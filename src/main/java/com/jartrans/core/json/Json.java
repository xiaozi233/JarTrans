package com.jartrans.core.json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析/写出（仅用 JDK 标准库）。
 *
 * 输出格式对齐 Python json.dump(..., ensure_ascii=False, indent=2)：
 * 缩进两个空格、非 ASCII 原样写出、对象按键插入顺序（Dictionary.save 单独排序）。
 * 解析端容忍 BOM 与常见格式，非法输入抛 IOException。
 */
public final class Json {

    private Json() {
    }

    // ---------- 解析 ----------

    public static Object parse(String text) throws IOException {
        if (text != null && !text.isEmpty()) {
            char first = text.charAt(0);
            if (first == '﻿') { // UTF-8 BOM (U+FEFF)
                text = text.substring(1);
            }
        }
        Parser p = new Parser(text);
        Object value = p.parseValue();
        p.skipWs();
        if (!p.atEnd()) {
            throw new IOException("JSON 解析出错：末尾有多余内容（位置 " + p.pos + "）");
        }
        return value;
    }

    public static Object readFile(Path path) throws IOException {
        return parse(Files.readString(path, StandardCharsets.UTF_8));
    }

    /** 与 Python 版一致的容错读取：失败返回 null，不抛异常。 */
    public static Object readFileQuiet(Path path) {
        try {
            return readFile(path);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    // ---------- 写出 ----------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value, 0);
        return sb.toString();
    }

    public static void writeFile(Path path, Object value) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.createDirectories(path.getParent());
        Files.writeString(tmp, write(value), StandardCharsets.UTF_8);
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            sb.append(value);
        } else if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                sb.append("null");
            } else if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                long l = d.longValue();
                sb.append(l);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value);
        } else if (value instanceof Map<?, ?> m) {
            writeObject(sb, m, indent);
        } else if (value instanceof List<?> l) {
            writeArray(sb, l, indent);
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> m, int indent) {
        if (m.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            indent(sb, indent + 1);
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(": ");
            writeValue(sb, e.getValue(), indent + 1);
            if (++i < m.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> l, int indent) {
        if (l.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < l.size(); i++) {
            indent(sb, indent + 1);
            writeValue(sb, l.get(i), indent + 1);
            if (i < l.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int level) {
        for (int i = 0; i < level; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ---------- 解析器 ----------

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return pos >= s.length();
        }

        void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        char peek() throws IOException {
            if (atEnd()) {
                throw new IOException("JSON 解析出错：意外结束");
            }
            return s.charAt(pos);
        }

        Object parseValue() throws IOException {
            skipWs();
            char c = peek();
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        void expect(String word) throws IOException {
            if (!s.startsWith(word, pos)) {
                throw new IOException("JSON 解析出错：位置 " + pos + " 处期望 " + word);
            }
            pos += word.length();
        }

        Map<String, Object> parseObject() throws IOException {
            Map<String, Object> out = new LinkedHashMap<>();
            pos++; // {
            skipWs();
            if (peek() == '}') {
                pos++;
                return out;
            }
            while (true) {
                skipWs();
                if (peek() != '"') {
                    throw new IOException("JSON 解析出错：对象键必须是字符串（位置 " + pos + "）");
                }
                String key = parseString();
                skipWs();
                if (peek() != ':') {
                    throw new IOException("JSON 解析出错：缺少冒号（位置 " + pos + "）");
                }
                pos++;
                out.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return out;
                } else {
                    throw new IOException("JSON 解析出错：位置 " + pos + " 处缺少 , 或 }");
                }
            }
        }

        List<Object> parseArray() throws IOException {
            List<Object> out = new ArrayList<>();
            pos++; // [
            skipWs();
            if (peek() == ']') {
                pos++;
                return out;
            }
            while (true) {
                out.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return out;
                } else {
                    throw new IOException("JSON 解析出错：位置 " + pos + " 处缺少 , 或 ]");
                }
            }
        }

        String parseString() throws IOException {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new IOException("JSON 解析出错：字符串未闭合");
                }
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new IOException("JSON 解析出错：转义序列不完整");
                    }
                    char e = s.charAt(pos++);
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 't' -> sb.append('\t');
                        case 'r' -> sb.append('\r');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw new IOException("JSON 解析出错：\\u 转义不完整");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw new IOException("JSON 解析出错：未知转义 \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        Object parseNumber() throws IOException {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean isFloat = false;
            while (!atEnd()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    isFloat = true;
                    pos++;
                } else {
                    break;
                }
            }
            if (pos == start || (pos == start + 1 && s.charAt(start) == '-')) {
                throw new IOException("JSON 解析出错：位置 " + start + " 处不是合法的值");
            }
            String num = s.substring(start, pos);
            if (!isFloat) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException ignored) {
                    // 超出 long 范围则按 double
                }
            }
            return Double.parseDouble(num);
        }
    }

    /** 按码点比较（对齐 Python 的 sort_keys 行为）。 */
    public static final Comparator<String> CODE_POINT_ORDER = (a, b) -> {
        int la = a.length(), lb = b.length();
        int ia = 0, ib = 0;
        while (ia < la && ib < lb) {
            int ca = a.codePointAt(ia);
            int cb = b.codePointAt(ib);
            if (ca != cb) {
                return Integer.compare(ca, cb);
            }
            ia += Character.charCount(ca);
            ib += Character.charCount(cb);
        }
        boolean aDone = ia >= la;
        boolean bDone = ib >= lb;
        if (aDone && bDone) {
            return 0;
        }
        return aDone ? -1 : 1;
    };
}
