package com.jartrans.core;

/** 一个常量池条目。raw 是 tag 之后的原始字节。 */
public final class CpEntry {

    public final int index;
    public final int tag;
    public final byte[] raw;

    private String textCache;
    private boolean textDecoded;

    public CpEntry(int index, int tag, byte[] raw) {
        this.index = index;
        this.tag = tag;
        this.raw = raw;
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
