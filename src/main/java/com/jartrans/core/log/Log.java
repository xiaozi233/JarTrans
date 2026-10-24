package com.jartrans.core.log;

/**
 * 极简日志门面：全应用统一经此获取 {@link System.Logger}。
 *
 * core 保持纯 JDK 依赖，不引入第三方日志框架；System.Logger 默认路由到
 * java.util.logging，由 {@link LogConfig} 在启动时装配轮转文件输出。
 * 后台静默失败（写盘/清理/探测）必须经此留痕，弹窗只用于需要用户决策的错误。
 */
public final class Log {

    private Log() {}

    public static System.Logger get(Class<?> type) {
        return System.getLogger(type.getName());
    }

    public static System.Logger get(String name) {
        return System.getLogger(name);
    }
}
