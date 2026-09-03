package com.jartrans.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 撤销/重做历史栈的纯逻辑：压栈、容量上限、撤销/重做之间搬运。 */
class EditHistoryTest {

    @Test
    void undoRedoMovesEntriesAcrossStacks() {
        EditHistory history = new EditHistory();
        EditHistory.Hist a = new EditHistory.Hist("trans", "c", "o", "x", "y");
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());

        history.push(a);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());

        EditHistory.Hist taken = history.takeUndo();
        assertEquals(a, taken);
        assertFalse(history.canUndo());
        assertTrue(history.canRedo());

        EditHistory.Hist back = history.takeRedo();
        assertEquals(a, back);
        assertTrue(history.canUndo());
        assertFalse(history.canRedo());
    }

    @Test
    void newPushClearsRedoStack() {
        EditHistory history = new EditHistory();
        EditHistory.Hist a = new EditHistory.Hist("trans", "c", "o", "x", "y");
        EditHistory.Hist b = new EditHistory.Hist("skip", null, "s", "0", "1");
        history.push(a);
        history.takeUndo();
        assertTrue(history.canRedo());

        history.push(b); // 新操作清空重做栈
        assertFalse(history.canRedo());
        assertTrue(history.canUndo());
    }

    @Test
    void undoIsLifoAndKeepsLatestTwoHundred() {
        EditHistory history = new EditHistory();
        for (int i = 0; i < 205; i++) {
            history.push(new EditHistory.Hist("trans", "c", "o" + i, "", "v" + i));
        }
        // 最旧 5 条（o0..o4）被淘汰，剩余 200 条：LIFO 依次弹出，第 200 次后栈空
        EditHistory.Hist first = null;
        EditHistory.Hist last = null;
        for (int i = 0; i < 200; i++) {
            assertTrue(history.canUndo());
            last = history.takeUndo();
            if (i == 0) {
                first = last;
            }
        }
        assertFalse(history.canUndo());
        assertEquals("o204", first.orig(), "最新入栈的应最先撤销");
        assertEquals("o5", last.orig(), "最旧的保留项应为 o5");
    }

    @Test
    void autoSentinelConstant() {
        assertEquals("*auto", EditHistory.AUTO);
    }
}
