package com.jartrans.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * TranslationStore 的 effective 缓存正确性：所有翻译变更入口都在类内，
 * 缓存必须在每个变更点精确失效（这是 UI 高频读取性能优化的正确性前提）。
 */
class TranslationStoreTest {

    private static final String CLS = "demo/Test.class";

    @Test
    void effectiveSnapshotIsCachedAndReadOnly() {
        TranslationStore store = new TranslationStore();
        store.set(CLS, "a", "甲", false);
        Map<String, String> first = store.effective(CLS);
        assertSame(first, store.effective(CLS), "未变更时应复用同一快照");
        assertThrows(UnsupportedOperationException.class, () -> first.put("b", "乙"), "快照必须只读（缓存实例被共享）");
    }

    @Test
    void setInvalidatesOnlyThatClass() {
        TranslationStore store = new TranslationStore();
        store.set(CLS, "a", "甲", false);
        store.set("demo/Other.class", "x", "叉", false);
        Map<String, String> other = store.effective("demo/Other.class");

        store.set(CLS, "b", "乙", false);
        assertEquals(Map.of("a", "甲", "b", "乙"), store.effective(CLS), "本类变更后应重算");
        assertSame(other, store.effective("demo/Other.class"), "其它类缓存不受影响");

        // 置空 = 删除
        store.set(CLS, "a", "", false);
        assertEquals(Map.of("b", "乙"), store.effective(CLS));
    }

    @Test
    void autoFillLayersAndInvalidates() {
        TranslationStore store = new TranslationStore();
        store.applyAutomatic(CLS, "a", "自动甲");
        assertEquals(Map.of("a", "自动甲"), store.effective(CLS));

        store.set(CLS, "a", "手动甲", false);
        assertEquals(Map.of("a", "手动甲"), store.effective(CLS), "手动覆盖自动");

        // 自动填充不覆盖已有手动译文
        store.applyAutomatic(CLS, "a", "又被自动");
        assertEquals(Map.of("a", "手动甲"), store.effective(CLS));
    }

    @Test
    void clearInvalidatesEverything() {
        TranslationStore store = new TranslationStore();
        store.set(CLS, "a", "甲", false);
        store.applyAutomatic("demo/Other.class", "x", "叉");
        store.clear();
        assertTrue(store.effective(CLS).isEmpty());
        assertTrue(store.effective("demo/Other.class").isEmpty());
    }

    @Test
    void statusReflectsLayers() {
        TranslationStore store = new TranslationStore();
        assertEquals(Project.Status.UNTRANSLATED, store.status(CLS, "a"));
        store.applyAutomatic(CLS, "a", "自动");
        assertEquals(Project.Status.AUTO, store.status(CLS, "a"));
        store.set(CLS, "a", "手动", false);
        assertEquals(Project.Status.TRANSLATED, store.status(CLS, "a"));
    }
}
