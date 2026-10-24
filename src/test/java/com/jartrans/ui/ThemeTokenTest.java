package com.jartrans.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * 主题一致性守护：
 * 1）两套调色板（light.css / dark.css）的令牌集合必须完全一致；
 * 2）类状态令牌（state_*）在 Theme 调色板与两份 CSS 之间同步；
 * 3）结构文件 base.css 不得含颜色字面量，且引用的每个 -jr-* 令牌都必须在两套调色板中定义。
 */
class ThemeTokenTest {

    private static final String[] STATE_KEYS = {"todo", "doing", "done", "ignore", "empty"};

    /** 调色板声明：-jr-name: value; */
    private static final Pattern DECL = Pattern.compile("^\\s*(-jr-[a-z0-9-]+)\\s*:", Pattern.MULTILINE);
    /** 任何 -jr-* 令牌引用（含声明处）。 */
    private static final Pattern TOKEN = Pattern.compile("-jr-[a-z0-9-]+");
    /** 颜色字面量：#rgb/#rrggbb/(rgba(...)。 */
    private static final Pattern COLOR_LITERAL = Pattern.compile("#[0-9a-fA-F]{3,8}\\b|\\brgba?\\(");

    private static String css(String name) throws IOException {
        try (InputStream in = Theme.class.getResourceAsStream("/com/jartrans/ui/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> declarations(String css) {
        Set<String> tokens = new LinkedHashSet<>();
        Matcher m = DECL.matcher(css);
        while (m.find()) {
            tokens.add(m.group(1));
        }
        return tokens;
    }

    @Test
    void lightAndDarkPalettesHaveIdenticalKeySets() throws IOException {
        assertEquals(declarations(css("light.css")), declarations(css("dark.css")), "两套调色板的令牌集合必须一致（新增令牌时两边同步）");
    }

    @Test
    void stateTokensPresentInBothPalettesAndCss() throws IOException {
        String lightCss = css("light.css");
        String darkCss = css("dark.css");
        for (String key : STATE_KEYS) {
            assertTrue(Theme.LIGHT.containsKey("state_" + key), "LIGHT 调色板缺 state_" + key);
            assertTrue(Theme.DARK.containsKey("state_" + key), "DARK 调色板缺 state_" + key);
            String var = "-jr-state-" + key + ":";
            assertTrue(lightCss.contains(var), "light.css 缺 " + var);
            assertTrue(darkCss.contains(var), "dark.css 缺 " + var);
        }
    }

    @Test
    void statusAndSyntaxColorKeysExistInBothPalettes() {
        String[] statusKeys = {"status_untranslated", "status_translated", "status_auto", "status_internal"};
        String[] syntaxKeys = {
            "syntax_kw", "syntax_str", "syntax_cmt", "syntax_num", "syntax_hl", "syntax_cur", "syntax_banner"
        };
        for (String key : statusKeys) {
            assertTrue(Theme.LIGHT.containsKey(key) && Theme.DARK.containsKey(key), "调色板缺 " + key);
        }
        for (String key : syntaxKeys) {
            assertTrue(Theme.LIGHT.containsKey(key) && Theme.DARK.containsKey(key), "调色板缺 " + key);
        }
    }

    @Test
    void baseCssHasNoColorLiteralsAndReferencesOnlyDefinedTokens() throws IOException {
        String base = css("base.css");
        assertFalse(COLOR_LITERAL.matcher(base).find(), "base.css 不得出现颜色字面量（颜色只能来自 light/dark 调色板令牌）");

        Set<String> defined = declarations(css("light.css"));
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = TOKEN.matcher(base);
        while (m.find()) {
            if (!defined.contains(m.group())) {
                missing.add(m.group());
            }
        }
        assertTrue(missing.isEmpty(), "base.css 引用了未定义的令牌：" + missing);
    }
}
