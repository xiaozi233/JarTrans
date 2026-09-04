package com.jartrans;

import com.jartrans.ui.MinThumbScrollBarSkin;
import com.jartrans.ui.Theme;
import javafx.collections.FXCollections;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 滚动条最小长度回归：10 万条 ListView 真实布局，滑块不得短于 MinThumbScrollBarSkin.MIN_THUMB_LENGTH。 */
class MinThumbScrollBarSkinTest {

    @TempDir
    static Path tmp;

    @Test
    void hugeListKeepsScrollThumbAtLeastMinLength() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        FxSupport.runFx(() -> {
            List<String> items = new ArrayList<>(100_000);
            for (int i = 0; i < 100_000; i++) {
                items.add("条目 " + i);
            }
            ListView<String> list = new ListView<>(FXCollections.observableArrayList(items));
            list.setPrefSize(220, 280);

            Scene scene = new Scene(new StackPane(list), 220, 280);
            new Theme("light").attach(scene);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            ScrollBar vertical = null;
            for (Node n : list.lookupAll(".scroll-bar")) {
                ScrollBar sb = (ScrollBar) n;
                if (sb.getOrientation() == Orientation.VERTICAL) {
                    vertical = sb;
                }
            }
            assertNotNull(vertical, "10 万条 ListView 应出现垂直滚动条");
            assertTrue(vertical.getSkin() instanceof MinThumbScrollBarSkin,
                    "滚动条应换用 MinThumbScrollBarSkin（实际 " + vertical.getSkin() + "）");

            Region thumb = (Region) vertical.lookup(".thumb");
            assertNotNull(thumb, "应能找到滑块");
            assertThumbLongEnough(thumb, "初始");

            // 滚到末尾：滑块仍保底且不越过轨道
            vertical.setValue(vertical.getMax());
            scene.getRoot().layout();
            assertThumbLongEnough((Region) vertical.lookup(".thumb"), "滚到末尾后");

            stage.close();
        });
    }

    private static void assertThumbLongEnough(Region thumb, String when) {
        assertNotNull(thumb);
        double len = thumb.getHeight();
        assertTrue(len >= MinThumbScrollBarSkin.MIN_THUMB_LENGTH - 0.5,
                when + "滑块高度应保底 " + MinThumbScrollBarSkin.MIN_THUMB_LENGTH
                        + "px（实际 " + len + "）");
    }
}
