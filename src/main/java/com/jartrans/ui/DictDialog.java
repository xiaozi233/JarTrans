package com.jartrans.ui;

import com.jartrans.core.Dictionary;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/** 词典条目编辑对话框（可切换当前编辑的词典）。对应 gui/dict_dialog.py。 */
public class DictDialog extends AppWindow {

    private final ComboBox<String> dictBox = new ComboBox<>();
    private final TextField queryField = new TextField();
    private final TableView<Map.Entry<String, String>> table = new TableView<>();
    private final TextField origField = new TextField();
    private final TextField transField = new TextField();
    private final Label countLabel = new Label("");
    /** 词典结构/激活变化（如在「管理多个词典」里新建）后同步本窗口的下拉与内容。 */
    private final Runnable dictsListener;

    public DictDialog(MainApp app) {
        super(app, "词典条目", 820, 560, 620, 420, Modality.NONE);
        dictsListener = () -> {
            if (app.project().dicts() == null) {
                return;
            }
            dictBox.getItems().setAll(app.project().dicts().names());
            dictBox.setValue(app.project().dicts().activeName());
            refresh();
        };

        BorderPane root = rootPane();

        HBox top = new HBox(8);
        top.setPadding(new Insets(0, 0, 6, 0));
        top.setAlignment(Pos.CENTER_LEFT);
        if (app.project().dicts() != null) {
            dictBox.getItems().setAll(app.project().dicts().names());
            dictBox.getSelectionModel().select(app.project().dicts().activeName());
            dictBox.valueProperty().addListener((o, ov, nv) -> switchDict());
            Button manageBtn = new Button("管理多个词典…");
            manageBtn.setOnAction(e -> app.manageDictionaries());
            top.getChildren().addAll(new Label("当前词典："), dictBox, manageBtn);
        }
        HBox topRight = new HBox(countLabel);
        topRight.setAlignment(Pos.CENTER_RIGHT);
        HBox.setHgrow(topRight, Priority.ALWAYS);
        top.getChildren().add(topRight);
        root.setTop(top);

        HBox search = new HBox(6);
        search.setPadding(new Insets(0, 0, 6, 0));
        queryField.setPrefWidth(280);
        queryField.setOnAction(e -> refresh());
        Button searchBtn = new Button("搜索");
        searchBtn.setOnAction(e -> refresh());
        Button allBtn = new Button("显示全部");
        allBtn.setOnAction(e -> {
            queryField.clear();
            refresh();
        });
        search.getChildren().addAll(new Label("搜索："), queryField, searchBtn, allBtn);

        TableColumn<Map.Entry<String, String>, String> colOrig =
                TableColumns.text("原字符串", 380, Map.Entry::getKey);
        TableColumn<Map.Entry<String, String>, String> colTrans =
                TableColumns.text("译文", 380, Map.Entry::getValue);
        //noinspection unchecked
        table.getColumns().addAll(colOrig, colTrans);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        // 多选：删除按钮可批量删除选中词条
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv != null) {
                origField.setText(nv.getKey());
                transField.setText(app.project().dictionary().get(nv.getKey()));
            }
        });

        VBox mid = new VBox(search, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setCenter(mid);

        // ---- 编辑条目 ----
        HBox form = new HBox(8);
        form.setPadding(new Insets(8, 0, 0, 0));
        form.setAlignment(Pos.CENTER_LEFT);
        origField.setPrefWidth(320);
        transField.setPrefWidth(320);
        VBox origBox = new VBox(new Label("原字符串："), origField);
        VBox transBox = new VBox(new Label("译文："), transField);
        Button saveBtn = new Button("添加 / 更新");
        saveBtn.setOnAction(e -> saveEntry());
        Button delBtn = new Button("删除选中");
        delBtn.setOnAction(e -> deleteEntry());
        VBox btnBox = new VBox(4, saveBtn, delBtn);
        form.getChildren().addAll(origBox, transBox, btnBox);
        TitledPane formPane = new TitledPane("编辑条目", form);
        formPane.setCollapsible(false);
        root.setBottom(formPane);

        setOnCloseRequest(e -> closeAndSave());
        mount(root);
        if (app.project().dicts() != null) {
            app.addDictsListener(dictsListener);
            setOnHidden(e -> app.removeDictsListener(dictsListener));
        }
        refresh();
    }

    private void switchDict() {
        String name = dictBox.getValue();
        if (name == null || app.project().dicts() == null
                || name.equals(app.project().dicts().activeName())) {
            return;
        }
        try {
            app.project().useDictionary(name);
        } catch (Exception ignored) {
            // 切换失败不阻断：下拉已切换，界面照常刷新
        }
        app.settings().setOrIgnore("dictionary", name);
        if (app.editor().currentClass() != null) {
            app.editor().refreshRows();
        }
        // 主窗下拉/统计与所有词典窗口（含本窗口、管理窗口）一起同步
        app.notifyDictsChanged();
    }

    private void refresh() {
        String keyword = queryField.getText().trim();
        Dictionary dic = app.project().dictionary();
        Map<String, String> data = keyword.isEmpty() ? dic.entries() : dic.search(keyword);
        table.getItems().clear();
        data.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(table.getItems()::add);
        String label = app.project().dictionaryLabel();
        setTitle("词典条目 — " + label);
        countLabel.setText("「" + label + "」共 " + dic.size() + " 条，当前列出 " + data.size() + " 条");
    }

    private void saveEntry() {
        String orig = origField.getText();
        String trans = transField.getText();
        if (orig.isEmpty() || trans.isEmpty()) {
            Dialogs.warn("提示", "原字符串和译文都不能为空。");
            return;
        }
        app.project().dictionary().add(orig, trans);
        app.project().dictionary().saveQuietly();
        refresh();
        // 词条数变化 → 管理窗口「词条数」列与主窗统计跟随刷新
        app.notifyDictsChanged();
    }

    private void deleteEntry() {
        List<Map.Entry<String, String>> selected =
                new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }
        String preview = Texts.displayText(selected.get(0).getKey())
                .substring(0, Math.min(100, selected.get(0).getKey().length()));
        String message = selected.size() == 1
                ? "删除该词条？\n\n" + preview
                : "删除选中的 " + selected.size() + " 条词条？\n\n" + preview + "\n…";
        if (Dialogs.confirm("确认", message)) {
            for (Map.Entry<String, String> entry : selected) {
                app.project().dictionary().remove(entry.getKey());
            }
            app.project().dictionary().saveQuietly();
            refresh();
            // 词条数变化 → 管理窗口「词条数」列与主窗统计跟随刷新
            app.notifyDictsChanged();
        }
    }

    private void closeAndSave() {
        app.project().dictionary().saveQuietly();
        app.updateStats();
        close();
    }
}
