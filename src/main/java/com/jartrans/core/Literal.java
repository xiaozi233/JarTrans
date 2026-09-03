package com.jartrans.core;

/** 一个字符串字面量（CONSTANT_String 条目）。 */
public final class Literal {

    public final int stringIndex;
    public final int utf8Index;
    public final String text; // 损坏引用时为 null

    public Literal(int stringIndex, int utf8Index, String text) {
        this.stringIndex = stringIndex;
        this.utf8Index = utf8Index;
        this.text = text;
    }

    @Override
    public String toString() {
        return "<Literal #" + stringIndex + " -> utf8 #" + utf8Index + " " + text + ">";
    }
}
