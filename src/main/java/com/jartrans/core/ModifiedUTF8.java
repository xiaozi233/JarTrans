package com.jartrans.core;

/**
 * Java class 文件专用的 Modified UTF-8 编解码。
 *
 * 与标准 UTF-8 的差异：
 * - U+0000 编码为两字节 C0 80（标准是单字节 00）；
 * - BMP 之外的字符按 UTF-16 代理对拆开，每个代理单独编成三字节（即 CESU-8）；
 * - 编码结果最长 6 字节/字符，条目总长度用 u2 前缀存放下。
 */
public final class ModifiedUTF8 {

    private static final int SURROGATE_HI_MIN = 0xD800;
    private static final int SURROGATE_HI_MAX = 0xDBFF;
    private static final int SURROGATE_LO_MIN = 0xDC00;
    private static final int SURROGATE_LO_MAX = 0xDFFF;

    private ModifiedUTF8() {
    }

    private static int encodeUnit(int cp, byte[] out, int off) {
        if (cp == 0) {
            out[off] = (byte) 0xC0;
            out[off + 1] = (byte) 0x80;
            return 2;
        }
        if (cp < 0x80) {
            out[off] = (byte) cp;
            return 1;
        }
        if (cp < 0x800) {
            out[off] = (byte) (0xC0 | (cp >> 6));
            out[off + 1] = (byte) (0x80 | (cp & 0x3F));
            return 2;
        }
        out[off] = (byte) (0xE0 | (cp >> 12));
        out[off + 1] = (byte) (0x80 | ((cp >> 6) & 0x3F));
        out[off + 2] = (byte) (0x80 | (cp & 0x3F));
        return 3;
    }

    /** str -&gt; Modified UTF-8 字节。孤代理按单个码元编码（与 JVM 行为一致）。 */
    public static byte[] encode(String text) {
        int max = text.length() * 3;
        byte[] out = new byte[max];
        int n = 0;
        for (int i = 0; i < text.length(); i++) {
            int cp = text.charAt(i);
            if (cp >= 0x10000) {
                cp -= 0x10000;
                int hi = SURROGATE_HI_MIN + (cp >> 10);
                int lo = SURROGATE_LO_MIN + (cp & 0x3FF);
                n += encodeUnit(hi, out, n);
                n += encodeUnit(lo, out, n);
            } else {
                n += encodeUnit(cp, out, n);
            }
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Modified UTF-8 字节 -&gt; str。非法序列替换为 U+FFFD，不抛异常。 */
    public static String decode(byte[] data) {
        int[] units = new int[data.length];
        int m = 0;
        int i = 0;
        int len = data.length;
        while (i < len) {
            int b0 = data[i] & 0xFF;
            if (b0 < 0x80) {
                units[m++] = b0;
                i += 1;
            } else if ((b0 & 0xE0) == 0xC0) {
                if (i + 1 < len && (data[i + 1] & 0xC0) == 0x80) {
                    units[m++] = ((b0 & 0x1F) << 6) | (data[i + 1] & 0x3F);
                    i += 2;
                } else {
                    units[m++] = 0xFFFD;
                    i += 1;
                }
            } else if ((b0 & 0xF0) == 0xE0) {
                if (i + 2 < len && (data[i + 1] & 0xC0) == 0x80 && (data[i + 2] & 0xC0) == 0x80) {
                    units[m++] = ((b0 & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F);
                    i += 3;
                } else {
                    units[m++] = 0xFFFD;
                    i += 1;
                }
            } else if ((b0 & 0xF8) == 0xF0) {
                // 标准 4 字节序列（个别工具会写出），容错解析
                if (i + 3 < len && (data[i + 1] & 0xC0) == 0x80
                        && (data[i + 2] & 0xC0) == 0x80 && (data[i + 3] & 0xC0) == 0x80) {
                    int cp = ((b0 & 0x07) << 18) | ((data[i + 1] & 0x3F) << 12)
                            | ((data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F);
                    units[m++] = cp;
                    i += 4;
                } else {
                    units[m++] = 0xFFFD;
                    i += 1;
                }
            } else {
                units[m++] = 0xFFFD;
                i += 1;
            }
        }

        // 组合代理对；孤代理替换为 U+FFFD（避免生成无法显示/序列化的字符串）
        StringBuilder out = new StringBuilder(m);
        int j = 0;
        while (j < m) {
            int u = units[j];
            if (u >= SURROGATE_HI_MIN && u <= SURROGATE_HI_MAX && j + 1 < m
                    && units[j + 1] >= SURROGATE_LO_MIN && units[j + 1] <= SURROGATE_LO_MAX) {
                int cp = 0x10000 + ((u - SURROGATE_HI_MIN) << 10) + (units[j + 1] - SURROGATE_LO_MIN);
                out.appendCodePoint(cp);
                j += 2;
            } else if (u >= SURROGATE_HI_MIN && u <= SURROGATE_LO_MAX) {
                out.append('\uFFFD');
                j += 1;
            } else {
                out.appendCodePoint(u);
                j += 1;
            }
        }
        return out.toString();
    }
}
