package com.jartrans.ui;

import com.jartrans.core.Project;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

/** 中间的字符串表格 + 底部多行编辑区（对应 gui/editor.py）。 */
public class EditorPane extends BorderPane {

    /** 表格行模型。 */
    public static final class Row {
        public final String orig;
        public final String trans;
        public final String statusKey;
        public final String count;
        public final String methods;
        public final boolean internal;

        Row(String orig, String trans, String statusKey, String count,
            String methods, boolean internal) {
            this.orig = orig;
            this.trans = trans;
            this.statusKey = statusKey;
            this.count = count;
            this.methods = methods;
            this.internal = internal;
        }
    }

    public static final Map<String, String> STATUS_TEXT = Map.of(
            "untranslated", "未翻译",
            "translated", "已翻译",
            "auto", "自动填充",
            "internal", "内部·只读",
            "skipped", "不翻译");

    private MainApp app;
    private String currentClass;
    private String editingOrig;
    private boolean editingInternal;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final TextArea editor = new TextArea();
    private final Label hint = new Label("");
    private final javafx.scene.shape.Circle stateDot = new javafx.scene.shape.Circle(4);
    private final ComboBox<String> classStateBox = new ComboBox<>();
    private final CheckBox onlyUntranslated = new CheckBox("只看未翻译");
    private final CheckBox saveDict = new CheckBox("保存时记入词典");
    /** 程序化同步下拉选中值时置位，避免 valueProperty 监听误触发写状态。 */
    private boolean syncingState;

    public EditorPane() {
        buildUi();
    }

    public void setApp(MainApp app) {
        this.app = app;
        onlyUntranslated.setSelected(app.settings().getBool("only_untranslated"));
        saveDict.setSelected(app.settings().getBool("save_to_dict"));
    }

