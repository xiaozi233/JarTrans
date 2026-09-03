package com.jartrans.core;

import java.nio.file.Path;

/** 程序目录定位：settings.json、dictionaries/、class_status.json、tools/ 的基准目录。 */
public final class AppDirs {

    private AppDirs() {
    }

    /**
     * 基准目录：优先系统属性 jartrans.dir，否则用当前工作目录。
     * 每次现算、不缓存——运行期属性不会变，缓存只会让多测试类共享 JVM 时
     * 出现「谁先调用谁锁死目录」的顺序耦合（各测试类用 System.setProperty 隔离）。
     * 与 Python 版「程序所在目录」语义一致（启动器以程序目录为工作目录）。
     */
    public static Path baseDir() {
        String prop = System.getProperty("jartrans.dir", "");
        return (prop == null || prop.isEmpty())
                ? Path.of(System.getProperty("user.dir"))
                : Path.of(prop);
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
}
