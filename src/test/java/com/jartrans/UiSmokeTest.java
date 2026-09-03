package com.jartrans;

import com.jartrans.ui.MainApp;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** UI 冒烟测试：验证 FXML 可加载、fx:id 装配正确、主场景能构建。 */
class UiSmokeTest {

    @TempDir
    static Path tmp;

    @Test
    void mainViewLoadsAndWires() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread fxThread = new Thread(() -> {
            Platform.startup(() -> {
            });
            try {
                MainApp app = new MainApp();
                FXMLLoader loader = new FXMLLoader(
                        MainApp.class.getResource("/com/jartrans/ui/main_view.fxml"));
                loader.setController(app);
                Parent root = loader.load();
                assertNotNull(root);
                // 校验主题 CSS 与关键控件可实例化
                javafx.scene.Scene scene = new javafx.scene.Scene(root, 1320, 840);
                scene.getStylesheets().setAll(
                        MainApp.class.getResource("/com/jartrans/ui/light.css").toExternalForm());
                scene.getRoot().applyCss();
                assertNotNull(app.editor());
                assertNotNull(app.sourcePane());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                ready.countDown();
            }
        }, "fx-smoke");
        fxThread.start();
        assertTrue(ready.await(30, TimeUnit.SECONDS), "JavaFX 初始化超时");
        if (error.get() != null) {
            throw new AssertionError("UI 装配失败", error.get());
        }
        Platform.exit();
    }
}
