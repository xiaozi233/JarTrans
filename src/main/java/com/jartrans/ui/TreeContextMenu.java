package com.jartrans.ui;

import com.jartrans.core.Project;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;

/**
 * 类树右键菜单：标记状态（含自动）/ 词典填充所选类 / 复制类路径 / 展开折叠。
 *
 * <p>从 MainApp 抽出的视图动作层；状态写入仍走 MainApp.mark（统一撤销记录与刷新）。</p>
 */
final class TreeContextMenu {

    private final MainApp app;
    /** 最近一次弹出的菜单（左键点树其它处时收起）。 */
    private ContextMenu lastMenu;

    TreeContextMenu(MainApp app) {
        this.app = app;
    }

    void show(TreeItem<String> node, double x, double y) {
        List<String> targets = targets(node);
        if (targets.isEmpty()) {
            return;
        }
        dismiss();
        ContextMenu menu = new ContextMenu();
        MenuItem markItem = new MenuItem("标记状态（" + targets.size() + " 个类）");
        MenuItem autoItem = new MenuItem("自动（按翻译进度）");
        autoItem.setOnAction(ev -> app.mark(targets, null));
        MenuItem todoItem = new MenuItem("未开始");
        todoItem.setOnAction(ev -> app.mark(targets, "todo"));
        MenuItem doingItem = new MenuItem("翻译中");
        doingItem.setOnAction(ev -> app.mark(targets, "doing"));
        MenuItem doneItem = new MenuItem("已完成");
        doneItem.setOnAction(ev -> app.mark(targets, "done"));
        MenuItem ignoreItem = new MenuItem("已忽略");
        ignoreItem.setOnAction(ev -> app.mark(targets, "ignore"));
        MenuItem fillItem = new MenuItem("用词典填充这些类");
        fillItem.setOnAction(ev -> fillClasses(targets));
        MenuItem copyItem = new MenuItem("复制类路径");
        copyItem.setOnAction(ev -> copyPaths(targets));
        MenuItem expandItem = new MenuItem("展开全部");
        expandItem.setOnAction(ev -> app.expandAll());
        MenuItem collapseItem = new MenuItem("折叠全部");
        collapseItem.setOnAction(ev -> app.collapseAll());
        menu.getItems()
                .addAll(
                        markItem,
                        autoItem,
                        todoItem,
                        doingItem,
                        doneItem,
                        ignoreItem,
                        new SeparatorMenuItem(),
                        fillItem,
                        copyItem,
                        new SeparatorMenuItem(),
                        expandItem,
                        collapseItem);
        lastMenu = menu;
        menu.show(app.classTree(), x, y);
    }

    /** 左键点树任意处时收起菜单（若正在显示）。 */
    void dismiss() {
        if (lastMenu != null && lastMenu.isShowing()) {
            lastMenu.hide();
        }
    }

    /** 右键目标类集合：多选时取选中的全部类节点，单选按节点类型派生。 */
    private List<String> targets(TreeItem<String> rightClicked) {
        List<TreeItem<String>> selected = app.classTree().getSelectionModel().getSelectedItems();
        if (selected != null && selected.size() > 1) {
            List<String> classes = new ArrayList<>();
            for (TreeItem<String> item : selected) {
                if (ClassTreeModel.TYPE_CLASS.equals(app.treeModel().kindOf(item))) {
                    classes.add(app.treeModel().targetOf(item));
                }
            }
            return classes;
        }
        return app.treeModel().targetsFor(rightClicked);
    }

    private void fillClasses(List<String> classes) {
        Project project = app.project();
        if (!project.hasJar()) {
            return;
        }
        int count = project.fillFromDictionary(classes);
        app.refreshAfterModelChange(app.editor().currentClass() != null
                && classes.contains(app.editor().currentClass()));
        app.setStatus("词典填充了 " + count + " 条未翻译字符串");
    }

    private void copyPaths(List<String> classes) {
        List<String> sorted = new ArrayList<>(classes);
        sorted.sort(String::compareTo);
        String text = String.join("\n", sorted);
        Clipboard.getSystemClipboard().setContent(Map.of(DataFormat.PLAIN_TEXT, text));
        app.setStatus("已复制 " + classes.size() + " 个类路径到剪贴板");
    }

    /** 状态名 -> 显示名（供菜单文案扩展时使用，保持与 ClassStates 单一来源一致）。 */
    static String labelOf(String state) {
        return state == null ? "自动判定" : MainApp.STATE_LABEL.get(state);
    }
}
