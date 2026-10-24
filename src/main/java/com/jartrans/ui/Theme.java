package com.jartrans.ui;

import com.jartrans.core.log.Log;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import javafx.scene.Scene;

/**
 * 主题管理：浅色 / 深色 / 跟随系统。
 * 通过切换 scene 的 stylesheet 实现（CSS 驱动），配色值供代码绘制（状态色、语法色）。
 * 「跟随系统」轮询系统设置，变化时自动切换。
 */
public final class Theme {

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";
    public static final List<String> MODES = List.of(MODE_SYSTEM, MODE_LIGHT, MODE_DARK);
    public static final Map<String, String> LABELS = Map.of(MODE_SYSTEM, "跟随系统", MODE_LIGHT, "浅色", MODE_DARK, "深色");

    // ---------- 浅色配色（与 light.css 令牌一致） ----------
    public static final Map<String, String> LIGHT = palette(
            "#eef1f6", "#ffffff", "#f0f3f8", "#f6f8fb", "#ffffff", "#23272f", "#69707e", "#d6dbe4", "#3b6ef0",
            "#ffffff", "#d9e4ff", "#23272f", "#a4aab5", "#e7ebf2", "#f6f8fb", "#c93c37", "#b57708", "#1f9d4d",
            "#8b93a1", "#a4aab5", "#c93c37", "#1f9d4d", "#2f6de0", "#8b93a1", "#7c3aed", "#0d7d3f", "#8a919c",
            "#b3541e", "#fff0b3", "#ffe08a", "#8a5a00");

    // ---------- 深色配色（与 dark.css 令牌一致） ----------
    public static final Map<String, String> DARK = palette(
            "#14161b", "#1d2129", "#262c37", "#191d24", "#101319", "#e5e9f2", "#98a1b3", "#333a48", "#6e93ff",
            "#ffffff", "#31415e", "#ffffff", "#6d7484", "#10141b", "#20252e", "#ff6b66", "#ffb454", "#5fd97f",
            "#7d8590", "#6e7681", "#ff6b66", "#5fd97f", "#79b8ff", "#7d8590", "#569cd6", "#ce9178", "#6a9955",
            "#b5cea8", "#3d3a12", "#665918", "#e8b04b");

