package com.jartrans.core;

import com.jartrans.core.json.Json;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Owns the on-disk progress format, independently of the current archive and UI. */
final class ProgressStore {
    private static final String SKIP_TEXTS_KEY = "__skip_texts";
    private static final int MAX_PROJECTS = 50;
    private final Path path;

    record Progress(Map<String, String> classStatus, Set<String> skippedTexts) {}

    ProgressStore(Path path) {
        this.path = path;
    }

    private Map<String, Object> read() {
        Map<String, Object> data = Json.readObjectQuiet(path);
        return data == null ? new LinkedHashMap<>() : data;
    }

    Progress load(String key) {
        Map<String, String> statuses = new LinkedHashMap<>();
        Set<String> skipped = new LinkedHashSet<>();
        Map<String, Object> block = key == null ? null : Json.object(read().get(key));
        if (block != null) {
            block.forEach((name, value) -> {
                if (Project.MANUAL_STATES.contains(String.valueOf(value))) {
                    statuses.put(name, String.valueOf(value));
                } else if (SKIP_TEXTS_KEY.equals(name) && value instanceof List<?> list) {
                    list.forEach(text -> skipped.add(String.valueOf(text)));
                }
            });
        }
        return new Progress(statuses, skipped);
    }

    void save(String key, Map<String, String> statuses, Set<String> skipped) throws IOException {
        if (key == null) {
            return;
        }
        Map<String, Object> data = read();
        if (statuses.isEmpty() && skipped.isEmpty()) {
            data.remove(key);
        } else {
            Map<String, Object> block = new LinkedHashMap<>(statuses);
            if (!skipped.isEmpty()) {
                List<String> sorted = new ArrayList<>(skipped);
                sorted.sort(String::compareTo);
                block.put(SKIP_TEXTS_KEY, sorted);
            }
            data.put(key, block);
        }
        while (data.size() > MAX_PROJECTS) {
            data.remove(data.keySet().iterator().next());
        }
        Json.writeFile(path, data);
    }
}
