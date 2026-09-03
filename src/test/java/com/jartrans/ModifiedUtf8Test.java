package com.jartrans;

import com.jartrans.core.ModifiedUTF8;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 对应 selftest.py 的 test_mutf8。 */
class ModifiedUtf8Test {

    @Test
    void roundTrip() {
        String[] cases = {"", "abc", "你好，世界！", "\u0000", "😀", "a\ud83d\ude00b",
                "mix中en文", "换行\n制表\t"};
        for (String s : cases) {
            assertEquals(s, ModifiedUTF8.decode(ModifiedUTF8.encode(s)), "round trip: " + s);
        }
    }

    @Test
    void zeroEncoding() {
        assertArrayEquals(new byte[]{(byte) 0xC0, (byte) 0x80},
                ModifiedUTF8.encode("\u0000"));
        assertEquals("\u0000", ModifiedUTF8.decode(new byte[]{(byte) 0xC0, (byte) 0x80}));
    }

    @Test
    void emojiIsSixBytesCesu8() {
        assertEquals(6, ModifiedUTF8.encode("😀").length);
    }

    @Test
    void invalidBytesBecomeReplacementChar() {
        assertEquals("\uFFFD", ModifiedUTF8.decode(new byte[]{(byte) 0xFF}));
        // 截断序列容错，不抛异常
        assertEquals("\uFFFD\uFFFD", ModifiedUTF8.decode(
                new byte[]{(byte) 0xE4, (byte) 0xBD}));
    }

    @Test
    void asciiPassthrough() {
        assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII),
                ModifiedUTF8.encode("abc"));
    }
}
