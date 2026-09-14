package com.jartrans.core.java;

import com.jartrans.core.jar.JarReader;
import com.jartrans.core.json.Json;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

/**
 * 反编译器 jar 下载：GitHub API 查询 + HTTP 分块下载（进度/取消/校验）。
 * 全部为可在后台线程中调用的静态方法。
 */
public final class DecompilerDownloader {

    private static final String UA = "jartrans/1.0";

    private DecompilerDownloader() {
    }

    /** 下载/查询错误。 */
    public static class DownloadException extends Exception {

        public DownloadException(String message) {
            super(message);
        }

        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** release 资产。 */
    public record Asset(String name, long size, String url) {
    }

    public record ReleaseInfo(String tag, List<Asset> assets) {
    }

    /** 查询 GitHub 最新 release 中所有 .jar 资产。 */
    public static ReleaseInfo fetchLatestRelease(String repo, long timeoutSeconds)
            throws DownloadException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.github.com/repos/" + repo + "/releases/latest"))
                .header("User-Agent", UA)
                .header("Accept", "application/vnd.github+json")
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .GET()
                .build();
        String body;
        try {
            HttpResponse<InputStream> resp =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = resp.body()) {
                body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (resp.statusCode() != 200) {
                throw new DownloadException("查询 GitHub 发布信息失败：HTTP " + resp.statusCode());
            }
        } catch (IOException | InterruptedException exc) {
            restoreInterrupt(exc);
            throw new DownloadException("查询 GitHub 发布信息失败：" + exc.getMessage(), exc);
        }

        Object parsed;
        try {
            parsed = Json.parse(body);
        } catch (IOException exc) {
            throw new DownloadException("GitHub 返回内容无法解析：" + exc.getMessage(), exc);
        }
        var data = Json.object(parsed);
        if (data == null) {
            throw new DownloadException("GitHub 返回内容无法解析");
        }
        List<Asset> assets = new ArrayList<>();
        if (data.get("assets") instanceof List<?> assetList) {
            for (Object o : assetList) {
                var a = Json.object(o);
                if (a == null) {
                    continue;
                }
                String name = String.valueOf(a.getOrDefault("name", ""));
                if (!name.toLowerCase().endsWith(".jar")) {
                    continue;
                }
                assets.add(new Asset(name, asLong(a.get("size")),
                        String.valueOf(a.getOrDefault("browser_download_url", ""))));
            }
        }
        if (assets.isEmpty()) {
            throw new DownloadException(repo + " 最新版本中没有 .jar 资产");
        }
        return new ReleaseInfo(String.valueOf(data.getOrDefault("tag_name", "")), assets);
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    /** 捕获块内调用：若由中断引起则恢复线程中断标记。 */
    private static void restoreInterrupt(Exception exc) {
        if (exc instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 下载 url到 dest（目标文件本身，调用方用 .part 临时名）。
     * 校验：Content-Length 与 expected_size 一致（都提供时）+ zip 魔数。
     * progress(done, total) 可为 null；cancelCheck 返回 true 时中止并抛"已取消"。
     */
    public static void download(String url, Path dest,
                                ProgressListener progress,
                                CancelCheck cancelCheck,
                                long timeoutSeconds,
                                Long expectedSize) throws DownloadException {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", UA)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .GET()
                    .build();
        } catch (IllegalArgumentException exc) {
            throw new DownloadException("下载失败：无效的 URL：" + url, exc);
        }
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException | InterruptedException exc) {
            restoreInterrupt(exc);
            throw new DownloadException("下载失败：" + exc.getMessage(), exc);
        }
        try (InputStream in = resp.body()) {
            if (resp.statusCode() != 200) {
                throw new DownloadException("下载失败：HTTP " + resp.statusCode());
            }
            Long total = contentLength(resp);
            if (expectedSize != null && total != null && !total.equals(expectedSize)) {
                throw new DownloadException("服务器文件大小与发布信息不一致，已中止");
            }
            CancelCheck check = cancelCheck != null ? cancelCheck : () -> false;
            byte[] buf = new byte[65536];
            long done = 0;
            try (OutputStream out = Files.newOutputStream(dest)) {
                while (true) {
                    if (check.isCancelled()) {
                        throw new DownloadException("已取消");
                    }
                    int n = in.read(buf);
                    if (n < 0) {
                        break;
                    }
                    out.write(buf, 0, n);
                    done += n;
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                }
            }
        } catch (DownloadException exc) {
            deleteQuietly(dest);
            throw exc;
        } catch (IOException exc) {
            deleteQuietly(dest);
            throw new DownloadException("下载失败：" + exc.getMessage(), exc);
        }

        long size;
        try {
            size = Files.size(dest);
        } catch (IOException exc) {
            throw new DownloadException("下载失败：" + exc.getMessage(), exc);
        }
        if (size == 0) {
            deleteQuietly(dest);
            throw new DownloadException("下载内容为空");
        }
        if (expectedSize != null && size != expectedSize) {
            deleteQuietly(dest);
            throw new DownloadException(
                    "文件大小不一致（期望 " + expectedSize + "，实际 " + size + "），可能不是目标文件");
        }
        byte[] magic = new byte[4];
        try (InputStream in = Files.newInputStream(dest)) {
            int n = in.readNBytes(magic, 0, 4);
            boolean isZip = n == 4 && magic[0] == 'P' && magic[1] == 'K'
                    && magic[2] == 0x03 && magic[3] == 0x04;
            if (!isZip) {
                throw new DownloadException("文件头不是 zip/jar 格式（可能下载到的是网页或错误内容）");
            }
        } catch (IOException exc) {
            throw new DownloadException("下载失败：" + exc.getMessage(), exc);
        }
    }

    private static Long contentLength(HttpResponse<?> resp) {
        OptionalLong value = resp.headers().firstValueAsLong("Content-Length");
        return value.isPresent() ? value.getAsLong() : null;
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 清理失败忽略
        }
    }

    @FunctionalInterface
    public interface ProgressListener {
        void onProgress(long done, Long total);
    }

    @FunctionalInterface
    public interface CancelCheck {
        boolean isCancelled();
    }

    public static boolean isZipFile(Path path) {
        return JarReader.isZipFile(path);
    }

    public static String humanSize(long n) {
        if (n <= 0) {
            return "未知大小";
        }
        double v = n;
        if (v < 1024) {
            return (long) v + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        for (String unit : units) {
            v /= 1024.0;
            if (v < 1024 || unit.equals("GB")) {
                return String.format("%.1f %s", v, unit);
            }
        }
        return String.format("%.1f GB", v);
    }

    /** 把下载完成的 .part 文件转正。 */
    public static void promote(Path part, Path finalPath) throws IOException {
        Files.move(part, finalPath, StandardCopyOption.REPLACE_EXISTING);
    }
}
