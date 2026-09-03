package com.jartrans;

import com.jartrans.core.Project;
import com.jartrans.ui.EditorPane;
import com.jartrans.ui.MainApp;
import com.jartrans.ui.PreferencesDialog;
import com.jartrans.ui.SearchWindow;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回归测试：类内查找/替换、快捷键自定义持久化、全局搜索预填与首选项（FX 线程）。 */
class UiSearchReplaceTest {

    @TempDir
    static Path tmp;

    @Test
    void findReplaceShortcutsAndSearchWindow() throws Exception {
        System.setProperty("jartrans.dir", tmp.resolve("appdir").toString());
        Path jar = TestClasses.writeSampleJar(tmp, "sample.jar", true);
        FxSupport.runFx(() -> {
            MainApp app = new MainApp();
            app.start(new Stage());
            app.project().openJar(jar);
            String cls = "demo/Test.class";
            EditorPane ed = app.editor();
            ed.showClass(cls);
            int total = ed.visibleRowCount();
            assertTrue(total >= 2, "样本类应有多条字符串");

            // --- Ctrl+F 类内查找：输入即过滤 ---
            ed.beginFind();
            assertTrue(ed.findBarShowing());
            ed.findFieldNode().setText("hello");
            int matches = ed.visibleRowCount();
            assertTrue(matches >= 1 && matches < total, "查找 hello 应过滤行");

            // 记录替换前后全部译文，验证只改匹配行
            Map<String, String> before = new LinkedHashMap<>();
            List<String> matchedOrigs = new ArrayList<>();
            for (Project.TextCount tc : app.project().texts(cls)) {
                before.put(tc.text(), app.project().effective(cls).getOrDefault(tc.text(), ""));
                if (tc.text().toLowerCase().contains("hello")
                        && !app.project().isSkipped(tc.text())) {
                    matchedOrigs.add(tc.text());
                }
            }
            assertFalse(matchedOrigs.isEmpty());

            // --- Ctrl+R 替换：全部替换当前查找结果 ---
            ed.beginReplace();
            assertTrue(ed.findBarShowing());
            ed.replaceFieldNode().setText("你好，世界！");
            ed.replaceAll();
            for (String orig : matchedOrigs) {
                assertEquals("你好，世界！",
                        app.project().effective(cls).getOrDefault(orig, ""),
                        "匹配行的译文应被替换");
            }
            // 未匹配行不变
            for (Map.Entry<String, String> e : before.entrySet()) {
                if (!matchedOrigs.contains(e.getKey())) {
                    assertEquals(e.getValue(),
                            app.project().effective(cls).getOrDefault(e.getKey(), ""),
                            "未匹配行不应被改动");
                }
            }
            // 清空查找词 → 恢复全部行
            ed.findFieldNode().setText("");
            assertEquals(total, ed.visibleRowCount(), "清空查找词应显示全部行");

            // --- 快捷键自定义与持久化 ---
            assertEquals("Ctrl+F", app.shortcutText("find"));
            assertTrue(app.applyShortcut("find", "Ctrl+Alt+K"));
            assertEquals("Ctrl+Alt+K", app.shortcutText("find"));
            assertFalse(app.applyShortcut("gsearch", "Ctrl+Alt+K"), "重复组合应被拒绝");
            app.resetShortcut("find");
            assertEquals("Ctrl+F", app.shortcutText("find"));
            assertEquals("Ctrl+F", new com.jartrans.core.Settings().getString("key_find"));

            // --- 全局搜索窗口：预填关键词并即时出结果 ---
            SearchWindow win = new SearchWindow(app, "hello");
            win.show();
            win.setQuery("hello");
            assertEquals(1, win.resultCount(), "hello 应匹配 1 条结果");
            win.hide();

            // 首选项可正常打开（构建后关闭）
            app.openPreferences();
            PreferencesDialog pref = app.preferencesDialog();
            assertTrue(pref != null);
            pref.hide();

            // --- 图例：显示开关（默认开，可关可开） ---
            assertTrue(app.legendVisible(), "图例默认应显示");
            app.applyLegendVisible(false);
            assertFalse(app.legendVisible(), "图例可隐藏");
            app.applyLegendVisible(true);
            assertTrue(app.legendVisible(), "图例可再次显示");

            // --- 译文自动保存：输入后 flush/切换即落库 ---
            assertTrue(app.settings().getBool("auto_save_translation"), "自动保存默认应开启");
            java.lang.reflect.Field f = EditorPane.class.getDeclaredField("editor");
            f.setAccessible(true);
            javafx.scene.control.TextArea ta = (javafx.scene.control.TextArea) f.get(ed);
            String rowOrig = ed.selectedOrig();
            assertTrue(rowOrig != null && !rowOrig.isEmpty());
            ed.selectRow(rowOrig); // 保证编辑区为该行
            ta.setText("自动保存测试译文");
            ed.flushEdit();
            assertEquals("自动保存测试译文",
                    app.project().effective(cls).getOrDefault(rowOrig, ""),
                    "flushEdit 应把编辑区文本写入工程");
            ta.setText("切换行自动保存");
            ed.showClass(cls); // 模拟切行/切类 → 自动落库
            assertEquals("切换行自动保存",
                    app.project().effective(cls).getOrDefault(rowOrig, ""),
                    "切换行/类前应自动保存译文");
            // 自动保存开关关闭时不再落库
            app.settings().set("auto_save_translation", false);
            ed.selectRow(rowOrig);
            ta.setText("不应保存的内容");
            ed.showClass(cls);
            assertEquals("切换行自动保存",
                    app.project().effective(cls).getOrDefault(rowOrig, ""),
                    "关闭自动保存后切换不应落库");
            app.settings().set("auto_save_translation", true);

            // --- 双击跳源码默认开启 ---
            assertTrue(app.settings().getBool("dblclick_source"), "双击跳转默认应开启");
        });
    }
}
