package com.jartrans;

import com.jartrans.core.ModifiedUTF8;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** 对应 selftest.py 的样本 class 构造工具。 */
final class TestClasses {

    private TestClasses() {
    }

    static byte[] u2(int v) {
        return new byte[]{(byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    static byte[] concat(byte[]... parts) {
        int len = 0;
        for (byte[] p : parts) {
            len += p.length;
        }
        byte[] out = new byte[len];
        int off = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, out, off, p.length);
            off += p.length;
        }
        return out;
    }

    static byte[] i4(long v) {
        return new byte[]{
                (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF),
                (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    static byte[] i8(long v) {
        return new byte[]{
                (byte) ((v >>> 56) & 0xFF), (byte) ((v >>> 48) & 0xFF),
                (byte) ((v >>> 40) & 0xFF), (byte) ((v >>> 32) & 0xFF),
                (byte) ((v >>> 24) & 0xFF), (byte) ((v >>> 16) & 0xFF),
                (byte) ((v >>> 8) & 0xFF), (byte) (v & 0xFF)};
    }

    static byte[] utf8Entry(String s) {
        byte[] raw = ModifiedUTF8.encode(s);
        return concat(new byte[]{1}, u2(raw.length), raw);
    }

    /**
     * 手工构造一个最小但合法的 class 文件。
     * 常量池：1 Utf8类名 / 2 Class / 3 Utf8字面量1 / 4 String / 5 Utf8"Code"
     * 6-7 Long（占两槽）/ 8 Utf8字面量2 / 9 String
     */
    static byte[] buildSampleClass() {
        byte[] pool = concat(
                utf8Entry("demo/Test"),                       // 1
                new byte[]{7}, u2(1),                         // 2 Class -> 1
                utf8Entry("Hello, 世界!"),                     // 3
                new byte[]{8}, u2(3),                         // 4 String -> 3
                utf8Entry("Code"),                            // 5
                new byte[]{5}, i8(1234567890123456789L),      // 6-7 Long
                utf8Entry("Press \0 Start"),                  // 8
                new byte[]{8}, u2(8));                        // 9 String -> 8
        byte[] tail = {0x00, 0x21, 0x00, 0x02, 0x00, 0x03, 0x00, 0x00,
                (byte) 0xde, (byte) 0xad, (byte) 0xbe, (byte) 0xef};
        return concat(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe},
                u2(0), u2(52), u2(10), pool, tail);
    }

    /** 带真实方法表和 Code 属性的 class：foo() 含 ldc 引用字符串；bar() 含 tableswitch。 */
    static byte[] buildBytecodeClass() {
        byte[] pool = concat(
                utf8Entry("demo/Test"),                       // 1
                new byte[]{7}, u2(1),                         // 2 Class
                utf8Entry("Hello, 世界!"),                     // 3
                new byte[]{8}, u2(3),                         // 4 String
                utf8Entry("foo"),                             // 5
                utf8Entry("()V"),                             // 6
                utf8Entry("Code"),                            // 7
                utf8Entry("bar"),                             // 8
                new byte[]{5}, i8(42));                       // 9-10 Long

        byte[] fooCode = {0x12, 0x04, (byte) 0xb1};           // ldc #4; return
        byte[] barCode = concat(
                new byte[]{(byte) 0xaa, 0x00, 0x00, 0x00},    // tableswitch（填充 3 字节）
                i4(0), i4(1), i4(3),                          // default, lo, hi
                i4(8), i4(16), i4(24),                        // 3 个跳转目标
                new byte[]{(byte) 0xb1});                     // return
        byte[] fooAttr = codeAttr(u2(7), fooCode);
        byte[] barAttr = codeAttr(u2(7), barCode);
        byte[] methods = concat(
                u2(2),
                u2(0x0001), u2(5), u2(6), u2(1), fooAttr,
                u2(0x0001), u2(8), u2(6), u2(1), barAttr);
        byte[] tail = concat(u2(0x0021), u2(2), u2(0), u2(0), u2(0), methods, u2(0));
        return concat(new byte[]{(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe},
                u2(0), u2(52), u2(11), pool, tail);
    }

    private static byte[] codeAttr(byte[] nameIndex, byte[] code) {
        byte[] payload = concat(
                u2(2), u2(1), i4(code.length), code, u2(0), u2(0)); // 异常表 0 + 内部属性 0
        return concat(nameIndex, i4(payload.length), payload);
    }

    /** 写一个合成 jar：manifest + 假签名 + class + 资源。 */
    static Path writeSampleJar(Path dir, String filename, boolean withExtras) throws IOException {
        Files.createDirectories(dir);
        Path jarPath = dir.resolve(filename);
        java.util.Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
        if (withExtras) {
            entries.put("META-INF/MANIFEST.MF",
                    "Manifest-Version: 1.0\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            entries.put("META-INF/sample.sf", "fake signature".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        entries.put("demo/Test.class", buildSampleClass());
        if (withExtras) {
            entries.put("assets/lang.txt", "hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        try (var zf = new java.util.zip.ZipOutputStream(Files.newOutputStream(jarPath),
                java.nio.charset.StandardCharsets.UTF_8)) {
            for (var e : entries.entrySet()) {
                zf.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zf.write(e.getValue());
                zf.closeEntry();
            }
        }
        return jarPath;
    }
}
