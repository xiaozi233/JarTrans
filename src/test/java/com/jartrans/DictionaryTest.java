package com.jartrans;

import com.jartrans.core.Dictionary;
import com.jartrans.core.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 词典 JSON 的读写语义：加载丢弃空值、save 按码点排序、增删查与搜索大小写不敏感。 */
class DictionaryTest {

    @TempDir
    Path tmp;

    private Path newFile() {
        return tmp.resolve("dict" + System.nanoTime() + ".json");
    }

    @Test
    void loadDropsEmptyValues() throws IOException {
        Path file = newFile();
        Files.writeString(file, "{\"a\":\"甲\",\"b\":\"\",\"c\":\"丙\"}");
        Dictionary dict = new Dictionary(file);
        assertEquals(2, dict.size());
        assertEquals("甲", dict.get("a"));
        assertNull(dict.get("b"));
    }

    @Test
    void addIgnoresEmptyOrSelfTranslations() throws IOException {
        Dictionary dict = new Dictionary(newFile());
        dict.add("x", "译文");
        dict.add("x", "");     // 空译文忽略
        dict.add("x", "x");    // 与原文相同的译文忽略
        dict.add(null, "y");
        dict.add("z", null);
        assertEquals(1, dict.size());
        assertEquals("译文", dict.get("x"));
        dict.remove("x");
        assertNull(dict.get("x"));
    }

    @Test
    void saveWritesKeysInCodePointOrder() throws IOException {
        Path file = newFile();
        Dictionary dict = new Dictionary(file);
        dict.add("z", "1");
        dict.add("a", "2");
        dict.add("中", "3");   // 码点 0x4E2D > 0x7A
        dict.save();

        Map<String, Object> parsed = Json.object(Json.readFile(file));
        List<String> keys = List.copyOf(parsed.keySet());
        assertEquals(List.of("a", "z", "中"), keys, "保存应按码点升序");
    }

    @Test
    void searchIsCaseInsensitiveOnBothSides() throws IOException {
        Path file = newFile();
        Dictionary dict = new Dictionary(file);
        dict.add("Hello World", "你好世界");
        dict.add("foo", "BarText");

        Map<String, String> hits = dict.search("WORLD");
        assertEquals(1, hits.size());
        assertTrue(hits.containsKey("Hello World"));

        Map<String, String> hits2 = dict.search("bartext");
        assertEquals(1, hits2.size());
        assertEquals("BarText", hits2.get("foo"));

        Map<String, String> none = dict.search("不存在");
        assertTrue(none.isEmpty());
    }

    @Test
    void entriesReturnsCopy() throws IOException {
        Dictionary dict = new Dictionary(newFile());
        dict.add("a", "1");
        Map<String, String> copy = dict.entries();
        copy.put("b", "2");
        assertFalse(dict.entries().containsKey("b"), "entries() 应返回副本");
    }
}
