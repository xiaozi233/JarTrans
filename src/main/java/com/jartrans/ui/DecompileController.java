package com.jartrans.ui;

import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javafx.animation.PauseTransition;
import javafx.concurrent.Task;
import javafx.util.Duration;

/** 源码页编排：异步调用内置引擎，缓存单类结果并处理取消。 */
final class DecompileController {
    private final MainApp app;
    private Task<List<Path>> activeTask;
    private String activeClass;
    private long generation;
    private final Map<String, List<Path>> singleClassFiles = new HashMap<>();

    DecompileController(MainApp app) {
        this.app = app;
    }

    /** 切换 JAR/引擎或清缓存时，使旧任务与旧结果失效。 */
    void invalidate() {
        generation++;
        if (activeTask != null) {
            activeTask.cancel(true);
            activeTask = null;
        }
        singleClassFiles.clear();
    }

    void cancel() {
        if (activeTask == null) {
            app.setStatus("当前没有正在进行的反编译");
            return;
        }
        activeTask.cancel(true);
    }

    /** 返回 single / loading / bytecode，全部回调发生在 FX 线程。 */
    void ensureClassSources(String cls, BiConsumer<String, Object> then) {
        if (!app.project().hasJar()) {
            then.accept("bytecode", null);
            return;
        }
        List<Path> cached = singleClassFiles.get(cls);
        if (cached != null && cached.stream().allMatch(Files::isRegularFile)) {
            then.accept("single", cached);
            return;
        }
        if (activeTask != null) {
            if (cls.equals(activeClass)) {
                then.accept("loading", null);
                return;
            }
            // 切换类时丢弃旧请求，当前类应获得自己的完成回调。
            activeTask.cancel(true);
        }
        DecompilerType type = DecompilerManager.currentDecompiler(app.settings());
        String inner = internalName(cls);
        List<String> names = app.project().classOrder().stream()
                .map(DecompileController::internalName)
                .filter(n -> n.equals(inner) || n.startsWith(inner + "$"))
                .toList();
        String jarPath = app.project().jarPath().toString();
        long taskGeneration = generation;
        app.setStatus("正在反编译 " + inner + " …");
        boolean[] timedOut = {false};
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                // SHA-256 需要全量读取 jar：放在工作线程，避免大 jar/慢盘时冻结界面
                String sha = app.project().jarSha256();
                if (sha == null || sha.isEmpty()) {
                    throw new IOException("无法计算 jar 摘要");
                }
                return DecompilerManager.decompileClasses(jarPath, sha, names, type);
            }
        };
        // 兜底超时：个别混淆类可能让引擎长时间不返回；到点取消并回退字节码视图，
        // 避免界面永远停在「正在反编译」。
        PauseTransition timeout = new PauseTransition(Duration.seconds(60));
        timeout.setOnFinished(ev -> {
            if (activeTask == task) {
                timedOut[0] = true;
                task.cancel(true);
            }
        });
        timeout.playFromStart();
        activeTask = task;
        activeClass = cls;
        task.setOnSucceeded(ev -> {
            timeout.stop();
            if (taskGeneration != generation || activeTask != task) {
                return;
            }
            activeTask = null;
            List<Path> files = task.getValue();
            if (files.isEmpty()) {
                app.setStatus("反编译未生成源码，已回退字节码视图");
                then.accept("bytecode", null);
                return;
            }
            singleClassFiles.put(cls, files);
            app.setStatus("已完成「" + inner + "」反编译");
            then.accept("single", files);
        });
        task.setOnFailed(ev -> {
            timeout.stop();
            if (taskGeneration != generation || activeTask != task) {
                return;
            }
            activeTask = null;
            String msg = task.getException() == null ? "" : task.getException().getMessage();
            app.setStatus("反编译失败：" + msg);
            if (!String.valueOf(msg).contains("已取消")) {
                Dialogs.error("反编译失败", String.valueOf(msg));
            }
            then.accept("bytecode", null);
        });
        task.setOnCancelled(ev -> {
            timeout.stop();
            if (taskGeneration != generation || activeTask != task) {
                return;
            }
            activeTask = null;
            app.setStatus(timedOut[0] ? "反编译超时（60 秒），已回退字节码视图" : "已取消反编译");
            then.accept("bytecode", null);
        });
        Thread worker = new Thread(task, "decompile-class");
        worker.setDaemon(true);
        worker.start();
        then.accept("loading", null);
    }

    private static String internalName(String cls) {
        return cls.endsWith(".class") ? cls.substring(0, cls.length() - ".class".length()) : cls;
    }
}
