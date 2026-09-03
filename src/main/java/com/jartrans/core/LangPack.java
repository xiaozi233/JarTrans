package com.jartrans.core;

import com.jartrans.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 语言包（JSON）读写、校验与匹配。 */
public final class LangPack {

    public static final int FORMAT_VERSION = 1;

    private LangPack() {
    }

    /** 语言包格式错误。 */
    public static class LangPackException extends Exception {

        public LangPackException(String message) {
            super(message);
        }
    }

    /** 一条失效条目：类路径 + 原字符串 + 译文。 */
    public record MissingEntry(String cls, String orig, String trans) {
    }

    public static String computeSha256(Path path) throws IOException {
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    public static Map<String, Object> buildPack(String jarName, String jarSha256,
                                                String author,
                                                Map<String, Map<String, String>> entries,
                                                Map<String, String> classStatus) {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("format_version", (long) FORMAT_VERSION);
        pack.put("target_jar", jarName);
        pack.put("target_jar_sha256", jarSha256);
        pack.put("author", author);
        pack.put("entries", entries);
        if (classStatus != null && !classStatus.isEmpty()) {
            pack.put("class_status", new LinkedHashMap<>(classStatus));
        }
        return pack;
    }

    public static void writePack(Path path, Map<String, Object> pack) throws IOException {
        Json.writeFile(path, pack);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> readPack(Path path) throws LangPackException {
        Object data;
        try {
            data = Json.readFile(path);
        } catch (IOException exc) {
            throw new LangPackException("无法读取语言包：" + exc.getMessage());
        }
        Map<String, Object> map = Json.object(data);
        if (map == null || !(map.get("entries") instanceof Map)) {
            throw new LangPackException("语言包格式不正确：缺少 entries 对象");
        }
        Object version = map.getOrDefault("format_version", (long) FORMAT_VERSION);
        if (toLong(version) != FORMAT_VERSION) {
            throw new LangPackException("不支持的语言包版本：" + version);
        }
        return (Map<String, Object>) data;
    }

    /**
     * 把语言包匹配到当前 jar。
     *
     * classTexts: {类路径: 该类中存在的原字符串集合}。
     * applied = {类路径: {原字符串: 译文}}，仅含类和字符串都存在的条目；
     * missing = 失效条目列表（类或字符串已不存在）。
     */
    public static MatchResult matchPack(Map<String, Object> pack,
                                        Map<String, ? extends Set<String>> classTexts) {
        Map<String, Map<String, String>> applied = new LinkedHashMap<>();
        List<MissingEntry> missing = new ArrayList<>();
        Map<String, Object> entries =
                Json.object(pack.get("entries"));
        if (entries == null) {
            return new MatchResult(applied, missing);
        }
        for (Map.Entry<String, Object> e : entries.entrySet()) {
            String cls = e.getKey();
            Map<String, Object> pairs =
                    Json.object(e.getValue());
            if (pairs == null) {
                continue;
            }
            Set<String> texts = classTexts.get(cls);
            if (texts == null) {
                for (Map.Entry<String, Object> p : pairs.entrySet()) {
                    String t = str(p.getValue());
                    if (!t.isEmpty()) {
                        missing.add(new MissingEntry(cls, p.getKey(), t));
                    }
                }
                continue;
            }
            for (Map.Entry<String, Object> p : pairs.entrySet()) {
                String orig = p.getKey();
                String trans = str(p.getValue());
                if (trans.isEmpty()) {
                    continue;
                }
                if (texts.contains(orig)) {
                    applied.computeIfAbsent(cls, k -> new LinkedHashMap<>()).put(orig, trans);
                } else {
                    missing.add(new MissingEntry(cls, orig, trans));
                }
            }
        }
        return new MatchResult(applied, missing);
    }

    public record MatchResult(Map<String, Map<String, String>> applied,
                              List<MissingEntry> missing) {
    }

    static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    static long toLong(Object o) {
        if (o instanceof Number num) {
            return num.longValue();
        }
        if (o instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return Long.MIN_VALUE;
            }
        }
        return Long.MIN_VALUE;
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
