package com.jartrans.core.log;

import com.jartrans.core.AppDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * 日志装配：程序目录 logs/ 下轮转文件（jartrans.log，5 个文件 × 1MB，追加模式）。
 *
 * 只在应用入口调用一次；装配失败（目录不可写等）降级为仅控制台输出，
 * 绝不因日志问题阻断启动。System.Logger 的调用端无需感知这里的存在。
 */
public final class LogConfig {

    /** 单个日志文件上限（字节）。 */
    static final int FILE_LIMIT = 1_000_000;
    /** 轮转文件数。 */
    static final int FILE_COUNT = 5;

    private LogConfig() {}

    public static void init() {
        Path dir = AppDirs.baseDir().resolve("logs");
        try {
            Files.createDirectories(dir);
            FileHandler handler = new FileHandler(dir.resolve("jartrans.log").toString(), FILE_LIMIT, FILE_COUNT, true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new SimpleFormatter());
            Logger root = Logger.getLogger("");
            root.addHandler(handler);
            root.setLevel(Level.INFO);
        } catch (IOException e) {
            Log.get(LogConfig.class).log(System.Logger.Level.WARNING, "日志文件初始化失败，仅输出到控制台", e);
        }
    }
}
