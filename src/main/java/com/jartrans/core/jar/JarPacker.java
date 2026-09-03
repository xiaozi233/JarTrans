package com.jartrans.core.jar;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * jar 重打包：按原条目顺序写回，仅替换被汉化的 .class 字节，其余条目
 * （资源、manifest 等）原字节、原压缩方式照搬；默认移除签名文件
 * （修改 class 后签名必然失效，保留反而会导致加载失败）。
 */
public final class JarPacker {

    private static final Pattern SIGNATURE_NAME =
            Pattern.compile("^META-INF/.*\\.(SF|RSA|DSA)$", Pattern.CASE_INSENSITIVE);

    private JarPacker() {
    }

    /**
     * 写出新 jar。modified: {条目名: 新字节}。
     * 签名条目（META-INF 下 .SF/.RSA/.DSA）在 stripSignature 时被移除。
     */
    public static void save(JarReader src, Path outPath,
                            Map<String, byte[]> modified, boolean stripSignature)
            throws IOException {
        try (ZipOutputStream zf = new ZipOutputStream(
                Files.newOutputStream(outPath), StandardCharsets.UTF_8)) {
            for (JarReader.EntryInfo info : src.infos()) {
                String name = info.name;
                if (stripSignature && SIGNATURE_NAME.matcher(name).matches()) {
                    continue;
                }
                byte[] payload = modified.getOrDefault(name, info.data);
                ZipEntry zi = new ZipEntry(name);
                if (info.timeMillis >= 0) {
                    zi.setTime(info.timeMillis);
                }
                zi.setMethod(info.method);
                zf.putNextEntry(zi);
                zf.write(payload);
                zf.closeEntry();
            }
        }
    }
}
