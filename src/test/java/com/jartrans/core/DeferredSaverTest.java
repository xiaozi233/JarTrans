package com.jartrans.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jartrans.core.io.DeferredSaver;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** DeferredSaver：延迟合并、flush 立即写出、无脏变更时 no-op。 */
class DeferredSaverTest {

    @Test
    void burstOfRequestsCoalescesIntoOneSave() throws Exception {
        AtomicInteger saves = new AtomicInteger();
        DeferredSaver saver = new DeferredSaver("test-coalesce", 50, saves::incrementAndGet);
        for (int i = 0; i < 100; i++) {
            saver.requestSave();
            Thread.sleep(2); // 制造轻微间隔，模拟连续 UI 操作
        }
        assertTrue(saver.isDirty() || saves.get() >= 1);
        saver.flush();
        assertEquals(1, saves.get(), "连续请求应合并为一次落盘（flush 前最多一次挂起）");
        assertFalse(saver.isDirty());
    }

    @Test
    void flushWritesImmediatelyAndSecondFlushIsNoOp() {
        AtomicInteger saves = new AtomicInteger();
        DeferredSaver saver = new DeferredSaver("test-flush", 60_000, saves::incrementAndGet);
        saver.requestSave();
        assertEquals(0, saves.get(), "延迟未到不应写盘");
        saver.flush();
        assertEquals(1, saves.get(), "flush 应立即写出");
        saver.flush();
        assertEquals(1, saves.get(), "无脏变更的 flush 是 no-op");
        assertFalse(saver.isDirty());
    }

    @Test
    void requestAfterFlushSchedulesAgain() {
        AtomicInteger saves = new AtomicInteger();
        DeferredSaver saver = new DeferredSaver("test-again", 60_000, saves::incrementAndGet);
        saver.requestSave();
        saver.flush();
        saver.requestSave();
        assertTrue(saver.isDirty());
        saver.flush();
        assertEquals(2, saves.get());
    }
}
