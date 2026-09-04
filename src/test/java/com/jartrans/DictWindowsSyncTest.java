package com.jartrans;

import com.jartrans.ui.DictDialog;
import com.jartrans.ui.DictManagerDialog;
import com.jartrans.ui.MainApp;
import javafx.application.Platform;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归测试：词典管理窗口新建词典后，各词典窗口（管理窗口自身 / 词典条目窗口的下拉与内容）
 * 必须即时同步，无需重开窗口；关闭后监听器不泄漏。
 */
class DictWindowsSyncTest {

    @TempDir
    static Path tmp;

    @Test
    void managerDialogRefreshesAfterCreate() throws Exception {
        System.setProperty("jartrans.dir", tmp.resolve("appdir-a").toString());
        FxSupport.runFx(() -> {
            MainApp app = new MainApp();
            app.start(new Stage());
            DictManagerDialog mgr = new DictManagerDialog(app);
            mgr.show();

            TableView<?> table = tableOf(mgr);
            int before = table.getItems().size();
            createNewDictViaUi(mgr);
            assertEquals(before + 1, table.getItems().size(),
                    "管理窗口新建词典后列表应立即多一行");
            assertTrue(rowsText(table).contains("✓ 测试词典"),
                    "新词典应为当前词典且即时可见");
            assertEquals("测试词典", app.project().dicts().activeName());
            assertEquals("测试词典", app.settings().getString("dictionary"));
            mgr.close();
        });
    }

    /** 核心场景：词典条目窗口内新建词典，条目窗口的下拉与内容必须即时出现新词典。 */
    @Test
    void dictDialogSyncsAfterCreateViaManager() throws Exception {
        System.setProperty("jartrans.dir", tmp.resolve("appdir-b").toString());
        FxSupport.runFx(() -> {
            MainApp app = new MainApp();
            app.start(new Stage());

            DictDialog dd = new DictDialog(app);
            dd.show();
            ComboBox<String> box = comboOf(dd);
            assertEquals(1, box.getItems().size(), "初始只有默认词典");
            assertEquals("默认词典", box.getValue());

            // 词典条目窗口里点「管理多个词典…」打开管理窗口
            Button manageBtn = findButton(dd, "管理多个词典…");
            assertNotNull(manageBtn);
            manageBtn.fire();
            DictManagerDialog mgr = findManagerWindow(app);
            assertNotNull(mgr);

            createNewDictViaUi(mgr);

            // ① 管理窗口自身即时出现
            assertTrue(rowsText(tableOf(mgr)).contains("✓ 测试词典"));
            // ② 词典条目窗口下拉即时包含新词典并切到它
            assertTrue(box.getItems().contains("测试词典"),
                    "listeners=" + listenersOf(app).size() + " box=" + box.getItems()
                            + " active=" + app.project().dicts().activeName()
                            + " ddTitle=" + dd.getTitle());
            assertEquals("测试词典", box.getValue(), "条目窗口应自动切到新词典");
            // ③ 标题与主窗同步
            assertTrue(dd.getTitle().contains("测试词典"), "条目窗口标题应切换到新词典");
            assertTrue(app.stage().getTitle() == null || !app.stage().getTitle().isEmpty());
            assertEquals("测试词典", app.project().dicts().activeName());

            // ④ 关闭窗口后监听器移除，不泄漏
            dd.close();
            mgr.close();
            assertEquals(0, listenersOf(app).size(), "窗口关闭后应注销词典监听器");
        });
    }

    /** 反向同步：条目窗口内切换词典 / 增删词条，管理窗口的激活标记与词条数必须跟随。 */
    @Test
    void dictEntryChangesRefreshManagerWindow() throws Exception {
        System.setProperty("jartrans.dir", tmp.resolve("appdir-c").toString());
        FxSupport.runFx(() -> {
            MainApp app = new MainApp();
            app.start(new Stage());

            DictDialog dd = new DictDialog(app);
            dd.show();
            DictManagerDialog mgr = new DictManagerDialog(app);
            mgr.show();

            // 先在管理窗口新建「测试词典」（自动成为激活词典）
            createNewDictViaUi(mgr);
            assertTrue(rowsText(tableOf(mgr)).contains("✓ 测试词典"));
            assertEquals("测试词典", comboOf(dd).getValue(), "条目窗口应已切到新词典");

            // 在条目窗口下拉切回「默认词典」→ 管理窗口的激活标记应跟随
            comboOf(dd).setValue("默认词典");
            assertEquals("默认词典", app.project().dicts().activeName());
            assertTrue(rowsText(tableOf(mgr)).contains("✓ 默认词典"),
                    "切回默认词典后管理窗口应更新激活标记");

            // 在条目窗口添加一条词条 → 管理窗口该行词条数应 +1
            addEntryViaUi(dd, "hello", "你好");
            assertTrue(rowsText(tableOf(mgr)).contains("count=1"),
                    "添加词条后管理窗口的词条数列应更新");

            dd.close();
            mgr.close();
            assertEquals(0, listenersOf(app).size(), "窗口关闭后应注销词典监听器");
        });
    }

