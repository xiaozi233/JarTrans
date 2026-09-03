package com.jartrans.core.java;

import com.jartrans.core.AppDirs;
import com.jartrans.core.Settings;
import com.jartrans.core.jar.JarReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 反编译器管理：tools/ 扫描、按需单类反编译（含内部类）、临时缓存目录。
 *
 * 单类产物累积在 %TEMP%/jartrans_decomp/&lt;jar内容sha256前16位&gt;/classes 下（无 .ok 标记）；
 * 老版本整 jar 缓存的 .ok 标记仍被 findCache 识别。程序退出时由 GUI 调 cleanupOnExit()
 * 清空整个缓存根目录。支持 Vineflower / CFR / Procyon 三种反编译器。
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

    /** 当前生效的反编译器选择：类型 + 可执行的 jar 路径。 */
    public record Selection(DecompilerType type, String path) {
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

    /** 配置的优先，其次 tools/ 扫描。无可用反编译器时返回 null。 */
    public static Selection currentDecompiler(Settings settings) {
        String configured = settings.getString("decompiler_path");
        if (!configured.isEmpty() && Files.isRegularFile(Paths.get(configured))
                && DecompilerDownloader.isZipFile(Paths.get(configured))) {
            DecompilerType type = DecompilerType.fromKey(settings.getString("decompiler_type"));
            return new Selection(type != null ? type : DecompilerType.VINEFLOWER, configured);
        }
        List<ToolEntry> scan = scanTools();
        // 默认优先级：Vineflower > CFR > Procyon
        for (DecompilerType preferred : List.of(DecompilerType.VINEFLOWER,
                DecompilerType.CFR, DecompilerType.PROCYON)) {
            for (ToolEntry entry : scan) {
                if (entry.type() == preferred) {
                    return new Selection(entry.type(), entry.path().toString());
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

    public static void clearAllCache() {
        deleteRecursively(CACHE_ROOT);
    }

    /** 程序退出时清空缓存（与 clearAllCache 相同，语义别名）。 */
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
                } catch (IOException ignored) {
                    // 删除失败忽略
                }
            });
        } catch (IOException ignored) {
            // 遍历失败忽略
        }
    }

    /**
     * 反编译 jar 中的若干类（classNames 为不带 .class 的内部名，可含内部类）到缓存目录，
     * 返回本次产出的 .java 绝对路径。只把选中的类字节复制到临时输入目录喂给反编译器，
     * 避免整 jar 反编译的等待。输出累积在 cacheDir(sha)/classes 下（不带 .ok 标记）。
     */
    public static List<Path> decompileClasses(String jarPath, String sha256,
                                              java.util.Collection<String> classNames,
                                              DecompilerType type)
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
            // 三种引擎均已随应用打进 classpath：全部进程内反编译，无子进程
            runEngineInProcess(type, out, input);
            return producedFor(out, classNames);
        } finally {
            try {
                Files.deleteIfExists(input);
            } catch (IOException ignored) {
                // 清理失败忽略
            }
        }
    }

    /** 进程内执行指定引擎（沿用 CLI 等价参数，输出写入 out 目录）。 */
    private static void runEngineInProcess(DecompilerType type, Path out, Path inJar)
            throws DecompileException {
        try {
            switch (type) {
                case VINEFLOWER -> runVineflowerInProcess(out, inJar);
                case CFR -> org.benf.cfr.reader.Main.main(new String[]{
                        inJar.toString(), "--outputdir", out.toString()});
                case PROCYON -> com.strobel.decompiler.DecompilerDriver.main(new String[]{
                        inJar.toString(), "-o", out.toString()});
            }
        } catch (Throwable exc) {
            throw new DecompileException("进程内反编译失败（" + type.displayName() + "）：" + exc);
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
