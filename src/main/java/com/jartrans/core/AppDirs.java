package com.jartrans.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** 程序目录定位：settings.json、dictionaries/、class_status.json、tools/ 的基准目录。 */
public final class AppDirs {

    private static Path cached;

    private AppDirs() {
    }

    /**
     * 基准目录：优先系统属性 jartrans.dir，否则用当前工作目录。
     * 与 Python 版「程序所在目录」语义一致（启动器以程序目录为工作目录）。
     */
    public static synchronized Path baseDir() {
        if (cached == null) {
            String prop = System.getProperty("jartrans.dir", "");
            cached = (prop == null || prop.isEmpty())
                    ? Path.of(System.getProperty("user.dir"))
                    : Path.of(prop);
        }
        return cached;
    }

    public static Path settingsFile() {
        return baseDir().resolve("settings.json");
    }

    public static Path progressFile() {
        return baseDir().resolve("class_status.json");
    }

    public static Path toolsDir() {
        return baseDir().resolve("tools");
    }

    public static void ensureBaseDir() {
        try {
            Files.createDirectories(baseDir());
        } catch (Exception ignored) {
            // 目录创建失败交由具体写入操作报错
        }
    }
}
