package com.jartrans.core;

import com.jartrans.core.jar.JarReader;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Parsed archive snapshot. Build it completely before installing it in a project. */
final class ProjectArchive {
    private record ClassInfo(ClassFile file, List<Project.TextCount> texts,
                             Set<String> textSet, Map<String, List<String>> usage) {
    }

    private final JarReader reader;
    private final Map<String, ClassInfo> index;
    private final List<String> order;
    private final Map<String, ClassFile> classes;
    private final Map<String, Set<String>> textSets;
    private final List<Project.FailedClass> failures;

    private ProjectArchive(JarReader reader, Map<String, ClassInfo> index,
                           List<Project.FailedClass> failures) {
        this.reader = reader;
        this.index = Collections.unmodifiableMap(new LinkedHashMap<>(index));
        this.order = List.copyOf(index.keySet());
        Map<String, ClassFile> files = new LinkedHashMap<>();
        Map<String, Set<String>> sets = new LinkedHashMap<>();
        index.forEach((name, info) -> {
            files.put(name, info.file());
            sets.put(name, info.textSet());
        });
        this.classes = Collections.unmodifiableMap(files);
        this.textSets = Collections.unmodifiableMap(sets);
        this.failures = List.copyOf(failures);
    }

    static ProjectArchive empty() {
        return new ProjectArchive(null, Map.of(), List.of());
    }

    static ProjectArchive load(Path path) throws IOException {
        JarReader reader;
        try {
            reader = new JarReader(path);
        } catch (JarReader.JarFileException failure) {
            throw new IOException(failure.getMessage(), failure);
        }
        Map<String, ClassInfo> index = new LinkedHashMap<>();
        List<Project.FailedClass> failures = new ArrayList<>();
        for (String name : reader.classNames()) {
            try {
                ClassFile file = ClassFile.parse(reader.readClass(name));
                index.put(name, collect(file));
            } catch (ClassFileException failure) {
                failures.add(new Project.FailedClass(name, failure.getMessage()));
            }
        }
        return new ProjectArchive(reader, index, failures);
    }

    private static ClassInfo collect(ClassFile file) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Literal literal : file.literals()) {
            if (literal.text != null) {
                counts.merge(literal.text, 1, Integer::sum);
            }
        }
        List<Project.TextCount> texts = counts.entrySet().stream()
                .map(e -> new Project.TextCount(e.getKey(), e.getValue())).toList();
        Map<String, List<String>> usage = new LinkedHashMap<>();
        try {
            Bytecode.stringUsage(file).forEach((text, names) -> usage.put(text, List.copyOf(names)));
        } catch (RuntimeException ignored) {
            // Method usage is optional; a malformed method must not hide valid literals.
        }
        return new ClassInfo(file, texts,
                Collections.unmodifiableSet(new LinkedHashSet<>(counts.keySet())),
                Collections.unmodifiableMap(usage));
    }

    JarReader reader() { return reader; }
    List<String> order() { return order; }
    Map<String, ClassFile> classes() { return classes; }
    Map<String, Set<String>> textSets() { return textSets; }
    List<Project.FailedClass> failures() { return failures; }

    List<Project.TextCount> texts(String name) {
        ClassInfo info = index.get(name);
        return info == null ? List.of() : info.texts();
    }

    Set<String> textSet(String name) {
        ClassInfo info = index.get(name);
        return info == null ? Set.of() : info.textSet();
    }

    List<String> methodNames(String name, String text) {
        ClassInfo info = index.get(name);
        return info == null ? List.of() : info.usage().getOrDefault(text, List.of());
    }

    long textCount() {
        return index.values().stream().mapToLong(info -> info.texts().size()).sum();
    }
}