    private static Map<String, String> palette(
            String bg,
            String surface,
            String surfaceAlt,
            String headerBg,
            String fieldBg,
            String fg,
            String fgMuted,
            String border,
            String accent,
            String accentFg,
            String selectBg,
            String selectFg,
            String disabledFg,
            String sunken,
            String stripe,
            String todo,
            String doing,
            String done,
            String ignore,
            String empty,
            String untranslated,
            String translated,
            String auto,
            String internal,
            String kw,
            String str,
            String cmt,
            String num,
            String hl,
            String cur,
            String banner) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("bg", bg);
        m.put("surface", surface);
        m.put("surface_alt", surfaceAlt);
        m.put("header_bg", headerBg);
        m.put("field_bg", fieldBg);
        m.put("fg", fg);
        m.put("fg_muted", fgMuted);
        m.put("border", border);
        m.put("accent", accent);
        m.put("accent_fg", accentFg);
        m.put("select_bg", selectBg);
        m.put("select_fg", selectFg);
        m.put("disabled_fg", disabledFg);
        m.put("sunken", sunken);
        m.put("stripe", stripe);
        // 类状态
        m.put("state_todo", todo);
        m.put("state_doing", doing);
        m.put("state_done", done);
        m.put("state_ignore", ignore);
        m.put("state_empty", empty);
        // 字符串条目状态
        m.put("status_untranslated", untranslated);
        m.put("status_translated", translated);
        m.put("status_auto", auto);
        m.put("status_internal", internal);
        // 源码着色
        m.put("syntax_kw", kw);
        m.put("syntax_str", str);
        m.put("syntax_cmt", cmt);
        m.put("syntax_num", num);
        m.put("syntax_hl", hl);
        m.put("syntax_cur", cur);
        m.put("syntax_banner", banner);
        return m;
    }

    private String mode;
    private boolean systemDark;
    private Map<String, String> palette = LIGHT;
    private final List<Runnable> listeners = new ArrayList<>();
    private final List<Scene> scenes = new ArrayList<>();
    /**
     * 系统主题探测调度（守护后台线程）。探测要 spawn 外部进程（reg/defaults/gsettings）
     * 并等待结果，绝不能在 FX 线程做——旧实现用 Timeline 每 1.5s 在 FX 线程探测，
     * 探测卡顿时整个界面周期性冻结（最长 5 秒）。
     */
    private ScheduledExecutorService watcher;

    public Theme(String mode) {
        this.mode = MODES.contains(mode) ? mode : MODE_SYSTEM;
        // 初始先按已知状态应用（systemDark 初值 false），后台探测完成后立即纠正
        applyCurrent();
        startWatch();
    }

    // ---------- 模式 ----------

    public String mode() {
        return mode;
    }

    public Map<String, String> palette() {
        return palette;
    }

    public boolean dark() {
        return palette == DARK;
    }

    public String color(String key) {
        return palette.get(key);
    }

    public String statusColor(String status) {
        return palette.getOrDefault("status_" + status, palette.get("fg"));
    }

    public void setMode(String newMode) {
        String m = MODES.contains(newMode) ? newMode : MODE_SYSTEM;
        if (m.equals(mode)) {
            // system 模式即便没切换，也按当前系统偏好重新探测一次（后台）
            if (m.equals(MODE_SYSTEM)) {
                requestProbe();
            }
            return;
        }
        mode = m;
        applyCurrent();
        if (m.equals(MODE_SYSTEM)) {
            requestProbe();
        }
    }

    // ---------- 注册 ----------

    public void onChange(Runnable callback) {
        listeners.add(callback);
        callback.run();
    }

    public void attach(Scene scene) {
        scenes.add(scene);
        scene.getStylesheets().setAll(stylesheetUrl(), SCROLLBAR_SKIN_URL);
    }

    /** 滚动条皮肤样式：.scroll-bar 换用 MinThumbScrollBarSkin（保证滑块最短长度）。 */
    private static final String SCROLLBAR_SKIN_URL =
            Theme.class.getResource("scrollbar-skin.css").toExternalForm();

    private String stylesheetUrl() {
        String name = dark() ? "dark.css" : "light.css";
        return Theme.class.getResource(name).toExternalForm();
    }

    // ---------- 应用 ----------

    /** 按已知状态（mode + systemDark）应用主题；不探测，可在任意线程调用者的同步路径上直接执行。 */
    private void applyCurrent() {
        palette = (mode.equals(MODE_DARK) || (mode.equals(MODE_SYSTEM) && systemDark)) ? DARK : LIGHT;
        String url = stylesheetUrl();
        for (Scene scene : scenes) {
            scene.getStylesheets().setAll(url, SCROLLBAR_SKIN_URL);
            scene.getRoot().applyCss();
        }
        for (Runnable cb : List.copyOf(listeners)) {
            cb.run();
        }
    }

    // ---------- 系统主题探测 ----------

    /** 探测系统是否处于深色模式。失败时保守返回 false。 */
    public static boolean systemPrefersDark() {
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                Path out = Files.createTempFile("jartrans_reg", ".txt");
                try {
                    Process proc = new ProcessBuilder(
                                    "reg",
                                    "query",
                                    "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                                    "/v",
                                    "AppsUseLightTheme")
                            .redirectOutput(out.toFile())
                            .redirectErrorStream(true)
                            .start();
                    if (!proc.waitFor(5, TimeUnit.SECONDS)) {
                        proc.destroyForcibly();
                        return false;
                    }
                    String text;
                    try {
                        text = Files.readString(out, StandardCharsets.UTF_16LE);
                        if (!text.contains("0x")) {
                            text = Files.readString(out, StandardCharsets.UTF_8);
                        }
                    } catch (MalformedInputException ex) {
                        text = Files.readString(out, StandardCharsets.UTF_8);
                    }
                    text = text.trim();
                    if (text.contains("AppsUseLightTheme")) {
                        // 取最后一行 REG_DWORD 值
                        for (String line : text.split("\\R")) {
                            if (line.contains("0x")) {
                                return line.trim().endsWith("0x0")
                                        || line.trim().endsWith("0x00");
                            }
                        }
                    }
                    return false;
                } finally {
                    Files.deleteIfExists(out);
                }
            }
            if (os.contains("mac")) {
                Process proc = new ProcessBuilder("defaults", "read", "-g", "AppleInterfaceStyle")
                        .redirectErrorStream(true)
                        .start();
                proc.waitFor(5, TimeUnit.SECONDS);
                String text = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                return text.contains("Dark");
            }
            if (os.contains("linux")) {
                String gtkTheme = System.getenv("GTK_THEME");
                if (gtkTheme != null && (gtkTheme.contains(":dark") || gtkTheme.endsWith("-dark"))) {
                    return true;
                }
                Process proc = new ProcessBuilder("gsettings", "get", "org.gnome.desktop.interface", "color-scheme")
                        .redirectErrorStream(true)
                        .start();
                proc.waitFor(5, TimeUnit.SECONDS);
                String text = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
                return text.contains("dark");
            }
        } catch (Exception e) {
            // 探测失败一律当作浅色；轮询周期内重复失败只记调试级，避免刷爆日志
            Log.get(Theme.class).log(System.Logger.Level.DEBUG, "系统深色探测失败", e);
            return false;
        }
        return false;
    }

    private void startWatch() {
        watcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "jartrans-theme-watch");
            t.setDaemon(true);
            return t;
        });
        // 立即探测一次（纠正构造时的 systemDark 初值），之后周期轮询
        watcher.scheduleWithFixedDelay(this::probeTick, 0, 1500, TimeUnit.MILLISECONDS);
    }

    /** 立即安排一次后台探测（setMode 回到 system 模式时使用）。 */
    private void requestProbe() {
        ScheduledExecutorService w = watcher;
        if (w != null && !w.isShutdown()) {
            w.execute(this::probeTick);
        }
    }

    /** 后台线程：探测系统偏好，变化时回 FX 线程应用。 */
    private void probeTick() {
        if (!mode.equals(MODE_SYSTEM)) {
            return;
        }
        boolean dark = systemPrefersDark();
        if (dark != systemDark) {
            systemDark = dark;
            runOnFx(this::applyCurrent);
        }
    }

    private static void runOnFx(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException e) {
            // FX 工具包未初始化（无界面场景）时直接执行
            action.run();
        }
    }

    public void stop() {
        if (watcher != null) {
            watcher.shutdownNow();
            watcher = null;
        }
    }
}
