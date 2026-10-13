package com.jartrans.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Owns manual/automatic translation precedence and clearing semantics. */
final class TranslationStore {
    private final Map<String, Map<String, String>> manual = new LinkedHashMap<>();
    private final Map<String, Map<String, String>> automatic = new LinkedHashMap<>();

    void clear() {
        manual.clear();
        automatic.clear();
    }

    Map<String, String> effective(String cls) {
        Map<String, String> result = new LinkedHashMap<>(automatic.getOrDefault(cls, Map.of()));
        result.putAll(manual.getOrDefault(cls, Map.of()));
        result.values().removeIf(value -> value == null || value.isEmpty());
        return result;
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
            return;
        }
        Map<String, Map<String, String>> target = autoFill ? automatic : manual;
        Map<String, Map<String, String>> other = autoFill ? manual : automatic;
        target.computeIfAbsent(cls, key -> new LinkedHashMap<>()).put(original, translated);
        remove(other, cls, original);
    }

    void applyAutomatic(String cls, String original, String translated) {
        if (!manual.getOrDefault(cls, Map.of()).containsKey(original)) {
            automatic.computeIfAbsent(cls, key -> new LinkedHashMap<>()).put(original, translated);
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
