package com.jartrans;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jartrans.ui.MainApp;
import com.jartrans.ui.Theme;
import java.nio.file.Path;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** UI 冒烟测试：验证 FXML 可加载、fx:id 装配正确、主场景能构建。 */
class UiSmokeTest {

    @TempDir
    static Path tmp;

    @Test
    void mainViewLoadsAndWires() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        FxSupport.runFx(() -> {
            MainApp app = new MainApp();
            FXMLLoader loader = new FXMLLoader(MainApp.class.getResource("/com/jartrans/ui/main_view.fxml"));
            loader.setController(app);
            Parent root = loader.load();
            assertNotNull(root);
            // Theme.attach 必须挂载「结构 base.css + 当前调色板 + 滚动条皮肤」三份样式
            Scene scene = new Scene(root, 1320, 840);
            Theme theme = new Theme(Theme.MODE_LIGHT);
            theme.attach(scene);
            scene.getRoot().applyCss();
            var sheets = scene.getStylesheets();
            assertTrue(sheets.stream().anyMatch(u -> u.endsWith("/base.css")), "缺结构样式 base.css");
            assertTrue(sheets.stream().anyMatch(u -> u.endsWith("/light.css")), "缺浅色调色板 light.css");
            assertTrue(sheets.stream().anyMatch(u -> u.endsWith("/scrollbar-skin.css")), "缺滚动条皮肤");
            theme.stop();
            assertNotNull(app.editor());
            assertNotNull(app.sourcePane());
        });
    }
}
