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
import javafx.concurrent.Task;

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
        String sha;
        try {
            sha = app.project().jarSha256();
        } catch (IOException e) {
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
        List<String> names = app.project().classOrder().stream().map(DecompileController::internalName)
                .filter(n -> n.equals(inner) || n.startsWith(inner + "$")).toList();
        String jarPath = app.project().jarPath().toString();
        long taskGeneration = generation;
        app.setStatus("正在反编译 " + inner + " …");
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                return DecompilerManager.decompileClasses(jarPath, sha, names, type);
            }
        };
        activeTask = task;
        activeClass = cls;
        task.setOnSucceeded(ev -> {
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
            if (taskGeneration != generation || activeTask != task) {
                return;
            }
            activeTask = null;
            app.setStatus("已取消反编译");
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
