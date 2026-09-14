package com.jartrans.ui;

import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import com.jartrans.core.java.JavaEnv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javafx.concurrent.Task;

/**
 * 源码页的反编译编排：按需单类反编译（含内部类）、产物缓存与索引、取消、Java 环境缓存。
 *
 * <p>从 MainApp 中拆出，使主窗口只承担界面接线；对外入口仍由 MainApp 原样转发，
 * SourcePanel / 反编译管理器窗口的调用方式不变。</p>
 */
final class DecompileController {

    private final MainApp app;

    /** 已探测到的 java 路径（本会话内复用）。 */
    private String javaPathCache;
    private boolean decompiling;
    /** 进行中的单类反编译任务（供「取消反编译」中断与丢弃结果）。 */
    private Task<List<Path>> activeTask;
    private Map<String, Path> indexCache;
    /** 已按单类反编译过的类 → 产出的 .java 文件（本会话内复用）。 */
    private final Map<String, List<Path>> singleClassFiles = new HashMap<>();
    private boolean javaPrompted;

    DecompileController(MainApp app) {
        this.app = app;
    }

    // ---------- Java 环境 ----------

    /** 记住已探测到的 java 路径，避免重复探测。 */
    void rememberJava(String path) {
        javaPathCache = path;
    }

    /** 快速取可用 java 路径：会话缓存 → 配置项 → 自动探测；均不可用返回 null。 */
    String javaQuick() {
        if (javaPathCache != null) {
            return javaPathCache;
        }
        String configured = app.settings().getString("java_path");
        if (!configured.isEmpty() && new File(configured).isFile()) {
            return configured;
        }
        JavaEnv.JavaResult result = JavaEnv.findJava(app.settings());
        if (result.path() != null) {
            javaPathCache = result.path();
            return result.path();
        }
        return null;
    }

    // ---------- 缓存 ----------

    /** 打开新 jar / 清理缓存后调用：本会话的反编译产物与索引全部失效。 */
    void invalidate() {
        indexCache = null;
        singleClassFiles.clear();
    }

    /** 整 jar 反编译产物索引（类 → .java）；无缓存时返回空表。 */
    Map<String, Path> index() {
        if (indexCache == null) {
            try {
                Path cache = DecompilerManager.findCache(app.project().jarSha256());
                indexCache = cache != null ? DecompilerManager.buildFileIndex(cache) : Map.of();
            } catch (IOException e) {
                indexCache = Map.of();
            }
        }
        return indexCache;
    }

    /** 「取消反编译」：中断进行中的单类反编译，丢弃其尚未回填的结果并回退字节码视图。 */
    void cancel() {
        Task<List<Path>> task = activeTask;
        if (task == null) {
            app.setStatus("当前没有正在进行的反编译");
            return;
        }
        task.cancel(true);
        // cancel(true) 会同步触发 onCancelled：置 decompiling=false、回退字节码、更新状态栏
    }

    // ---------- 按需反编译 ----------

    /**
     * 按需准备某个类的反编译源码（不再整 jar 反编译）：
     * 已有整 jar 缓存 → "whole"(Path 缓存根)；已单类反编译过 → "single"(List&lt;Path&gt;)；
     * 正在反编译 → "loading"；不具备条件 → "bytecode"。结果均在 FX 线程回调。
     */
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
        Path whole = DecompilerManager.findCache(sha);
        if (whole != null) {
            then.accept("whole", whole);
            return;
        }
        List<Path> cached = singleClassFiles.get(cls);
        if (cached != null) {
            then.accept("single", cached);
            return;
        }
        DecompilerManager.Selection dec = currentTool();
        if (dec == null) {
            maybePromptSetup();
            then.accept("bytecode", null);
            return;
        }
        if (decompiling) {
            then.accept("loading", null);
            return;
        }
        DecompilerType type = dec.type();
        String inner = internalName(cls);
        // 目标类 + 内部类（如 Foo 与 Foo$Inner），整组一次喂给反编译器
        List<String> names = new ArrayList<>();
        for (String c : app.project().classOrder()) {
            String n = internalName(c);
            if (n.equals(inner) || n.startsWith(inner + "$")) {
                names.add(n);
            }
        }
        decompiling = true;
        app.setStatus("正在反编译 " + inner + " …");
        Task<List<Path>> task = new Task<>() {
            @Override
            protected List<Path> call() throws Exception {
                JavaEnv.JavaResult java = JavaEnv.findJava(app.settings());
                if (java.path() == null) {
                    throw new DecompilerManager.DecompileException(
                            "反编译器初始化失败，请重试或清理反编译缓存后再试。");
                }
                javaPathCache = java.path();
                return DecompilerManager.decompileClasses(app.project().jarPath().toString(), sha,
                        names, type);
            }
        };
        activeTask = task;
        task.setOnSucceeded(ev -> {
            decompiling = false;
            activeTask = null;
            List<Path> files = task.getValue();
            if (files.isEmpty()) {
                app.setStatus("反编译未生成源码（可能类被混淆/无法解析），已回退字节码视图");
                then.accept("bytecode", null);
                return;
            }
            singleClassFiles.put(cls, files);
            app.setStatus("已完成「" + inner + "」反编译");
            then.accept("single", files);
        });
        task.setOnFailed(ev -> {
            decompiling = false;
            activeTask = null;
            String msg = task.getException() == null ? "" : task.getException().getMessage();
            app.setStatus("反编译失败：" + msg);
            if (!String.valueOf(msg).contains("已取消")) {
                Dialogs.error("反编译失败", String.valueOf(msg));
            }
            then.accept("bytecode", null);
        });
        task.setOnCancelled(ev -> {
            // 用户点击「取消反编译」：丢弃未回填的结果并回退字节码视图
            decompiling = false;
            activeTask = null;
            app.setStatus("已取消反编译");
            then.accept("bytecode", null);
        });
        new Thread(task, "decompile-class").start();
        then.accept("loading", null);
    }

    /** 取当前反编译器（三种均内置，缺失时自动释放，保证有可用引擎）。 */
    private DecompilerManager.Selection currentTool() {
        DecompilerManager.ensureBundledAll();
        return DecompilerManager.currentDecompiler(app.settings());
    }

    /** class 内部名（UI 里类键带 .class 后缀，反编译器需要去掉）。 */
    private static String internalName(String cls) {
        return cls.endsWith(".class")
                ? cls.substring(0, cls.length() - ".class".length()) : cls;
    }

    private void maybePromptSetup() {
        if (javaPrompted || javaQuick() != null) {
            return;
        }
        javaPrompted = true;
        Dialogs.info("提示",
                "反编译器未能就绪，已回退字节码视图。\n"
                        + "可在「源码 → 清理反编译缓存」后重试。");
    }
}
