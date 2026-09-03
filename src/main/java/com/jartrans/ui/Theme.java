package com.jartrans.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Scene;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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
    public static final Map<String, String> LABELS = Map.of(
            MODE_SYSTEM, "跟随系统", MODE_LIGHT, "浅色", MODE_DARK, "深色");

    // ---------- 浅色配色（与 light.css 令牌一致） ----------
    public static final Map<String, String> LIGHT = palette(
            "#eef1f6", "#ffffff", "#f0f3f8", "#f6f8fb", "#ffffff",
            "#23272f", "#69707e", "#d6dbe4", "#3b6ef0", "#ffffff",
            "#d9e4ff", "#23272f", "#a4aab5", "#e7ebf2", "#f6f8fb",
            "#c93c37", "#b57708", "#1f9d4d", "#8b93a1", "#a4aab5",
            "#c93c37", "#1f9d4d", "#2f6de0", "#8b93a1",
            "#7c3aed", "#0d7d3f", "#8a919c", "#b3541e", "#fff0b3", "#ffe08a", "#8a5a00");

    // ---------- 深色配色（与 dark.css 令牌一致） ----------
    public static final Map<String, String> DARK = palette(
            "#14161b", "#1d2129", "#262c37", "#191d24", "#101319",
            "#e5e9f2", "#98a1b3", "#333a48", "#6e93ff", "#ffffff",
            "#31415e", "#ffffff", "#6d7484", "#10141b", "#20252e",
            "#ff6b66", "#ffb454", "#5fd97f", "#7d8590", "#6e7681",
            "#ff6b66", "#5fd97f", "#79b8ff", "#7d8590",
            "#569cd6", "#ce9178", "#6a9955", "#b5cea8", "#3d3a12", "#665918", "#e8b04b");

    private static Map<String, String> palette(String bg, String surface, String surfaceAlt,
                                               String headerBg, String fieldBg,
                                               String fg, String fgMuted, String border,
                                               String accent, String accentFg,
                                               String selectBg, String selectFg,
                                               String disabledFg, String sunken, String stripe,
                                               String todo, String doing, String done,
                                               String ignore, String empty,
                                               String untranslated, String translated,
                                               String auto, String internal,
                                               String kw, String str, String cmt,
                                               String num, String hl, String cur, String banner) {
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
    private Timeline watcher;

    public Theme(String mode) {
        this.mode = MODES.contains(mode) ? mode : MODE_SYSTEM;
        apply();
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

    public String stateColor(String state) {
        return palette.getOrDefault("state_" + state, palette.get("fg"));
    }

    public String statusColor(String status) {
        return palette.getOrDefault("status_" + status, palette.get("fg"));
    }

    public void setMode(String newMode) {
        String m = MODES.contains(newMode) ? newMode : MODE_SYSTEM;
        if (m.equals(mode)) {
            // system 模式即便没切换，也按当前系统偏好重新探测一次
            if (m.equals(MODE_SYSTEM)) {
                apply();
            }
            return;
        }
        mode = m;
        apply();
    }

    // ---------- 注册 ----------

    public void onChange(Runnable callback) {
        listeners.add(callback);
        callback.run();
    }

    public void attach(Scene scene) {
        scenes.add(scene);
        scene.getStylesheets().setAll(stylesheetUrl());
    }

    private String stylesheetUrl() {
        String name = dark() ? "dark.css" : "light.css";
        return Theme.class.getResource(name).toExternalForm();
    }

    // ---------- 应用 ----------

    private void apply() {
        // 跟随系统模式：每次都重新探测，不缓存，避免启动时探测失败导致整局卡在错的主题上
        if (mode.equals(MODE_SYSTEM)) {
            systemDark = systemPrefersDark();
        }
        palette = (mode.equals(MODE_DARK) || (mode.equals(MODE_SYSTEM) && systemDark))
                ? DARK : LIGHT;
        String url = stylesheetUrl();
        for (Scene scene : scenes) {
            scene.getStylesheets().setAll(url);
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
                    Process proc = new ProcessBuilder("reg", "query",
                            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
                            "/v", "AppsUseLightTheme")
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
                    } catch (java.nio.charset.MalformedInputException ex) {
                        text = Files.readString(out, StandardCharsets.UTF_8);
                    }
                    text = text.trim();
                    if (text.contains("AppsUseLightTheme")) {
                        // 取最后一行 REG_DWORD 值
                        for (String line : text.split("\\R")) {
                            if (line.contains("0x")) {
                                return line.trim().endsWith("0x0") || line.trim().endsWith("0x00");
                            }
                        }
                    }
                    return false;
                } finally {
                    Files.deleteIfExists(out);
                }
            }
            if (os.contains("mac")) {
                Process proc = new ProcessBuilder("defaults", "read", "-g",
                        "AppleInterfaceStyle")
                        .redirectErrorStream(true)
                        .start();
                proc.waitFor(5, TimeUnit.SECONDS);
                String text = new String(proc.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8);
                return text.contains("Dark");
            }
            if (os.contains("linux")) {
                String gtkTheme = System.getenv("GTK_THEME");
                if (gtkTheme != null && (gtkTheme.contains(":dark") || gtkTheme.endsWith("-dark"))) {
                    return true;
                }
                Process proc = new ProcessBuilder("gsettings", "get",
                        "org.gnome.desktop.interface", "color-scheme")
                        .redirectErrorStream(true)
                        .start();
                proc.waitFor(5, TimeUnit.SECONDS);
                String text = new String(proc.getInputStream().readAllBytes(),
                        StandardCharsets.UTF_8).toLowerCase();
                return text.contains("dark");
            }
        } catch (Exception ignored) {
            // 探测失败一律当作浅色
            return false;
        }
        return false;
    }

    private void startWatch() {
        watcher = new Timeline(new KeyFrame(Duration.millis(1500), e -> tick()));
        watcher.setCycleCount(Timeline.INDEFINITE);
        watcher.play();
    }

    private void tick() {
        if (!mode.equals(MODE_SYSTEM)) {
            return;
        }
        boolean dark = systemPrefersDark();
        if (dark != systemDark) {
            systemDark = dark;
            apply();
        }
    }

    public void stop() {
        if (watcher != null) {
            watcher.stop();
        }
    }
}
