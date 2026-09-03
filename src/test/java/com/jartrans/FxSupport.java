package com.jartrans;

import javafx.application.Platform;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 让多个 UI 测试在同一个 JVM 里共享唯一的 JavaFX toolkit（避免重复 startup/exit 冲突）。 */
final class FxSupport {

    private static final AtomicReference<Throwable> TOOLKIT_ERROR = new AtomicReference<>();
    private static volatile boolean initStarted;
    private static final CountDownLatch TOOLKIT_READY = new CountDownLatch(1);

    private FxSupport() {
    }

    @FunctionalInterface
    interface Body {
        void run() throws Throwable;
    }

    /** 在 FX 应用线程执行 body；body 抛错则以 AssertionError 传播。 */
    static void runFx(Body body) throws Exception {
        startToolkit();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw new AssertionError("FX 任务执行超时");
        }
        if (error.get() != null) {
            throw new AssertionError("FX 任务失败", error.get());
        }
    }

    private static void startToolkit() throws Exception {
        synchronized (FxSupport.class) {
            if (initStarted) {
                awaitReady();
                return;
            }
            initStarted = true;
        }
        Thread t = new Thread(() -> {
            try {
                Platform.startup(TOOLKIT_READY::countDown);
            } catch (Throwable e) {
                TOOLKIT_ERROR.set(e);
                TOOLKIT_READY.countDown();
            }
        }, "fx-toolkit");
        t.setDaemon(true);
        t.start();
        awaitReady();
    }

    private static void awaitReady() throws Exception {
        if (!TOOLKIT_READY.await(30, TimeUnit.SECONDS)) {
            throw new AssertionError("JavaFX 初始化超时");
        }
        if (TOOLKIT_ERROR.get() != null) {
            throw new AssertionError("JavaFX 初始化失败", TOOLKIT_ERROR.get());
        }
    }
}
