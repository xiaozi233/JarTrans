package com.jartrans.core.io;

import com.jartrans.core.log.Log;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 合并短时间内的多次保存请求：requestSave() 只（重新）调度一次延迟落盘，
 * flush() 立即执行挂起的保存。用于设置/进度这类「高频小写」的 JSON 状态文件，
 * 把连续点击的多次读改写合并成一次（原子写由调用方经 AtomicFiles 保证）。
 *
 * 线程模型：所有实例共享一个守护调度线程；action 在该线程执行，
 * 必须自行快照所需数据，不得依赖调用方线程的同步上下文。
 */
public final class DeferredSaver {

    /** 共享调度线程（守护，逐任务串行）。 */
    private static final ScheduledExecutorService SHARED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jartrans-saver");
        t.setDaemon(true);
        return t;
    });

    private final String name;
    private final long delayMillis;
    private final Runnable action;
    private final Object lock = new Object();
    private ScheduledFuture<?> pending;
    private boolean dirty;
    private boolean running;

    public DeferredSaver(String name, long delayMillis, Runnable action) {
        this.name = name;
        this.delayMillis = delayMillis;
        this.action = action;
    }

    /** 标记有变更并（重新）调度延迟保存；连续调用会合并为最后一次之后的单次落盘。 */
    public void requestSave() {
        synchronized (lock) {
            dirty = true;
            if (pending != null) {
                pending.cancel(false);
            }
            pending = SHARED.schedule(this::run, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    /** 若有挂起变更则立即执行保存；异常按 action 自身的策略处理（静默/日志）。 */
    public void flush() {
        while (true) {
            ScheduledFuture<?> p;
            synchronized (lock) {
                p = pending;
                pending = null;
                if (p != null) {
                    p.cancel(false);
                }
                if (!dirty) {
                    return;
                }
                if (running) {
                    // action 正在执行：置脏已记录，等它结束后由本方法再跑一轮
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    continue;
                }
                dirty = false;
                running = true;
            }
            try {
                action.run();
            } catch (RuntimeException e) {
                Log.get(DeferredSaver.class).log(System.Logger.Level.ERROR, "延迟保存执行失败（" + name + "）", e);
            } finally {
                synchronized (lock) {
                    running = false;
                    lock.notifyAll();
                }
            }
        }
    }

    private void run() {
        synchronized (lock) {
            pending = null;
            if (!dirty || running) {
                return;
            }
            dirty = false;
            running = true;
        }
        try {
            action.run();
        } catch (RuntimeException e) {
            synchronized (lock) {
                dirty = true; // 失败保留脏标记，下次请求或退出 flush 重试
            }
            Log.get(DeferredSaver.class).log(System.Logger.Level.WARNING, "延迟保存失败（" + name + "），保留待写", e);
        } finally {
            synchronized (lock) {
                running = false;
                lock.notifyAll(); // 唤醒可能在等待 action 完成的 flush()
            }
        }
    }

    /** 是否有未落盘的变更（测试与退出前检查用）。 */
    public boolean isDirty() {
        synchronized (lock) {
            return dirty;
        }
    }
}
