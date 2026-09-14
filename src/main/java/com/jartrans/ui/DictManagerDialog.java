package com.jartrans.ui;

import com.jartrans.core.DictionaryManager;
import java.io.File;
import java.nio.file.Path;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;

/** 多词典管理：新建 / 重命名 / 删除 / 导入 / 导出 / 切换当前词典。对应 dict_manager_dialog.py。 */
public class DictManagerDialog extends AppWindow {

    private final DictionaryManager dicts;
    private final TableView<DictRow> table = new TableView<>();
    private final Label infoLabel = new Label("");
    /** 词典结构/激活变化时刷新本窗口列表（多实例窗口保持同步）。 */
    private final Runnable dictsListener = this::refresh;

    private record DictRow(String name, int count, String file) {
    }

    public DictManagerDialog(MainApp app) {
        super(app, "管理多个词典", 760, 470, 620, 400, Modality.NONE);
        this.dicts = app.project().dicts();

        BorderPane root = rootPane();

        TableColumn<DictRow, String> colName =
                TableColumns.text("名称", 200, DictRow::name);
        TableColumn<DictRow, String> colCount =
                TableColumns.text("词条数", 80, r -> String.valueOf(r.count()));
        TableColumn<DictRow, String> colFile =
                TableColumns.text("文件", 400, DictRow::file);
        //noinspection unchecked
        table.getColumns().addAll(colName, colCount, colFile);
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                activate();
            }
        });

        HBox bar = new HBox(6);
        bar.setPadding(new Insets(6, 0, 6, 0));
        Button createBtn = new Button("新建…");
        createBtn.setOnAction(e -> create());
        Button renameBtn = new Button("重命名…");
        renameBtn.setOnAction(e -> rename());
        Button removeBtn = new Button("删除");
        removeBtn.setOnAction(e -> remove());
        Button importBtn = new Button("导入 JSON…");
        importBtn.setOnAction(e -> importJson());
        Button exportBtn = new Button("导出 JSON…");
        exportBtn.setOnAction(e -> exportJson());
        Button activateBtn = new Button("设为当前词典");
        activateBtn.setOnAction(e -> activate());
        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> close());
        Separator sep1 = new Separator();
        sep1.setOrientation(Orientation.VERTICAL);
        Separator sep2 = new Separator();
        sep2.setOrientation(Orientation.VERTICAL);
        HBox right = new HBox(closeBtn);
        right.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(right, Priority.ALWAYS);
        bar.getChildren().addAll(createBtn, renameBtn, removeBtn, sep1, importBtn,
                exportBtn, sep2, activateBtn, right);
        root.setCenter(new VBox(table, bar));

        Label tip = new Label("词典文件保存在程序目录的 dictionaries/ 下，可直接复制分享；"
                + "双击列表项即可切换当前词典。");
        tip.setPadding(new Insets(4, 0, 0, 0));
        HBox foot = new HBox(infoLabel);
        foot.setPadding(new Insets(4, 0, 0, 0));
        root.setBottom(new VBox(foot, tip));

        mount(root);
        app.addDictsListener(dictsListener);
        setOnHidden(e -> app.removeDictsListener(dictsListener));
        refresh();
    }

    private String selectedName() {
        DictRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return null;
        }
        // 行名带当前词典的「✓ 」前缀，比较时剥掉
        String raw = row.name();
        return raw.startsWith("✓ ") ? raw.substring(2) : raw;
    }

    private void refresh() {
        String sel = selectedName();
        table.getItems().clear();
        int index = 0;
        for (String name : dicts.names()) {
            Path path = dicts.pathOf(name);
            String mark = name.equals(dicts.activeName()) ? "✓ " : "";
            table.getItems().add(new DictRow(mark + name, dicts.count(name),
                    path == null ? "" : path.getFileName().toString()));
            if (name.equals(sel)) {
                table.getSelectionModel().select(index);
            }
            index += 1;
        }
        infoLabel.setText("共 " + dicts.names().size() + " 个词典，当前：" + dicts.activeName()
                + "（" + dicts.count(dicts.activeName()) + " 条）");
    }

    /** 刷新主窗下拉/统计，并通知所有已打开的词典窗口（含本窗口）同步列表。 */
    private void syncApp() {
        app.notifyDictsChanged();
    }

    /** 切换当前词典并同步全部词典窗口与主窗（新建/删除/激活三处共用）。 */
    private void switchTo(String name) {
        try {
            app.project().useDictionary(name);
            app.settings().setOrIgnore("dictionary", name);
        } catch (Exception ignored) {
            // 切换失败不阻断：词典已变更，界面照常刷新
        }
        if (app.editor().currentClass() != null) {
            app.editor().refreshRows();
        }
        syncApp();
    }

    private void create() {
        String name = Dialogs.ask("新建词典", "词典名称：", "");
        if (name == null || name.isBlank()) {
            return;
        }
        name = name.trim();
        try {
            dicts.create(name);
            dicts.setActive(name);
        } catch (Exception exc) {
            Dialogs.warn("提示", exc.getMessage());
            return;
        }
        switchTo(name);
    }

    private void rename() {
        String name = selectedName();
        if (name == null) {
            return;
        }
        String newName = Dialogs.ask("重命名", "新的名称：", name);
        if (newName == null || newName.isBlank() || newName.equals(name)) {
            return;
        }
        try {
            dicts.rename(name, newName.trim());
        } catch (Exception exc) {
            Dialogs.warn("提示", exc.getMessage());
            return;
        }
        if (dicts.activeName().equals(newName.trim())) {
            app.settings().setOrIgnore("dictionary", newName.trim());
        }
        syncApp();
    }

    private void remove() {
        String name = selectedName();
        if (name == null) {
            return;
        }
        if (!Dialogs.confirm("确认删除",
                "删除词典「" + name + "」？该词典的 " + dicts.count(name)
                        + " 条词条会被一并删除，且无法恢复。")) {
            return;
        }
        try {
            dicts.remove(name);
        } catch (Exception exc) {
            Dialogs.warn("提示", exc.getMessage());
            return;
        }
        switchTo(dicts.activeName());
    }

    private void importJson() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择词典 JSON");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JSON 文件", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(this);
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        String base = path.getFileName().toString().replaceFirst("\\.[^.]*$", "");
        String name = Dialogs.ask("导入词典", "导入为：", base);
        if (name == null || name.isBlank()) {
            return;
        }
        boolean merge = false;
        if (dicts.exists(name)) {
            merge = Dialogs.confirm("名称已存在",
                    "已存在名为「" + name + "」的词典。\n\n"
                            + "「是」= 合并进去（同名词条会被覆盖）\n"
                            + "「否」= 另建一个新词典");
        }
        DictionaryManager.ImportResult result;
        try {
            result = dicts.importFile(path, name, merge);
        } catch (Exception exc) {
            Dialogs.error("错误", exc.getMessage());
            return;
        }
        syncApp();
        Dialogs.info("完成", "已" + (result.merged() ? "合并到" : "新建") + "词典「"
                + result.name() + "」，处理 " + result.count() + " 条。");
    }

    private void exportJson() {
        String name = selectedName();
        if (name == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出词典");
        chooser.setInitialFileName(name + ".json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
        File file = chooser.showSaveDialog(this);
        if (file == null) {
            return;
        }
        try {
            int count = dicts.exportFile(name, file.toPath());
            Dialogs.info("完成", "已导出 " + count + " 条到：\n" + file.toPath());
        } catch (Exception exc) {
            Dialogs.error("错误", exc.getMessage());
        }
    }

    private void activate() {
        String name = selectedName();
        if (name == null || name.equals(dicts.activeName())) {
            return;
        }
        switchTo(name);
        app.setStatus("已切换到词典「" + name + "」");
    }
}
