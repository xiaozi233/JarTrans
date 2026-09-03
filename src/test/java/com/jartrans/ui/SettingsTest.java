package com.jartrans.ui;

import com.jartrans.core.Settings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** settings.json 载入合并：默认值、覆盖值、多余键（如 key_*）三者共存且跨保存/重载一致。 */
class SettingsTest {

    @TempDir
    Path tmp;

    @Test
    void loadMergesDefaultsOverrideAndExtraKeys() throws Exception {
        Path file = tmp.resolve("settings.json");
        Files.writeString(file, "{\n"
                + "  \"theme\": \"dark\",\n"
                + "  \"key_find\": \"Ctrl+Alt+K\",\n"
                + "  \"future_key\": \"hello\"\n"
                + "}\n");
        Settings settings = new Settings(file);

        // 覆盖默认值
        assertEquals("dark", settings.getString("theme"));
        // 多余键（自定义快捷键等）保留
        assertEquals("Ctrl+Alt+K", settings.getString("key_find"));
        assertEquals("hello", settings.getString("future_key"));
        // 缺省键仍取默认值
        assertEquals("", settings.getString("java_path"));
        assertTrue(settings.getBool("save_to_dict"));
    }

    @Test
    void setPersistsAndReloads() throws Exception {
        Path file = tmp.resolve("settings2.json");
        Settings settings = new Settings(file);
        settings.set("theme", "light");
        settings.set("key_save", "Ctrl+Shift+S");

        Settings reloaded = new Settings(file);
        assertEquals("light", reloaded.getString("theme"));
        assertEquals("Ctrl+Shift+S", reloaded.getString("key_save"));
        assertFalse(reloaded.getBool("only_untranslated")); // 默认 false
    }

    @Test
    void getBoolParsesBooleanAndTruthyStrings() throws Exception {
        Settings settings = new Settings(tmp.resolve("settings3.json"));
        // Boolean 与缺省值
        assertTrue(settings.getBool("save_to_dict"));        // 默认 TRUE
        assertFalse(settings.getBool("only_untranslated"));  // 默认 FALSE
        // 字符串真值表
        settings.setQuiet("only_untranslated", "yes");
        assertTrue(settings.getBool("only_untranslated"));
        settings.setQuiet("only_untranslated", "1");
        assertTrue(settings.getBool("only_untranslated"));
        settings.setQuiet("only_untranslated", "TRUE");
        assertTrue(settings.getBool("only_untranslated"));
        settings.setQuiet("only_untranslated", "on");
        assertTrue(settings.getBool("only_untranslated"));
        settings.setQuiet("only_untranslated", "0");
        assertFalse(settings.getBool("only_untranslated"));
        settings.setQuiet("only_untranslated", "no");
        assertFalse(settings.getBool("only_untranslated"));
    }
}
