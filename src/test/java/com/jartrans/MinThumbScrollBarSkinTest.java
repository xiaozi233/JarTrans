package com.jartrans;

import com.jartrans.ui.MinThumbScrollBarSkin;
import com.jartrans.ui.Theme;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
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
            assertThumbInsideTrack(vertical, thumb, "初始");

            // 顶 / 中 / 底三段滚动：滑块始终保底且在轨道范围内（不得越出滚动条）
            vertical.setValue(0);
            scene.getRoot().layout();
            Region thumbAtTop = (Region) vertical.lookup(".thumb");
            assertThumbLongEnough(thumbAtTop, "顶部");
            assertThumbInsideTrack(vertical, thumbAtTop, "顶部");

            vertical.setValue(vertical.getMax() / 2);
            scene.getRoot().layout();
            Region thumbAtMid = (Region) vertical.lookup(".thumb");
            assertThumbLongEnough(thumbAtMid, "中部");
            assertThumbInsideTrack(vertical, thumbAtMid, "中部");

            vertical.setValue(vertical.getMax());
            scene.getRoot().layout();
            Region thumbAtBottom = (Region) vertical.lookup(".thumb");
            assertThumbLongEnough(thumbAtBottom, "滚到末尾后");
            assertThumbInsideTrack(vertical, thumbAtBottom, "滚到末尾后");

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

    /** 滑块不得越出滚动条（含上下/左右边界），否则下拉/滚动会“跑出范围”。 */
    private static void assertThumbInsideTrack(ScrollBar bar, Region thumb, String when) {
        assertNotNull(thumb);
        Bounds b = thumb.getBoundsInParent();
        Region track = (Region) bar.lookup(".track");
        Bounds tb = track == null ? null : track.getBoundsInParent();
        String ctx = when + "：value=" + bar.getValue() + " max=" + bar.getMax()
                + " thumbY=" + b.getMinY() + " thumbH=" + b.getHeight()
                + " trackY=" + (tb == null ? "?" : tb.getMinY())
                + " trackH=" + (tb == null ? "?" : tb.getHeight())
                + " barH=" + bar.getHeight();
        if (bar.getOrientation() == Orientation.VERTICAL) {
            assertTrue(b.getMinY() >= -0.5,
                    ctx + "｜滑块顶端越出滚动条顶部（minY=" + b.getMinY() + "）");
            assertTrue(b.getMaxY() <= bar.getHeight() + 0.5,
                    ctx + "｜滑块底端越出滚动条底部（maxY=" + b.getMaxY() + "）");
        } else {
            assertTrue(b.getMinX() >= -0.5,
                    ctx + "｜滑块左端越出滚动条左缘（minX=" + b.getMinX() + "）");
            assertTrue(b.getMaxX() <= bar.getWidth() + 0.5,
                    ctx + "｜滑块右端越出滚动条右缘（maxX=" + b.getMaxX() + "）");
        }
    }
}