    private void buildUi() {
        setPadding(new Insets(4));

        // ---- 顶部：类状态 + 只看未翻译 ----
        HBox head = new HBox(8);
        head.setPadding(new Insets(2, 2, 6, 2));
        head.setAlignment(Pos.CENTER_LEFT);
        classStateBox.getItems().addAll("自动", "未开始", "翻译中", "已完成", "已忽略");
        classStateBox.setPrefWidth(112);
        classStateBox.getSelectionModel().selectFirst();
        classStateBox.valueProperty().addListener((o, ov, nv) -> onClassStateBox());
        stateDot.getStyleClass().add("state-dot");
        head.getChildren().addAll(new Label("类状态："), classStateBox, stateDot,
                new Separator(), onlyUntranslated);
        onlyUntranslated.setOnAction(e -> {
            setQuiet("only_untranslated", onlyUntranslated.isSelected());
            refreshRows();
        });

        setTop(head);

        // ---- 表格 ----
        TableColumn<Row, String> colOrig = new TableColumn<>("原字符串");
        colOrig.setCellValueFactory(d -> new SimpleStringProperty(Texts.displayText(d.getValue().orig)));
        colOrig.setPrefWidth(340);
        TableColumn<Row, String> colTrans = new TableColumn<>("译文");
        colTrans.setCellValueFactory(d -> new SimpleStringProperty(Texts.displayText(d.getValue().trans)));
        colTrans.setPrefWidth(260);
        TableColumn<Row, String> colStatus = new TableColumn<>("状态");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(STATUS_TEXT.get(d.getValue().statusKey)));
        colStatus.setPrefWidth(70);
        colStatus.setStyle("-fx-alignment: CENTER;");
        TableColumn<Row, String> colCount = new TableColumn<>("次数");
        colCount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().count));
        colCount.setPrefWidth(42);
        colCount.setStyle("-fx-alignment: CENTER;");
        TableColumn<Row, String> colMethods = new TableColumn<>("所在方法");
        colMethods.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().methods));
        colMethods.setPrefWidth(130);

        List<TableColumn<Row, String>> allCols =
                List.of(colOrig, colTrans, colStatus, colCount, colMethods);
        //noinspection unchecked
        table.getColumns().addAll(allCols);
        // 拖列宽只动相邻右列（不影响左侧栏目的宽度，总宽不变）
        table.setColumnResizePolicy(
                javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_NEXT_COLUMN);
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                setTextFill(null);
                if (item != null && !empty && app != null) {
                    String color = "skipped".equals(item.statusKey)
                            ? app.theme().color("fg_muted")
                            : app.theme().statusColor(item.statusKey);
                    setTextFill(javafx.scene.paint.Color.web(color));
                }
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> onSelect());
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                viewInSource();
            }
        });

        // ---- 右键菜单：表头=显示/隐藏列；数据行=不翻译/翻译 ----
        javafx.scene.control.ContextMenu colMenu = new javafx.scene.control.ContextMenu();
        javafx.scene.control.ContextMenu rowMenu = new javafx.scene.control.ContextMenu();
        table.setOnContextMenuRequested(e -> {
            javafx.scene.Node t = e.getPickResult().getIntersectedNode();
            if (t == null) {
                t = e.getTarget() instanceof javafx.scene.Node n ? n : null;
            }
            if (t != null && isInsideHeader(t)) {
                long visible = allCols.stream().filter(TableColumn::isVisible).count();
                colMenu.getItems().clear();
                for (TableColumn<Row, String> c : allCols) {
                    javafx.scene.control.CheckMenuItem mi =
                            new javafx.scene.control.CheckMenuItem(c.getText());
                    mi.setSelected(c.isVisible());
                    mi.setDisable(c.isVisible() && visible == 1); // 至少保留一列
                    mi.setOnAction(ev -> {
                        if (mi.isSelected()) {
                            c.setVisible(true);
                        } else if (visible > 1) {
                            c.setVisible(false);
                        } else {
                            mi.setSelected(true);
                        }
                    });
                    colMenu.getItems().add(mi);
                }
                colMenu.show(table, e.getScreenX(), e.getScreenY());
            } else {
                showRowMenu(rowMenu, e.getScreenX(), e.getScreenY());
            }
        });

        // ---- 表格(上) 与 编辑区(下) 之间的可拖动分栏 ----
        javafx.scene.control.SplitPane vSplit = new javafx.scene.control.SplitPane();
        vSplit.setOrientation(javafx.geometry.Orientation.VERTICAL);
        vSplit.setDividerPositions(0.62);
        vSplit.getItems().add(table);

        HBox buttons = new HBox(6);
        buttons.setPadding(new Insets(4, 6, 2, 6));
        buttons.setAlignment(Pos.CENTER_LEFT);
        Button saveBtn = new Button("保存译文 (Ctrl+S)");
        saveBtn.getStyleClass().add("accent");
        saveBtn.setOnAction(e -> saveTranslation());
        Button useDictBtn = new Button("使用词典译文");
        useDictBtn.setOnAction(e -> useDictionary());
        Button clearBtn = new Button("清空译文");
        clearBtn.setOnAction(e -> clearTranslation());
        Button srcBtn = new Button("在源码中查看");
        srcBtn.setOnAction(e -> viewInSource());
        hint.setStyle("-fx-text-fill: -jr-muted;");
        buttons.getChildren().addAll(saveBtn, useDictBtn, clearBtn, srcBtn, saveDict, hint);
        saveDict.setOnAction(e -> setQuiet("save_to_dict", saveDict.isSelected()));

        VBox editBox = new VBox(buttons, editor);
        editBox.setPadding(new Insets(0, 6, 6, 6));
        VBox.setVgrow(editor, Priority.ALWAYS);
        editor.setPrefRowCount(4);
        editor.setMinHeight(72);
        editor.setWrapText(true);
        editor.setStyle("-fx-font-family: 'Microsoft YaHei UI', monospace;");

        javafx.scene.layout.BorderPane editPanel = new javafx.scene.layout.BorderPane();
        Label editHeader = new Label("编辑区");
        editHeader.getStyleClass().add("pane-header");
        editPanel.setTop(editHeader);
        editPanel.setCenter(editBox);
        vSplit.getItems().add(editPanel);

        BorderPane.setMargin(vSplit, new Insets(0, 0, 4, 0));
        setCenter(vSplit);

        editor.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN).match(e)
                    || new KeyCodeCombination(KeyCode.S, KeyCombination.SHIFT_DOWN, KeyCombination.CONTROL_DOWN).match(e)) {
                saveTranslation();
                e.consume();
            }
        });
        table.setOnKeyPressed(e -> {
            if (new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN).match(e)) {
                saveTranslation();
                e.consume();
            }
        });
    }

    private void setQuiet(String key, boolean value) {
        try {
            app.settings().set(key, value);
        } catch (Exception ignored) {
            // 设置写盘失败不影响界面
        }
    }

    // ---------- 类状态控件 ----------

    private void onClassStateBox() {
        if (currentClass == null || app == null || syncingState) {
            return;
        }
        String label = classStateBox.getValue();
        String state = switch (label) {
            case "未开始" -> "todo";
            case "翻译中" -> "doing";
            case "已完成" -> "done";
            case "已忽略" -> "ignore";
            default -> null;
        };
        try {
            app.project().setClassStatus(currentClass, state);
        } catch (Exception ignored) {
            // 状态写盘失败不阻断
        }
        app.refreshClassNodes();
        app.updateStats();
        refreshClassStateUi();
    }

    private String currentStateKey() {
        if (currentClass == null || app == null) {
            return null;
        }
        String manual = app.project().classStatus().get(currentClass);
        if (manual != null && Project.MANUAL_STATES.contains(manual)) {
            return manual;
        }
        return app.project().autoClassState(currentClass);
    }

    void refreshClassStateUi() {
        // 状态点颜色由 CSS（.state-dot.cell-state-*）驱动，这里只负责换状态类
        stateDot.getStyleClass().removeIf(c -> c.startsWith("cell-state-"));
        if (currentClass == null || app == null) {
            classStateBox.getSelectionModel().selectFirst();
            return;
        }
        String key = currentStateKey();
        Map<String, String> mapping = Map.of(
                "todo", "未开始", "doing", "翻译中", "done", "已完成",
                "ignore", "已忽略", "empty", "无字符串");
        String label = mapping.get(key);
        // 「无字符串」不是可选手动状态：下拉保持「自动」，仅状态点标灰
        if (label == null || !classStateBox.getItems().contains(label)) {
            label = "自动";
        }
        syncingState = true;
        try {
            classStateBox.getSelectionModel().select(label);
        } finally {
            syncingState = false;
        }
        if (key != null) {
            stateDot.getStyleClass().add("cell-state-" + key);
        }
    }

    // ---------- 展示 ----------

    public void showClass(String cls) {
        currentClass = cls;
        rows.clear();
        if (cls == null || app == null || !app.project().classes().containsKey(cls)) {
            setEditor(null, false);
            refreshClassStateUi();
            return;
        }
        Project p = app.project();
        Map<String, String> eff = p.effective(cls);
        for (Project.TextCount tc : p.texts(cls)) {
            if (onlyUntranslated.isSelected() && eff.get(tc.text()) != null) {
                continue;
            }
            appendRow(tc.text(), tc.count(), eff.getOrDefault(tc.text(), ""), false);
        }
        if (app.showInternal() && !onlyUntranslated.isSelected()) {
            for (String text : p.internalTexts(cls)) {
                appendRow(text, 0, "", true);
            }
        }
        setEditor(null, false);
        refreshClassStateUi();
    }

    private void appendRow(String orig, int cnt, String trans, boolean internal) {
        String status;
        if (internal) {
            status = "internal";
        } else if (app.project().isSkipped(orig)) {
            status = "skipped";
        } else {
            status = app.project().status(currentClass, orig).key;
        }
        String methods = internal ? "" : String.join(", ", app.project().methodNames(currentClass, orig));
        rows.add(new Row(orig, trans, status, internal ? "" : String.valueOf(cnt),
                methods, internal));
    }

    public boolean selectRow(String orig) {
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            if (r.orig.equals(orig) && !r.internal) {
                table.getSelectionModel().clearAndSelect(i);
                table.scrollTo(i);
                return true;
            }
        }
        return false;
    }

    public void refreshRows() {
        String cls = currentClass;
        showClass(cls);
    }

    // ---------- 编辑 ----------

    private void onSelect() {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        setEditor(row.orig, row.internal);
    }

    private void setEditor(String orig, boolean internal) {
        editingOrig = orig;
        editingInternal = internal;
        editor.setEditable(true);
        editor.setText("");
        hint.setText("");
        if (orig == null) {
            return;
        }
        if (internal) {
            editor.setText(orig);
            editor.setEditable(false);
            hint.setText("内部 UTF8 条目（类名/方法签名等），修改会破坏字节码，已禁止");
            return;
        }
        Project p = app.project();
        if (p.isSkipped(orig)) {
            editor.setText(orig);
            editor.setEditable(false);
            hint.setText("该文本已被标记「不翻译」：导出/导入/词典填充都会保留原文。右键可恢复。");
            return;
        }
        editor.setText(p.effective(currentClass).getOrDefault(orig, ""));
        String suggestion = p.dictionary().get(orig);
        hint.setText(suggestion != null ? "词典建议：" + Texts.displayText(suggestion) : "");
    }

    public void saveTranslation() {
        if (currentClass == null || editingOrig == null || app == null) {
            return;
        }
        if (editingLocked()) {
            return;
        }
        String orig = editingOrig;
        String trans = editor.getText();
        Project p = app.project();
        p.setTranslation(currentClass, orig, trans);
        if (saveDict.isSelected() && !trans.isEmpty() && !trans.equals(orig)) {
            p.dictionary().add(orig, trans);
            try {
                p.dictionary().save();
            } catch (Exception ignored) {
                // 词典写盘失败不阻断
            }
        }
        showClass(currentClass);
        selectRow(orig);
        app.onTranslationChanged();
    }

    private void clearTranslation() {
        if (currentClass == null || editingOrig == null || editingLocked() || app == null) {
            return;
        }
        app.project().setTranslation(currentClass, editingOrig, "");
        showClass(currentClass);
        selectRow(editingOrig);
        app.onTranslationChanged();
    }

    private void useDictionary() {
        if (currentClass == null || editingOrig == null || editingLocked() || app == null) {
            return;
        }
        String trans = app.project().dictionary().get(editingOrig);
        if (trans == null || trans.isEmpty()) {
            hint.setText("词典中没有该字符串的译文");
            return;
        }
        editor.setText(trans);
        hint.setText("已填入词典译文，点击「保存译文」生效");
    }

    private void viewInSource() {
        if (currentClass == null || editingOrig == null || editingInternal || app == null) {
            return;
        }
        app.showSourceFor(currentClass, editingOrig);
    }

    /** 内部只读条目或「不翻译」文本均禁止改写。 */
    private boolean editingLocked() {
        return editingInternal
                || (editingOrig != null && app != null && app.project().isSkipped(editingOrig));
    }

    // ---------- 右键：不翻译/恢复 ----------

    private boolean isInsideHeader(javafx.scene.Node node) {
        for (javafx.scene.Node n = node; n != null; n = n.getParent()) {
            if (n.getStyleClass().contains("column-header-background")
                    || n.getStyleClass().contains("column-header")) {
                return true;
            }
        }
        return false;
    }

    private void showRowMenu(javafx.scene.control.ContextMenu menu, double x, double y) {
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null || row.internal || currentClass == null || app == null) {
            return;
        }
        boolean skipped = app.project().isSkipped(row.orig);
        menu.getItems().clear();
        javafx.scene.control.MenuItem toggle = new javafx.scene.control.MenuItem(
                skipped ? "恢复翻译此文本" : "此文本不翻译（保留原文）");
        toggle.setOnAction(e -> toggleSkip(row.orig));
        javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem("复制原字符串");
        copy.setOnAction(e -> javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, row.orig)));
        menu.getItems().addAll(toggle, copy);
        menu.show(table, x, y);
    }

    private void toggleSkip(String orig) {
        try {
            app.project().setTextSkipped(orig, !app.project().isSkipped(orig));
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        refreshRows();
        app.refreshClassNodes();
        app.updateStats();
        selectRow(orig);
    }

    public String currentClass() {
        return currentClass;
    }

    /** 表格选中行的原字符串（搜索窗口跳转用）。 */
    public String selectedOrig() {
        Row row = table.getSelectionModel().getSelectedItem();
        return row == null ? null : row.orig;
    }
}
