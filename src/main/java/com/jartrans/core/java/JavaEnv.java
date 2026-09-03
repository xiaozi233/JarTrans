package com.jartrans.core.java;

import com.jartrans.core.Settings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** java 运行时探测：配置项 → PATH → JAVA_HOME → MC 官方启动器 runtime。 */
public final class JavaEnv {

    private static final Path MC_RUNTIME_DIR = Paths.get(
            System.getenv().getOrDefault("LOCALAPPDATA", ""),
            "Packages", "Microsoft.4297127D64EC6_8wekyb3d8bbwe",
            "LocalCache", "Local", "runtime");

    private JavaEnv() {
    }

    private static String exeName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "java.exe" : "java";
    }

    private static Path exe(Path base) {
        return base.resolve(exeName());
    }

    /** 从 PATH 中查找 java（对齐 shutil.which）。 */
    private static String whichJava() {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null || pathEnv.isEmpty()) {
            return null;
        }
        for (String dir : pathEnv.split(Pattern.quote(System.getProperty("path.separator", ":")))) {
            if (dir.isBlank()) {
                continue;
            }
            Path candidate = exe(Paths.get(dir));
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return null;
    }

    /** 按优先级产出候选 java 路径。 */
    private static List<String> candidates(Settings settings) {
        List<String> out = new ArrayList<>();
        String configured = settings != null ? settings.getString("java_path") : "";
        if (!configured.isEmpty() && Files.isRegularFile(Paths.get(configured))) {
            out.add(configured);
        }
        String found = whichJava();
        if (found != null) {
            out.add(found);
        }
        String home = System.getenv("JAVA_HOME");
        if (home != null && !home.isEmpty()) {
            Path p = exe(Paths.get(home, "bin"));
            if (Files.isRegularFile(p)) {
                out.add(p.toString());
            }
        }
        if (Files.isDirectory(MC_RUNTIME_DIR)) {
            // 官方启动器的目录结构层级不定（可能带平台子目录），直接遍历找 bin/java.exe
            Set<String> seen = new LinkedHashSet<>();
            try {
                Files.walkFileTree(MC_RUNTIME_DIR, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        try {
                            try (var stream = Files.list(dir)) {
                                if (stream.anyMatch(f -> f.getFileName().toString().equals(exeName()))) {
                                    seen.add(exe(dir).toString());
                                }
                            }
                        } catch (IOException ignored) {
                            // 单个目录读取失败继续遍历
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // 遍历失败忽略
            }
            out.addAll(seen);
        }
        return out;
    }

    /** 运行 java -version 验证可用并解析版本号，失败返回 null。 */
    public static String probeVersion(String javaPath, long timeoutSeconds) {
        Process proc = null;
        try {
            proc = new ProcessBuilder(javaPath, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                return null;
            }
            byte[] outBytes = proc.getInputStream().readAllBytes();
            String text = new String(outBytes, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("version \"([^\"]+)\"").matcher(text);
            if (m.find()) {
                return m.group(1);
            }
            return null;
        } catch (IOException | InterruptedException e) {
            if (proc != null) {
                proc.destroyForcibly();
            }
            return null;
        }
    }

    public static String probeVersion(String javaPath) {
        return probeVersion(javaPath, 5);
    }

    private static List<Long> versionKey(String v) {
        List<Long> key = new ArrayList<>();
        for (String part : v.split("[._+]")) {
            if (part.matches("\\d+")) {
                key.add(Long.parseLong(part));
            } else {
                key.add(0L);
            }
        }
        return key;
    }

    private static int compareKeys(List<Long> a, List<Long> b) {
        int n = Math.max(a.size(), b.size());
        for (int i = 0; i < n; i++) {
            long x = i < a.size() ? a.get(i) : 0;
            long y = i < b.size() ? b.get(i) : 0;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return 0;
    }

    public record JavaResult(String path, String version, List<String> tried) {
    }

    /** 返回 (路径, 版本)；找不到时 path 为 null，tried 为已尝试列表。 */
    public static JavaResult findJava(Settings settings) {
        List<String> tried = new ArrayList<>();
        String bestPath = null;
        String bestVersion = null;
        List<Long> bestKey = null;
        String pathJava = whichJava();
        String configured = settings != null ? settings.getString("java_path") : "";
        for (String path : candidates(settings)) {
            if (tried.contains(path)) {
                continue;
            }
            tried.add(path);
            String version = probeVersion(path);
            if (version == null) {
                continue;
            }
            List<Long> key = versionKey(version);
            if (bestKey == null || compareKeys(key, bestKey) > 0) {
                bestKey = key;
                bestPath = path;
                bestVersion = version;
            }
            if (path.equals(pathJava) || (settings != null && path.equals(configured))) {
                // 显式指定的路径优先级最高，命中即用
                return new JavaResult(path, version, tried);
            }
        }
        return bestPath != null
                ? new JavaResult(bestPath, bestVersion, tried)
                : new JavaResult(null, null, tried);
    }
}
