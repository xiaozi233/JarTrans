package com.jartrans.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主题一致性守护：类状态令牌（state_*）在 Theme 调色板（LIGHT/DARK 两组键集一致）
 * 与两份 CSS 的 -jr-state-* 变量之间必须同步；缺失即报警，防改主题漏同步。
 */
class ThemeTokenTest {

    private static final String[] STATE_KEYS = {"todo", "doing", "done", "ignore", "empty"};

    private static String css(String name) throws IOException {
        try (InputStream in = Theme.class.getResourceAsStream("/com/jartrans/ui/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void lightAndDarkPalettesHaveIdenticalKeySets() {
        assertEquals(Theme.LIGHT.keySet(), Theme.DARK.keySet(),
                "LIGHT 与 DARK 调色板的键集必须一致");
    }

    @Test
    void stateTokensPresentInBothPalettesAndCss() throws IOException {
        String lightCss = css("light.css");
        String darkCss = css("dark.css");
        for (String key : STATE_KEYS) {
            assertTrue(Theme.LIGHT.containsKey("state_" + key),
                    "LIGHT 调色板缺 state_" + key);
            assertTrue(Theme.DARK.containsKey("state_" + key),
                    "DARK 调色板缺 state_" + key);
            String var = "-jr-state-" + key + ":";
            assertTrue(lightCss.contains(var), "light.css 缺 " + var);
            assertTrue(darkCss.contains(var), "dark.css 缺 " + var);
        }
    }

    @Test
    void statusAndSyntaxColorKeysExistInBothPalettes() {
        String[] statusKeys = {"status_untranslated", "status_translated", "status_auto",
                "status_internal"};
        String[] syntaxKeys = {"syntax_kw", "syntax_str", "syntax_cmt", "syntax_num",
                "syntax_hl", "syntax_cur", "syntax_banner"};
        for (String key : statusKeys) {
            assertTrue(Theme.LIGHT.containsKey(key) && Theme.DARK.containsKey(key),
                    "调色板缺 " + key);
        }
        for (String key : syntaxKeys) {
            assertTrue(Theme.LIGHT.containsKey(key) && Theme.DARK.containsKey(key),
                    "调色板缺 " + key);
        }
    }
}
