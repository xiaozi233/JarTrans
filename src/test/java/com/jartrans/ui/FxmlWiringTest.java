package com.jartrans.ui;

import javafx.fxml.FXML;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护主界面接线：main_view.fxml 的每个 fx:id 都必须有 MainApp 里对应的 @FXML 字段
 * （双向一致），每个 onAction="#…" 处理器必须有同名方法。防改 FXML/控制器时漏同步，
 * 这类错误 UiSmokeTest 加载时未必暴露（处理器要到触发时才解析）。
 */
class FxmlWiringTest {

    private static final Pattern ID_RE = Pattern.compile("fx:id=\"([A-Za-z_]\\w*)\"");
    private static final Pattern HANDLER_RE = Pattern.compile("onAction=\"#(\\w+)\"");

    private static String fxml() throws IOException {
        try (InputStream in = MainApp.class.getResourceAsStream("/com/jartrans/ui/main_view.fxml")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Set<String> fxmlIds(String src) {
        return ID_RE.matcher(src).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
    }

    @Test
    void fxmlIdsMatchFxmlAnnotatedFields() throws Exception {
        Set<String> ids = fxmlIds(fxml());
        Set<String> fields = Arrays.stream(MainApp.class.getDeclaredFields())
                .filter(f -> f.isAnnotationPresent(FXML.class))
                .map(Field::getName)
                .collect(Collectors.toSet());
        assertEquals(fields, ids, "FXML fx:id 与 MainApp @FXML 字段应一一对应");
    }

    @Test
    void actionHandlersExistAsMethods() throws Exception {
        Set<String> handlers = HANDLER_RE.matcher(fxml()).results()
                .map(m -> m.group(1)).collect(Collectors.toSet());
        assertTrue(!handlers.isEmpty(), "FXML 应声明事件处理器");
        Set<String> methods = Arrays.stream(MainApp.class.getDeclaredMethods())
                .map(Method::getName).collect(Collectors.toSet());
        for (String handler : handlers) {
            assertTrue(methods.contains(handler),
                    "FXML onAction 引用的方法不存在: " + handler);
        }
    }
}
