package com.jartrans.core.java;

import com.jartrans.core.AppDirs;
import com.jartrans.core.Settings;
import com.jartrans.core.jar.JarReader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * 反编译器管理：tools/ 扫描、整 jar 反编译、临时缓存目录。
 *
 * 缓存：%TEMP%/jartrans_decomp/&lt;jar内容sha256前16位&gt;/，成功后写 .ok 标记。
 * 程序退出时由 GUI 调 cleanupOnExit() 清空整个缓存根目录。
 * 支持 Vineflower / CFR / Procyon 三种反编译器。
 */
public final class DecompilerManager {

    public static final Path CACHE_ROOT =
            Paths.get(System.getProperty("java.io.tmpdir"), "jartrans_decomp");

    /** 内置反编译器 jar 的资源路径模板（Vineflower/CFR/Procyon 均随应用分发，
        首次需要时自动释放到 tools/，无需联网下载）。 */
    public static String bundledResource(DecompilerType type) {
        return "/com/jartrans/bundled/" + type.key + ".jar";
    }

    public static String bundledFileName(DecompilerType type) {
        return type.key + ".jar";
    }

    private static final AtomicReference<Process> ACTIVE_PROCESS = new AtomicReference<>();

    private DecompilerManager() {
    }

    /** 反编译错误。 */
    public static class DecompileException extends Exception {

        public DecompileException(String message) {
            super(message);
        }
    }

    /** tools/ 扫描结果：类型 + 路径 + 从文件名猜的版本。 */
    public record ToolEntry(DecompilerType type, Path path, String version) {
    }

    public static Path toolsDir() {
        return AppDirs.toolsDir();
    }

