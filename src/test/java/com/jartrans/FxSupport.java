package com.jartrans;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javafx.application.Platform;

/** 让多个 UI 测试在同一个 JVM 里共享唯一的 JavaFX toolkit（避免重复 startup/exit 冲突）。 */
final class FxSupport {

    private static final AtomicReference<Throwable> TOOLKIT_ERROR = new AtomicReference<>();
    private static volatile boolean initStarted;
    private static final CountDownLatch TOOLKIT_READY = new CountDownLatch(1);

    private FxSupport() {}

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

    /**
     * 轮询等待异步条件成立（条件在 FX 线程求值），超时抛 AssertionError。
     * 用于防抖输入/后台搜索这类无法同步断言的场景。
     */
    static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            boolean[] ok = {false};
            runFx(() -> ok[0] = condition.getAsBoolean());
            if (ok[0]) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("等待条件超时（15s）");
    }

    private static void startToolkit() throws Exception {
        synchronized (FxSupport.class) {
            if (initStarted) {
                awaitReady();
                return;
            }
            initStarted = true;
        }
        Thread t = new Thread(
                () -> {
                    try {
                        // 共享工具包：最后一个 Stage 隐藏/关闭不得隐式退出 FX 线程，
                        // 否则后续测试的 runLater 永远不会执行（表现为全部 FX 测试超时）
                        Platform.setImplicitExit(false);
                        Platform.startup(TOOLKIT_READY::countDown);
                    } catch (Throwable e) {
                        TOOLKIT_ERROR.set(e);
                        TOOLKIT_READY.countDown();
                    }
                },
                "fx-toolkit");
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
