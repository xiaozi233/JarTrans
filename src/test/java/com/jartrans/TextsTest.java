package com.jartrans;

import com.jartrans.ui.Texts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 展示/源码转义工具：displayText、javaEscape/javaUnescape 往返一致。 */
class TextsTest {

    @Test
    void displayTextShowsNewlinesAndTabsInline() {
        assertEquals("a\\\\b", Texts.displayText("a\\b"));
        assertEquals("a\\nb\\tc", Texts.displayText("a\nb\tc"));
    }

    @Test
    void javaEscapeCoversQuoteAndControlChars() {
        assertEquals("\\\"q\\\"", Texts.javaEscape("\"q\""));
        assertEquals("a\\nb\\rc\\td", Texts.javaEscape("a\nb\rc\td"));
    }

    @Test
    void javaUnescapeRoundTripsEscapes() {
        String src = "say \"hi\"\nline2\ttab\\slash\u4e2d\u6587";
        String escaped = Texts.javaEscape(src);
        assertEquals(src, Texts.javaUnescape(escaped));
    }

    @Test
    void javaUnescapeHandlesUnicodeEscape() {
        // 源码与注释中都不能出现字面"反斜杠 + u"组合（会被 javac 当 Unicode 转义预处理），用拼接构造
        String backslashU = "\\" + "u";
        assertEquals("中", Texts.javaUnescape(backslashU + "4e2d"));
        // 非法/不完整的该序列保留 'u' 原样
        assertEquals("u", Texts.javaUnescape(backslashU));
        // 未知转义去掉反斜杠（沿用实现语义）
        assertEquals("x", Texts.javaUnescape("\\x"));
    }
}