    /** 扫描 tools/ 目录，返回识别出的反编译器列表（按文件名排序）。 */
    public static List<ToolEntry> scanTools() {
        List<ToolEntry> found = new ArrayList<>();
        Path dir = toolsDir();
        if (!Files.isDirectory(dir)) {
            return found;
        }
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.jar")) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException ignored) {
            return found;
        }
        names.sort(String::compareTo);
        for (String name : names) {
            Path path = dir.resolve(name);
            DecompilerType type = DecompilerType.fromFileName(name.toLowerCase());
            if (type == null || !DecompilerDownloader.isZipFile(path)) {
                continue;
            }
            String stem = name.substring(0, name.length() - 4);
            String version = "";
            String[] parts = stem.split("-");
            if (parts.length > 1) {
                version = parts[parts.length - 1];
            }
            found.add(new ToolEntry(type, path, version));
        }
        return found;
    }

    /** 配置的优先，其次 tools/ 扫描。返回 [type, path] 或 null。 */
    public static Object[] currentDecompiler(Settings settings) {
        String configured = settings.getString("decompiler_path");
        if (!configured.isEmpty() && Files.isRegularFile(Paths.get(configured))
                && DecompilerDownloader.isZipFile(Paths.get(configured))) {
            DecompilerType type = DecompilerType.fromKey(settings.getString("decompiler_type"));
            return new Object[]{type != null ? type : DecompilerType.VINEFLOWER, configured};
        }
        List<ToolEntry> scan = scanTools();
        // 默认优先级：Vineflower > CFR > Procyon
        for (DecompilerType preferred : List.of(DecompilerType.VINEFLOWER,
                DecompilerType.CFR, DecompilerType.PROCYON)) {
            for (ToolEntry entry : scan) {
                if (entry.type() == preferred) {
                    return new Object[]{entry.type(), entry.path().toString()};
                }
            }
        }
        return null;
    }

    /** tools/ 中是否已有指定类型的反编译器。 */
    public static boolean hasTool(DecompilerType type) {
        for (ToolEntry entry : scanTools()) {
            if (entry.type() == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * 确保指定反编译器可用：tools/ 中缺失时，把随应用内置的 jar 释放过去。
     * 成功后无需联网与配置即可被 scanTools/currentDecompiler 发现。
     */
    public static boolean ensureBundled(DecompilerType type) {
        if (hasTool(type)) {
            return true;
        }
        try {
            Path dir = toolsDir();
            Files.createDirectories(dir);
            Path target = dir.resolve(bundledFileName(type));
            if (!Files.isRegularFile(target)) {
                try (InputStream in = DecompilerManager.class.getResourceAsStream(bundledResource(type))) {
                    if (in == null) {
                        return false;
                    }
                    Files.copy(in, target);
                }
            }
            return Files.isRegularFile(target) && DecompilerDownloader.isZipFile(target);
        } catch (IOException e) {
            return false;
        }
    }

    /** 确保三种反编译器均可用（Vineflower/CFR/Procyon 全部内置）。 */
    public static void ensureBundledAll() {
        for (DecompilerType type : DecompilerType.values()) {
            ensureBundled(type);
        }
    }

    /** 兼容旧入口：确保内置 Vineflower 可用。 */
    public static boolean ensureBundledDecompiler() {
        return ensureBundled(DecompilerType.VINEFLOWER);
    }

    public static Path cacheDir(String jarSha256) {
        return CACHE_ROOT.resolve(jarSha256.substring(0, Math.min(16, jarSha256.length())));
    }

    public static Path findCache(String jarSha256) {
        Path d = cacheDir(jarSha256);
        if (Files.isRegularFile(d.resolve(".ok"))) {
            return d;
        }
        return null;
    }

    public static void clearCache(String jarSha256) {
        deleteRecursively(cacheDir(jarSha256));
    }

    public static void clearAllCache() {
        deleteRecursively(CACHE_ROOT);
    }

    public static void cleanupOnExit() {
        deleteRecursively(CACHE_ROOT);
    }

    private static void deleteRecursively(Path target) {
        if (!Files.exists(target)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(target)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 删除失败忽略
                }
            });
        } catch (IOException ignored) {
            // 遍历失败忽略
        }
    }

    /** 终止正在运行的反编译进程。 */
    public static void cancelActive() {
        Process proc = ACTIVE_PROCESS.get();
        if (proc != null && proc.isAlive()) {
            proc.destroyForcibly();
        }
    }

    /**
     * 反编译 jar 中的若干类（classNames 为不带 .class 的内部名，可含内部类）到缓存目录，
     * 返回本次产出的 .java 绝对路径。只把选中的类字节复制到临时输入目录喂给反编译器，
     * 避免整 jar 反编译的等待。输出累积在 cacheDir(sha)/classes 下（不带 .ok 标记）。
     */
    public static List<Path> decompileClasses(String jarPath, String sha256,
                                              java.util.Collection<String> classNames,
                                              String javaPath, String decompilerPath,
                                              DecompilerType type, LogSink log,
                                              CancelCheck cancelCheck)
            throws DecompileException {
        Path base = cacheDir(sha256);
        Path out = base.resolve("classes");
        try {
            Files.createDirectories(out);
        } catch (IOException exc) {
            throw new DecompileException("无法创建输出目录：" + exc.getMessage());
        }
        // 输入：只含目标类与内部类的临时 jar（Vineflower/CFR/Procyon 三种引擎都认 jar）
        Path input;
        int found = 0;
        try {
            input = Files.createTempFile(CACHE_ROOT.getParent(), "jartrans_in_", ".jar");
        } catch (IOException exc) {
            throw new DecompileException("无法创建临时输入 jar：" + exc.getMessage());
        }
        try {
            JarReader jr = new JarReader(Paths.get(jarPath));
            try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(input))) {
                for (String name : classNames) {
                    byte[] bytes = jr.readEntry(name + ".class");
                    if (bytes == null) {
                        continue;
                    }
                    jos.putNextEntry(new java.util.zip.ZipEntry(name + ".class"));
                    jos.write(bytes);
                    jos.closeEntry();
                    found++;
                }
            }
        } catch (JarReader.JarFileException exc) {
            throw new DecompileException("读取 jar 类失败：" + exc.getMessage());
        } catch (IOException exc) {
            throw new DecompileException("构造临时输入 jar 失败：" + exc.getMessage());
        }
        if (found == 0) {
            try {
                Files.deleteIfExists(input);
            } catch (IOException ignored) {
                // 忽略
            }
            throw new DecompileException("jar 中未找到指定类：" + classNames);
        }
        try {
            if (type == DecompilerType.VINEFLOWER) {
                // Vineflower 已随应用以库形式打入 classpath：进程内反编译，无子进程
                runVineflowerInProcess(out, input);
                return producedFor(out, classNames);
            }
            return runWithInput(out, javaPath, decompilerPath, type,
                    input, classNames, log, cancelCheck);
        } finally {
            try {
                Files.deleteIfExists(input);
            } catch (IOException ignored) {
                // 清理失败忽略
            }
        }
    }

    /** 进程内调用内置 Vineflower（ConsoleDecompiler），输出直接写 out 目录。 */
    private static void runVineflowerInProcess(Path out, Path inJar)
            throws DecompileException {
        try {
            Class<?> cls = Class.forName(
                    "org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler");
            Class<?> loggerCls = Class.forName(
                    "org.jetbrains.java.decompiler.main.extern.IFernflowerLogger");
            java.util.Map<String, Object> options = new java.util.LinkedHashMap<>();
            options.put("log_level", "error");
            // 静默日志：用框架自带的 NO_OP 实例
            Object logger = loggerCls.getField("NO_OP").get(null);
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(
                    java.io.File.class, java.util.Map.class, loggerCls);
            ctor.setAccessible(true);
            Object decomp = ctor.newInstance(out.toFile(), options, logger);
            cls.getMethod("addSource", java.io.File.class).invoke(decomp, inJar.toFile());
            cls.getMethod("decompileContext").invoke(decomp);
            // 进程内模式可能把结果打成 <输入名>.jar：把里面的 .java 解包到 out 根目录
            unwrapArchives(out);
        } catch (Exception exc) {
            throw new DecompileException("进程内 Vineflower 反编译失败：" + exc);
        }
    }

    private static void unwrapArchives(Path out) throws IOException {
        List<Path> archives = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(out, "*.jar")) {
            for (Path p : ds) {
                archives.add(p);
            }
        }
        for (Path archive : archives) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(archive.toFile())) {
                var entries = zf.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().endsWith(".java")) {
                        continue;
                    }
                    Path dest = out.resolve(entry.getName());
                    Files.createDirectories(dest.getParent());
                    try (InputStream in = zf.getInputStream(entry)) {
                        Files.copy(in, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            } finally {
                Files.deleteIfExists(archive);
            }
        }
    }

    private static List<Path> runWithInput(Path out, String javaPath, String decompilerPath,
                                           DecompilerType type, Path inJar,
                                           java.util.Collection<String> classNames,
                                           LogSink log, CancelCheck cancelCheck)
            throws DecompileException {
        List<String> cmd = type.buildCommand(javaPath, decompilerPath,
                inJar.toString(), out.toString());
        int code;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ACTIVE_PROCESS.set(proc);
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                var baos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelCheck != null && cancelCheck.isCancelled()) {
                        proc.destroyForcibly();
                        throw new DecompileException("已取消");
                    }
                    baos.write(buf, 0, n);
                    byte[] all = baos.toByteArray();
                    int lineStart = 0;
                    for (int i = 0; i < all.length; i++) {
                        if (all[i] == '\n') {
                            emitLine(log, all, lineStart, i);
                            lineStart = i + 1;
                        }
                    }
                    baos.reset();
                    baos.write(all, lineStart, all.length - lineStart);
                }
                if (baos.size() > 0) {
                    emitLine(log, baos.toByteArray(), 0, baos.size());
                }
            }
            code = proc.waitFor();
        } catch (IOException exc) {
            throw new DecompileException("无法启动反编译器：" + exc.getMessage());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new DecompileException("已取消");
        } finally {
            ACTIVE_PROCESS.set(null);
        }

        if (code != 0) {
            throw new DecompileException("反编译器退出码 " + code + "，详见输出日志");
        }
        return producedFor(out, classNames);
    }

    /** 只返回与本次请求类相关的产物（其它类累积产物不在此列）。 */
    private static List<Path> producedFor(Path out, java.util.Collection<String> classNames) {
        List<String> relOf = classNames.stream().map(n -> n + ".java").toList();
        List<Path> produced = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(out)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .forEach(p -> {
                        String rel = out.relativize(p).toString().replace('\\', '/');
                        for (String r : relOf) {
                            if (rel.equals(r)
                                    || (rel.startsWith(r) && rel.length() > r.length()
                                    && "$./".indexOf(rel.charAt(r.length())) >= 0)) {
                                produced.add(p);
                                break;
                            }
                        }
                    });
        } catch (IOException ignored) {
            // 忽略
        }
        produced.sort(Comparator.comparing(Path::toString));
        return produced;
    }

    /**
     * 反编译整个 jar 到 outDir。阻塞执行（应在后台线程调用）。
     * log(line) 用于回显反编译器输出；cancelCheck() 返回 true 时终止进程。
     * 成功后写 outDir/.ok 标记。
     */
    public static Path decompile(String jarPath, Path outDir, String javaPath,
                                 String decompilerPath, DecompilerType type,
                                 LogSink log, CancelCheck cancelCheck)
            throws DecompileException {
        try {
            Files.createDirectories(outDir);
        } catch (IOException exc) {
            throw new DecompileException("无法创建输出目录：" + exc.getMessage());
        }
        List<String> cmd = type.buildCommand(javaPath, decompilerPath, jarPath,
                outDir.toString());
        int code;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            ACTIVE_PROCESS.set(proc);
            try (InputStream in = proc.getInputStream()) {
                byte[] buf = new byte[4096];
                var baos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (cancelCheck != null && cancelCheck.isCancelled()) {
                        proc.destroyForcibly();
                        throw new DecompileException("已取消");
                    }
                    baos.write(buf, 0, n);
                    // 按行切分回显
                    byte[] all = baos.toByteArray();
                    int lineStart = 0;
                    for (int i = 0; i < all.length; i++) {
                        if (all[i] == '\n') {
                            emitLine(log, all, lineStart, i);
                            lineStart = i + 1;
                        }
                    }
                    baos.reset();
                    baos.write(all, lineStart, all.length - lineStart);
                }
                if (baos.size() > 0) {
                    emitLine(log, baos.toByteArray(), 0, baos.size());
                }
            }
            code = proc.waitFor();
            proc.waitFor(5, TimeUnit.SECONDS);
        } catch (IOException exc) {
            throw new DecompileException("无法启动反编译器：" + exc.getMessage());
        } catch (InterruptedException exc) {
            Thread.currentThread().interrupt();
            throw new DecompileException("已取消");
        } finally {
            ACTIVE_PROCESS.set(null);
        }

        if (code != 0) {
            deleteRecursively(outDir);
            throw new DecompileException(
                    "反编译器退出码 " + code + "（jar 或 java 环境问题），详见输出日志");
        }
        if (cancelCheck != null && cancelCheck.isCancelled()) {
            deleteRecursively(outDir);
            throw new DecompileException("已取消");
        }

        // 部分版本会把输出包在以 jar 名命名的子目录里，探测真实根目录
        Path root = outDir;
        String jarStem = Paths.get(jarPath).getFileName().toString();
        jarStem = jarStem.endsWith(".jar") ? jarStem.substring(0, jarStem.length() - 4) : jarStem;
        List<String> entries = listNames(outDir);
        if (entries.size() == 1 && entries.get(0).equals(jarStem)
                && Files.isDirectory(outDir.resolve(jarStem))) {
            Path sub = outDir.resolve(jarStem);
            try {
                relocate(sub, outDir);
                root = outDir;
            } catch (IOException ignored) {
                root = sub;
            }
        }

        if (!hasJavaFiles(root)) {
            deleteRecursively(outDir);
            throw new DecompileException("反编译完成但没有生成 .java 文件");
        }
        try {
            Files.writeString(root.resolve(".ok"), "ok", StandardCharsets.UTF_8);
            if (!root.equals(outDir)) {
                // 把 .ok 放在外层目录作为缓存有效标记
                Files.writeString(outDir.resolve(".ok"), "ok:" + root.getFileName(),
                        StandardCharsets.UTF_8);
            }
        } catch (IOException exc) {
            throw new DecompileException("无法写入缓存标记：" + exc.getMessage());
        }
        return outDir;
    }

    @FunctionalInterface
    public interface LogSink {
        void onLine(String line);
    }

    @FunctionalInterface
    public interface CancelCheck {
        boolean isCancelled();
    }

    private static void emitLine(LogSink log, byte[] bytes, int start, int endExclusive) {
        if (log == null) {
            return;
        }
        String line = new String(bytes, start, endExclusive - start, StandardCharsets.UTF_8);
        log.onLine(line.replaceAll("\r$", ""));
    }

    private static List<String> listNames(Path dir) {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            stream.forEach(p -> names.add(p.getFileName().toString()));
        } catch (IOException ignored) {
            // 读取失败按空目录处理
        }
        return names;
    }

    private static boolean hasJavaFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.anyMatch(p -> p.getFileName().toString().endsWith(".java"));
        } catch (IOException e) {
            return false;
        }
    }

    /** 把 srcRoot 下的内容移动到 dest。 */
    private static void relocate(Path srcRoot, Path dest) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(srcRoot)) {
            for (Path s : stream) {
                Path d = dest.resolve(s.getFileName());
                if (Files.exists(d)) {
                    continue;
                }
                Files.move(s, d);
            }
        }
        Files.deleteIfExists(srcRoot);
    }

    /** {相对路径(正斜杠): 绝对路径}，用于类 -> .java 定位。 */
    public static Map<String, Path> buildFileIndex(Path outDir) {
        Map<String, Path> index = new LinkedHashMap<>();
        if (outDir == null || !Files.isDirectory(outDir)) {
            return index;
        }
        try (Stream<Path> walk = Files.walk(outDir)) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java"))
                    .sorted()
                    .forEach(p -> {
                        String rel = outDir.relativize(p).toString().replace('\\', '/');
                        index.put(rel, p);
                    });
        } catch (IOException ignored) {
            // 遍历失败返回已有部分
        }
        return index;
    }

    /** 给定 'pkg/Name.class'，返回 (外部类相对路径或 null, 内部类相对路径列表)。 */
    public static SourceFiles sourceFilesFor(Map<String, Path> index, String classPath) {
        String base = classPath.substring(0, classPath.length() - ".class".length());
        String external = base + ".java";
        String innerPrefix = base + "$";
        List<String> inners = new ArrayList<>();
        for (String k : index.keySet()) {
            if (k.startsWith(innerPrefix) && k.endsWith(".java")) {
                inners.add(k);
            }
        }
        inners.sort(String::compareTo);
        return new SourceFiles(index.containsKey(external) ? external : null, inners);
    }

    public record SourceFiles(String external, List<String> inners) {
    }
}
