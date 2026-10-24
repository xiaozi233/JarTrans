package com.jartrans.core.jar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * jar（zip）文件读取：保持条目原顺序，同名条目（极罕见）保留第一个，跳过目录。
 */
public final class JarReader {

    /** jar 读取错误。 */
    public static class JarFileException extends Exception {

        private static final long serialVersionUID = 1L;

        public JarFileException(String message) {
            super(message);
        }

        public JarFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** jar 内条目：名称 + 时间 + 压缩方式 + 原始字节（字段包内可见，不对外暴露快照内部）。 */
    public static final class EntryInfo {
        final String name;
        final long timeMillis;
        final int method;
        final byte[] data;

        EntryInfo(String name, long timeMillis, int method, byte[] data) {
            this.name = name;
            this.timeMillis = timeMillis;
            this.method = method;
            this.data = data;
        }

        EntryInfo copy() {
            return new EntryInfo(name, timeMillis, method, data.clone());
        }

        public String name() {
            return name;
        }

        public long timeMillis() {
            return timeMillis;
        }

        public int method() {
            return method;
        }

        /** 条目解压后的原始字节（快照副本；不得修改）。 */
        public byte[] data() {
            return data;
        }
    }

    private final Path path;
    private final List<EntryInfo> infos = new ArrayList<>(); // 保持原顺序
    private final Map<String, EntryInfo> data = new LinkedHashMap<>(); // 文件名 -&gt; 条目

    public JarReader(Path path) throws JarFileException {
        if (path == null) {
            throw new JarFileException("jar 路径不能为空");
        }
        this.path = path;
        try (ZipFile zf = new ZipFile(path.toFile())) {
            var entries = zf.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) {
                    continue;
                }
                if (data.containsKey(entry.getName())) { // 同名条目（极罕见），保留第一个
                    continue;
                }
                byte[] bytes;
                try (var input = zf.getInputStream(entry)) {
                    bytes = input.readAllBytes();
                }
                EntryInfo info = new EntryInfo(entry.getName(), entry.getTime(), entry.getMethod(), bytes);
                infos.add(info);
                data.put(info.name, info);
            }
        } catch (IOException exc) {
            throw new JarFileException("无法读取 jar 文件：" + exc.getMessage(), exc);
        }
    }

    public Path path() {
        return path;
    }

    public String name() {
        return path.getFileName() == null ? path.toString() : path.getFileName().toString();
    }

    public List<EntryInfo> infos() {
        // The reader is a snapshot. Do not let callers reorder entries or mutate payloads.
        return infos.stream().map(EntryInfo::copy).toList();
    }

    Iterable<EntryInfo> entriesForPacking() {
        return infos;
    }

    public byte[] readEntry(String name) {
        EntryInfo info = data.get(name);
        return info == null ? null : info.data.clone();
    }

    public List<String> classNames() {
        List<String> out = new ArrayList<>();
        for (EntryInfo info : infos) {
            if (info.name.endsWith(".class")) {
                out.add(info.name);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 读取类字节用于 ClassFile.parse（不拷贝）。
     * parse 会取得数组所有权；jar 快照持有原缓冲，本方法约定调用方只读。
     */
    public byte[] readClass(String name) {
        EntryInfo info = data.get(name);
        return info == null ? null : info.data;
    }

    /** 判断文件是否为 zip/jar（PK 魔数）。 */
    public static boolean isZipFile(Path path) {
        if (path == null) {
            return false;
        }
        try {
            byte[] head = new byte[4];
            int n;
            try (var in = Files.newInputStream(path)) {
                n = in.readNBytes(head, 0, 4);
            }
            return n == 4
                    && head[0] == 'P'
                    && head[1] == 'K'
                    && ((head[2] == 0x03 && head[3] == 0x04) || (head[2] == 0x05 && head[3] == 0x06));
        } catch (IOException e) {
            return false;
        }
    }
}
