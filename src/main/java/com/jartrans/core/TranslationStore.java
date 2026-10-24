package com.jartrans.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns manual/automatic translation precedence and clearing semantics.
 *
 * {@link #effective(String)} 的结果做惰性缓存：classState/stats/search/UI 刷新
 * 会按类反复读取该快照，重算每次都要分配合并两个 Map；所有翻译变更入口都在本类，
 * 因此在变更点精确失效（单类失效单类，clear 全失效），缓存永不过期。
 */
final class TranslationStore {

    private final Map<String, Map<String, String>> manual = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> automatic = new LinkedHashMap<>();
    /** effective(cls) 的不可变快照缓存；null 值表示该类未缓存。 */
    private final Map<String, Map<String, String>> effectiveCache = new HashMap<>();

    void clear() {
        manual.clear();
        automatic.clear();
        effectiveCache.clear();
    }

    /**
     * 该类当前生效的译文表（用户编辑覆盖自动填充，空译文视为未翻译）。
     * 返回只读快照，调用方不得修改（缓存复用同一实例）。
     */
    Map<String, String> effective(String cls) {
        Map<String, String> cached = effectiveCache.get(cls);
        if (cached != null) {
            return cached;
        }
        Map<String, String> merged = new LinkedHashMap<>(automatic.getOrDefault(cls, Map.of()));
        merged.putAll(manual.getOrDefault(cls, Map.of()));
        merged.values().removeIf(value -> value == null || value.isEmpty());
        Map<String, String> snapshot = Collections.unmodifiableMap(merged);
        effectiveCache.put(cls, snapshot);
        return snapshot;
    }

    Project.Status status(String cls, String original) {
        if (manual.getOrDefault(cls, Map.of()).containsKey(original)) {
            return Project.Status.TRANSLATED;
        }
        if (automatic.getOrDefault(cls, Map.of()).containsKey(original)) {
            return Project.Status.AUTO;
        }
        return Project.Status.UNTRANSLATED;
    }

    void set(String cls, String original, String translated, boolean autoFill) {
        if (translated == null || translated.isEmpty() || translated.equals(original)) {
            remove(manual, cls, original);
            remove(automatic, cls, original);
        } else {
            Map<String, Map<String, String>> target = autoFill ? automatic : manual;
            Map<String, Map<String, String>> other = autoFill ? manual : automatic;
            target.computeIfAbsent(cls, key -> new LinkedHashMap<>()).put(original, translated);
            remove(other, cls, original);
        }
        effectiveCache.remove(cls);
    }

    void applyAutomatic(String cls, String original, String translated) {
        if (!manual.getOrDefault(cls, Map.of()).containsKey(original)) {
            automatic.computeIfAbsent(cls, key -> new LinkedHashMap<>()).put(original, translated);
            effectiveCache.remove(cls);
        }
    }

    private static void remove(Map<String, Map<String, String>> source, String cls, String original) {
        Map<String, String> translations = source.get(cls);
        if (translations != null) {
            translations.remove(original);
            if (translations.isEmpty()) {
                source.remove(cls);
            }
        }
    }
}
