package com.jartrans;

import com.jartrans.core.json.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON 解析/写出：BOM 容错、键序保持、整数 Double 不带小数点、非法输入报错、码点排序。 */
class JsonTest {

    private static Map<String, Object> obj() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("b", Boolean.TRUE);
        m.put("a", 1L);
        m.put("unicode", "你好😀");
        m.put("nested", java.util.Arrays.asList("x", null, 3L));
        return m;
    }

    @Test
    void writeKeepsInsertionOrderAndRoundTrips() throws IOException {
        String text = Json.write(obj());
        assertTrue(text.indexOf("\"b\"") < text.indexOf("\"a\""), "写出应保持插入键序");

        Object parsed = Json.parse(text);
        Map<String, Object> map = Json.object(parsed);
        assertEquals(Boolean.TRUE, map.get("b"));
        assertEquals(1L, map.get("a"));
        assertEquals("你好😀", map.get("unicode"));
        List<?> nested = (List<?>) map.get("nested");
        assertEquals(java.util.Arrays.asList("x", null, 3L), nested);
    }

    @Test
    void integralDoublesLoseDecimalPointButOthersKeepIt() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("i", 42.0);
        m.put("f", 3.5);
        String text = Json.write(m);
        assertTrue(text.contains("\"i\": 42"));
        assertTrue(!text.contains("42.0"), "整数值 double 不应输出小数点");
        assertTrue(text.contains("\"f\": 3.5"));
    }

    @Test
    void nanAndInfinitySerializeAsNull() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nan", Double.NaN);
        m.put("inf", Double.POSITIVE_INFINITY);
        String text = Json.write(m);
        assertTrue(text.contains("\"nan\": null"));
        assertTrue(text.contains("\"inf\": null"));
    }

    @Test
    void bomAndSurroundingWhitespaceTolerated() throws IOException {
        assertEquals("a", Json.parse("\uFEFF \"a\" "));
    }

    @Test
    void malformedInputThrows() {
        assertThrows(IOException.class, () -> Json.parse("{"));
        assertThrows(IOException.class, () -> Json.parse("\"abc"));
        assertThrows(IOException.class, () -> Json.parse("{\"a\":1} extra"));
        assertThrows(IOException.class, () -> Json.parse("truee"));
    }

    @Test
    void codePointOrderComparesByCodepoints() {
        // 码点序：a(0x61) < 中(0x4E2D) < 😀(U+1F600)
        assertTrue(Json.CODE_POINT_ORDER.compare("a", "中") < 0);
        assertTrue(Json.CODE_POINT_ORDER.compare("中", "😀") < 0);
        // 前缀较短者在前
        assertTrue(Json.CODE_POINT_ORDER.compare("ab", "abc") < 0);
        assertEquals(0, Json.CODE_POINT_ORDER.compare("ab", "ab"));
        // 代理对按单个码点参与比较（≠ UTF-16 逐 char 比较）
        String emoji = "\uD83D\uDE00";
        assertTrue(Json.CODE_POINT_ORDER.compare("a", emoji) < 0);
        assertEquals(0, Json.CODE_POINT_ORDER.compare(emoji, "\uD83D\uDE00"));
    }

    @Test
    void nullAndMissingObjectHelpers() throws IOException {
        assertNull(Json.object("not-a-map"));
        Map<String, Object> map = Json.object(Json.parse("{\"x\":1}"));
        assertEquals(1L, map.get("x"));
    }
}
