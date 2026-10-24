package com.jartrans.core;

/** 一个常量池条目。raw 是 tag 之后的原始字节。 */
public final class CpEntry {

    // 包级可见：core 内的解析/重写热路径直接读；对外经访问器。
    final int index;
    final int tag;
    final byte[] raw;

    private String textCache;
    private boolean textDecoded;

    public CpEntry(int index, int tag, byte[] raw) {
        this.index = index;
        this.tag = tag;
        this.raw = raw;
    }

    public int index() {
        return index;
    }

    public int tag() {
        return tag;
    }

    /** tag 之后的原始字节（只读视图；不得修改）。 */
    public byte[] raw() {
        return raw;
    }

    /** 仅对 Utf8 条目有效。解码结果缓存。 */
    public String text() {
        if (!textDecoded) {
            textCache = ModifiedUTF8.decode(raw);
            textDecoded = true;
        }
        return textCache;
    }
}
