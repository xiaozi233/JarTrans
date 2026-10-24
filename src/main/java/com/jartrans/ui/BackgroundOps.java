package com.jartrans.ui;

import com.jartrans.core.log.Log;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.stage.Window;

/**
 * 重操作后台执行的统一封装（打开/导出 jar、语言包应用、词典批量填充等）。
 *
 * 职责：忙状态（状态栏文案 + 等待光标）、互斥单飞（进行中拒绝再次触发，
 * 避免并发修改工程状态）、错误统一弹窗 + 日志。成功/失败回调都回到 FX 线程。
 */
public final class BackgroundOps {

    private static final System.Logger LOG = Log.get(BackgroundOps.class);

    private final Window owner;
    private final Consumer<String> status;
    private final AtomicBoolean busy = new AtomicBoolean();

    public BackgroundOps(Window owner, Consumer<String> status) {
        this.owner = owner;
        this.status = status;
    }

    /** 是否有后台操作正在进行（调用方可用它禁用相关菜单动作）。 */
    public boolean isBusy() {
        return busy.get();
    }

    /**
     * 后台执行 work；onDone 在 FX 线程接收结果。
     * 忙碌中再次触发会提示并忽略，opTitle 用于状态与错误文案。
     */
    public <T> void run(String opTitle, String busyStatus, Callable<T> work, Consumer<T> onDone) {
        if (!busy.compareAndSet(false, true)) {
            Dialogs.warn(opTitle, "当前有操作正在进行，请稍候再试。");
            return;
        }
        setStatus(busyStatus);
        Scene scene = owner.getScene();
        if (scene != null) {
            scene.setCursor(Cursor.WAIT);
        }
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(ev -> {
            finish(scene);
            onDone.accept(task.getValue());
        });
        task.setOnFailed(ev -> {
            finish(scene);
            Throwable ex = task.getException();
            LOG.log(System.Logger.Level.ERROR, opTitle + "失败", ex);
            String detail = ex.getMessage() == null || ex.getMessage().isEmpty() ? ex.toString() : ex.getMessage();
            Dialogs.error("错误", opTitle + "失败：\n" + detail);
            setStatus(opTitle + "失败");
        });
        Thread thread = new Thread(task, "jartrans-bg-ops");
        thread.setDaemon(true);
        thread.start();
    }

    private void finish(Scene scene) {
        busy.set(false);
        if (scene != null) {
            scene.setCursor(Cursor.DEFAULT);
        }
    }

    private void setStatus(String text) {
        if (status != null) {
            status.accept(text);
        }
    }
}