    // ---------- 辅助 ----------

    private static void createNewDictViaUi(DictManagerDialog mgr) throws Exception {
        AtomicBoolean finished = new AtomicBoolean(false);
        Thread driver = new Thread(() -> {
            for (int i = 0; i < 200 && !finished.get(); i++) {
                Platform.runLater(() -> {
                    try {
                        if (fillTextInputDialogs("测试词典")) {
                            finished.set(true);
                        }
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                });
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "dialog-driver");
        driver.setDaemon(true);
        driver.start();
        Button createBtn = findButton(mgr, "新建…");
        assertNotNull(createBtn, "应找到「新建…」按钮");
        createBtn.fire();
    }

    /** 自动填写当前显示中的文本输入框并确认；同时打印出现的警告内容以便诊断。 */
    private static boolean fillTextInputDialogs(String value) {
        for (Window w : Window.getWindows()) {
            if (!w.isShowing() || w.getScene() == null
                    || !(w.getScene().getRoot() instanceof DialogPane pane)) {
                continue;
            }
            TextField tf = (TextField) pane.lookup(".text-field");
            Button ok = (Button) pane.lookupButton(ButtonType.OK);
            if (tf != null) {
                tf.setText(value);
                if (ok != null) {
                    ok.fire();
                    return true;
                }
            } else if (ok != null) {
                System.out.println("[DictWindowsSyncTest] Alert："
                        + pane.getContentText());
                ok.fire();
                return true;
            }
        }
        return false;
    }

    /** 在词典条目窗口表单里填原/译文并点「添加 / 更新」。 */
    private static void addEntryViaUi(DictDialog dlg, String orig, String trans) throws Exception {
        Field fo = DictDialog.class.getDeclaredField("origField");
        fo.setAccessible(true);
        Field ft = DictDialog.class.getDeclaredField("transField");
        ft.setAccessible(true);
        ((TextField) fo.get(dlg)).setText(orig);
        ((TextField) ft.get(dlg)).setText(trans);
        Button saveBtn = findButton(dlg, "添加 / 更新");
        assertNotNull(saveBtn, "应找到「添加 / 更新」按钮");
        saveBtn.fire();
    }

    private static DictManagerDialog findManagerWindow(MainApp app) {
        for (Window w : Window.getWindows()) {
            if (w instanceof DictManagerDialog d && w.isShowing()
                    && d.getOwner() == app.stage()) {
                return d;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static TableView<Object> tableOf(DictManagerDialog dlg) throws Exception {
        Field f = DictManagerDialog.class.getDeclaredField("table");
        f.setAccessible(true);
        return (TableView<Object>) f.get(dlg);
    }

    @SuppressWarnings("unchecked")
    private static ComboBox<String> comboOf(DictDialog dlg) throws Exception {
        Field f = DictDialog.class.getDeclaredField("dictBox");
        f.setAccessible(true);
        return (ComboBox<String>) f.get(dlg);
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Runnable> listenersOf(MainApp app) throws Exception {
        Field f = MainApp.class.getDeclaredField("dictsListeners");
        f.setAccessible(true);
        return (java.util.List<Runnable>) f.get(app);
    }

    private static Button findButton(javafx.stage.Stage win, String text) {
        for (javafx.scene.Node n : win.getScene().getRoot().lookupAll(".button")) {
            if (n instanceof Button b && text.equals(b.getText())) {
                return b;
            }
        }
        return null;
    }

    private static String rowsText(TableView<?> table) {
        StringBuilder sb = new StringBuilder();
        for (Object o : table.getItems()) {
            sb.append(o).append('\n');
        }
        return sb.toString();
    }
}
