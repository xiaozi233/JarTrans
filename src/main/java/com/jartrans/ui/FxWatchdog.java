package com.jartrans.ui;

import com.jartrans.core.log.Log;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

/**
 * 界面线程无响应看门狗：FX 线程每秒打一次心跳；后台线程发现超过 6 秒没有心跳
 * （窗口被系统标记“未响应”）时，把所有线程栈一次性写入日志，便于定位卡死原因。
 *
 * <p>只在真正无响应时记录，不参与业务逻辑，也不改变任何交互行为。</p>
 */
final class FxWatchdog {

    private static final System.Logger LOG = Log.get(FxWatchdog.class);
    private static final long BEAT_MILLIS = 1_000;
    private static final long HANG_MILLIS = 6_000;

    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "jartrans-fx-watchdog");
        t.setDaemon(true);
        return t;
    });
    private volatile long lastBeat = System.currentTimeMillis();
    private volatile boolean reported;
    private Timeline heartbeat;

    void start() {
        heartbeat = new Timeline(new KeyFrame(Duration.millis(BEAT_MILLIS), e -> {
            lastBeat = System.currentTimeMillis();
            reported = false;
        }));
        heartbeat.setCycleCount(Timeline.INDEFINITE);
        heartbeat.play();
        monitor.scheduleWithFixedDelay(this::check, 2, 2, TimeUnit.SECONDS);
    }

    void stop() {
        if (heartbeat != null) {
            heartbeat.stop();
            heartbeat = null;
        }
        monitor.shutdownNow();
    }

    private void check() {
        long silent = System.currentTimeMillis() - lastBeat;
        if (silent < HANG_MILLIS || reported) {
            return;
        }
        reported = true;
        StringBuilder dump = new StringBuilder();
        dump.append("界面线程已无响应 ").append(silent).append(" ms，输出全部线程栈用于排查：\n");
        for (Map.Entry<Thread, StackTraceElement[]> e :
                Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            dump.append("\n\"").append(t.getName()).append("\" state=").append(t.getState());
            if (t.isDaemon()) {
                dump.append(" daemon");
            }
            dump.append('\n');
            for (StackTraceElement el : e.getValue()) {
                dump.append("    at ").append(el).append('\n');
            }
        }
        LOG.log(System.Logger.Level.ERROR, dump.toString());
    }
}
