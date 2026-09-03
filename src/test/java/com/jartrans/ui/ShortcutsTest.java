package com.jartrans.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 快捷键文本 ↔ 组合键解析与默认定义（纯逻辑，无需 JavaFX toolkit）。 */
class ShortcutsTest {

    @Test
    void normalizeUnifiesModifiersAndCase() {
        assertEquals("Ctrl+Alt+F", Shortcuts.normalize(" ctrl + alt + f "));
        assertEquals("Ctrl+Shift+Z", Shortcuts.normalize("control+shift+z"));
        assertEquals("Ctrl+,", Shortcuts.normalize("ctrl+,"));
        assertEquals("", Shortcuts.normalize(null));
    }

    @Test
    void parseAcceptsLettersDigitsAndPunctuation() {
        assertNotNull(Shortcuts.parse("Ctrl+Alt+F"));
        assertNotNull(Shortcuts.parse("Ctrl+,"));
        assertNotNull(Shortcuts.parse("Ctrl+Shift+Z"));
        assertNotNull(Shortcuts.parse("Ctrl+2"));
        // 无修饰键（除 F 键外）或非法组合应拒绝
        assertNull(Shortcuts.parse(""));
        assertNull(Shortcuts.parse("Ctrl+"));
        assertNull(Shortcuts.parse("Alt+Bogus"));
    }

    @Test
    void parsedKeyAndModifiersAreExact() {
        KeyCodeCombination combo = Shortcuts.parse("Ctrl+Alt+F");
        assertEquals(new KeyCodeCombination(KeyCode.F,
                KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN), combo);
    }

    @Test
    void defaultsDeclaredForAllActions() {
        assertEquals("Ctrl+F", Shortcuts.defaultFor("find"));
        assertEquals("Ctrl+Alt+F", Shortcuts.defaultFor("gsearch"));
        assertEquals("", Shortcuts.defaultFor("unknown-id"));
        // DEFS 里每个 id 都有默认值，且与定义表一一对应
        for (String[] d : Shortcuts.DEFS) {
            assertEquals(d[2], Shortcuts.defaultFor(d[0]));
        }
    }
}
