package com.jartrans.ui;

import com.jartrans.core.Project;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨层状态键一致性守护：类状态键（empty/todo/doing/done/ignore）与条目状态键
 * （untranslated/translated/auto/internal/skipped）必须贯穿以下各处并保持一致——
 * Project 状态常量、MainApp 文案与筛选定义、EditorPane 状态文案、Theme 两份调色板、
 * 以及两份 CSS 的 -jr-state-* / .cell-state-* 规则。改状态集合时若漏改任一处即报警。
 */
class StateKeyConsistencyTest {

    private static final List<String> CLASS_STATES =
            List.of("empty", "todo", "doing", "done", "ignore");
    private static final Set<String> STATUS_KEYS = Set.of(
            "untranslated", "translated", "auto", "internal", "skipped");

    private static String css(String name) throws IOException {
        try (InputStream in = Theme.class.getResourceAsStream("/com/jartrans/ui/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void classStateConstantsMatchUiLabelsAndFilters() {
        assertEquals(CLASS_STATES, Project.CLASS_STATES, "Project.CLASS_STATES 顺序被改动");
        assertEquals(Set.copyOf(CLASS_STATES), MainApp.STATE_LABEL.keySet(),
                "MainApp.STATE_LABEL 必须恰好覆盖全部类状态");
        for (String[] f : MainApp.STATE_FILTERS) {
            assertTrue(f[0].equals("all") || CLASS_STATES.contains(f[0]),
                    "STATE_FILTERS 出现未知状态键: " + f[0]);
        }
    }

    @Test
    void rowStatusKeysCoveredByUiTextsAndPalettes() {
        assertEquals(STATUS_KEYS, EditorPane.STATUS_TEXT.keySet(),
                "EditorPane.STATUS_TEXT 必须覆盖全部条目状态");
        for (String k : STATUS_KEYS) {
            if (!k.equals("skipped")) { // skipped 走 fg 回退色，不要求 status_ 调色键
                assertTrue(Theme.LIGHT.containsKey("status_" + k)
                                && Theme.DARK.containsKey("status_" + k),
                        "调色板缺 status_" + k);
            }
        }
    }

    @Test
    void stateTokensAndCssClassesStayInSync() throws IOException {
        String lightCss = css("light.css");
        String darkCss = css("dark.css");
        for (String k : CLASS_STATES) {
            assertTrue(Theme.LIGHT.containsKey("state_" + k)
                            && Theme.DARK.containsKey("state_" + k),
                    "调色板缺 state_" + k);
            assertTrue(lightCss.contains("-jr-state-" + k + ":")
                            && lightCss.contains("cell-state-" + k),
                    "light.css 缺 -jr-state-" + k + " 或 .cell-state-" + k);
            assertTrue(darkCss.contains("-jr-state-" + k + ":")
                            && darkCss.contains("cell-state-" + k),
                    "dark.css 缺 -jr-state-" + k + " 或 .cell-state-" + k);
        }
    }
}
