package com.jartrans.core.java;

import com.jartrans.core.Settings;
import com.jartrans.core.io.AtomicFiles;
import com.jartrans.core.log.Log;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/** 反编译器门面：注册进程内引擎、协调缓存，并隔离并发输出。 */
public final class DecompilerManager {

    private static final System.Logger LOG = Log.get(DecompilerManager.class);

    public static final Path CACHE_ROOT = Paths.get(System.getProperty("java.io.tmpdir"), "jartrans_decomp");
    private static final Map<DecompilerType, DecompilerEngine> ENGINES = Map.of(
            DecompilerType.VINEFLOWER, new VineflowerEngine(),
            DecompilerType.CFR, new CfrEngine(),
            DecompilerType.PROCYON, new ProcyonEngine());
    private static final ConcurrentHashMap<String, ReentrantLock> OUTPUT_LOCKS = new ConcurrentHashMap<>();

    private DecompilerManager() {}

    public static class DecompileException extends Exception {
        private static final long serialVersionUID = 1L;

        public DecompileException(String message) {
            super(message);
        }

        public DecompileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 引擎元数据；实现始终来自应用 classpath。 */
    public record EngineInfo(DecompilerType type, String version) {}

    public static List<EngineInfo> engines() {
        return List.of(
                new EngineInfo(DecompilerType.VINEFLOWER, "1.12.0"),
                new EngineInfo(DecompilerType.CFR, "0.152"),
                new EngineInfo(DecompilerType.PROCYON, "0.6.0"));
    }

    public static DecompilerType currentDecompiler(Settings settings) {
        DecompilerType selected = DecompilerType.fromKey(settings.getString(Settings.Keys.DECOMPILER_TYPE));
        return selected == null ? DecompilerType.VINEFLOWER : selected;
    }

    public static Path cacheDir(String sha256) {
        if (sha256 == null || !sha256.matches("[a-fA-F0-9]{16,64}")) {
            throw new IllegalArgumentException("jar SHA-256 必须为十六进制摘要");
        }
        return CACHE_ROOT.resolve(sha256);
    }

    public static Path outputDir(String sha, DecompilerType type) {
        return cacheDir(sha).resolve(type.key).resolve("classes");
    }

    public static void clearAllCache() {
        deleteRecursively(CACHE_ROOT);
        // 锁对象随缓存目录一起释放，避免换 N 个 jar 后遗留 N×3 把无主锁
        OUTPUT_LOCKS.clear();
    }

    public static void cleanupOnExit() {
        clearAllCache();
    }

    private static void deleteRecursively(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // 占用中的文件交由下次清理。
                    LOG.log(System.Logger.Level.DEBUG, "缓存文件删除失败（占用中）：" + p, e);
                }
            });
        } catch (IOException e) {
            // 遍历失败时保留缓存。
            LOG.log(System.Logger.Level.WARNING, "缓存目录遍历失败，保留：" + target, e);
        }
    }

    /**
     * 单类及内部类按需反编译，依赖字节从原 JAR 加载；只缓存本次返回的源码。
     * 同一 JAR/引擎串行执行，等待锁期间可以取消；运行中的引擎自然收尾。
     */
    public static List<Path> decompileClasses(
            String jarPath, String sha256, Collection<String> classNames, DecompilerType type)
            throws DecompileException {
        if (jarPath == null
                || jarPath.isBlank()
                || sha256 == null
                || sha256.isBlank()
                || classNames == null
                || classNames.isEmpty()
                || type == null) {
            throw new DecompileException("反编译参数不完整");
        }
        Path out;
        try {
            out = outputDir(sha256, type).toAbsolutePath().normalize();
            for (String name : classNames) {
                sourcePath(out, name);
            }
        } catch (IllegalArgumentException e) {
            throw new DecompileException("反编译参数无效：" + e.getMessage(), e);
        }
        ReentrantLock lock = OUTPUT_LOCKS.computeIfAbsent(sha256 + ":" + type.key, k -> new ReentrantLock());
        try {
            lock.lockInterruptibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DecompileException("已取消", e);
        }
        try {
            Map<String, String> sources;
            try (ZipFile jar = new ZipFile(jarPath)) {
                List<String> found = classNames.stream()
                        .distinct()
                        .filter(name -> jar.getEntry(name + ".class") != null)
                        .toList();
                if (found.isEmpty()) {
                    throw new DecompileException("jar 中未找到指定类：" + classNames);
                }
                sources = ENGINES.get(type).decompile(jar, found);
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new DecompileException("已取消");
            }
            List<Path> result = new ArrayList<>();
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = sourcePath(out, source.getKey());
                AtomicFiles.write(file, temporary -> Files.writeString(temporary, source.getValue()));
                result.add(file);
            }
            result.sort(Comparator.comparing(Path::toString));
            return List.copyOf(result);
        } catch (IOException | RuntimeException | LinkageError e) {
            throw new DecompileException("进程内反编译失败（" + type.displayName() + "）：" + e.getMessage(), e);
        } finally {
            lock.unlock();
        }
    }

    private static Path sourcePath(Path out, String name) {
        if (name == null
                || name.isBlank()
                || name.contains("\\")
                || name.startsWith("/")
                || name.contains(":")
                || name.contains(".")
                || name.contains("//")) {
            throw new IllegalArgumentException("无效的类内部名：" + name);
        }
        Path file = out.resolve(name + ".java").normalize();
        if (!file.startsWith(out)) {
            throw new IllegalArgumentException("类路径超出缓存目录");
        }
        return file;
    }

    /** 相对路径（正斜杠）到绝对路径的索引。 */
    public static Map<String, Path> buildFileIndex(Path outDir) {
        Map<String, Path> index = new LinkedHashMap<>();
        if (outDir == null || !Files.isDirectory(outDir)) {
            return index;
        }
        try (Stream<Path> walk = Files.walk(outDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> index.put(outDir.relativize(p).toString().replace('\\', '/'), p.toAbsolutePath()));
        } catch (IOException e) {
            // 返回已建立的索引。
            LOG.log(System.Logger.Level.WARNING, "反编译产物索引构建失败：" + outDir, e);
        }
        return index;
    }

    public static SourceFiles sourceFilesFor(Map<String, Path> index, String classPath) {
        String base = classPath.substring(0, classPath.length() - ".class".length());
        String external = base + ".java";
        List<String> inners = index.keySet().stream()
                .filter(k -> k.startsWith(base + "$") && k.endsWith(".java"))
                .sorted()
                .toList();
        return new SourceFiles(index.containsKey(external) ? external : null, inners);
    }

    public record SourceFiles(String external, List<String> inners) {}
}
