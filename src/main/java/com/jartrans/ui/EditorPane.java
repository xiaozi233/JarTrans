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
            "internal", "内部·只读");

    private MainApp app;
    private String currentClass;
    private String editingOrig;
    private boolean editingInternal;
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final TextArea editor = new TextArea();
    private final Label hint = new Label("");
    private final Label stateDot = new Label("●");
    private final ComboBox<String> classStateBox = new ComboBox<>();
    private final CheckBox onlyUntranslated = new CheckBox("只看未翻译");
    private final CheckBox saveDict = new CheckBox("保存时记入词典");

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
        stateDot.setStyle("-fx-font-size: 14px;");
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
        colOrig.setPrefWidth(360);
        TableColumn<Row, String> colTrans = new TableColumn<>("译文");
        colTrans.setCellValueFactory(d -> new SimpleStringProperty(Texts.displayText(d.getValue().trans)));
        colTrans.setPrefWidth(260);
        TableColumn<Row, String> colStatus = new TableColumn<>("状态");
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(STATUS_TEXT.get(d.getValue().statusKey)));
        colStatus.setPrefWidth(80);
        colStatus.setStyle("-fx-alignment: CENTER;");
        TableColumn<Row, String> colCount = new TableColumn<>("次数");
        colCount.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().count));
        colCount.setPrefWidth(50);
        colCount.setStyle("-fx-alignment: CENTER;");
        TableColumn<Row, String> colMethods = new TableColumn<>("所在方法");
        colMethods.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().methods));
        colMethods.setPrefWidth(170);

        //noinspection unchecked
        table.getColumns().addAll(colOrig, colTrans, colStatus, colCount, colMethods);
        table.setColumnResizePolicy(javafx.scene.control.TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(Row item, boolean empty) {
                super.updateItem(item, empty);
                setTextFill(null);
                if (item != null && !empty && app != null) {
                    setTextFill(javafx.scene.paint.Color.web(app.theme().statusColor(item.statusKey)));
                }
            }
        });
        table.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> onSelect());
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                viewInSource();
            }
        });
        BorderPane.setMargin(table, new Insets(0, 0, 6, 0));
        setCenter(table);

        // ---- 编辑区 ----
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
        editor.setPrefRowCount(6);
        editor.setWrapText(true);
        editor.setStyle("-fx-font-family: 'Microsoft YaHei UI', monospace;");

        TitledPane editPane = new TitledPane("编辑区", editBox);
        editPane.setCollapsible(false);
        setBottom(editPane);

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
        if (currentClass == null || app == null) {
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
        if (currentClass == null || app == null) {
            classStateBox.getSelectionModel().selectFirst();
            stateDot.setTextFill(javafx.scene.paint.Color.web(app != null
                    ? app.theme().color("fg") : "#000000"));
            return;
        }
        String key = currentStateKey();
        Map<String, String> mapping = Map.of(
                "todo", "未开始", "doing", "翻译中", "done", "已完成",
                "ignore", "已忽略", "empty", "无字符串");
        String label = mapping.get(key);
        classStateBox.getSelectionModel().select(label != null ? label : "自动");
        stateDot.setTextFill(javafx.scene.paint.Color.web(
                app.theme().stateColor(key != null ? key : "empty")));
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
        String status = internal ? "internal"
                : app.project().status(currentClass, orig).key;
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
        editor.setText(p.effective(currentClass).getOrDefault(orig, ""));
        String suggestion = p.dictionary().get(orig);
        hint.setText(suggestion != null ? "词典建议：" + Texts.displayText(suggestion) : "");
    }

    public void saveTranslation() {
        if (currentClass == null || editingOrig == null || app == null) {
            return;
        }
        if (editingInternal) {
            com.jartrans.ui.Dialogs.info("提示", "内部 UTF8 条目不可修改。");
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
        if (currentClass == null || editingOrig == null || editingInternal || app == null) {
            return;
        }
        app.project().setTranslation(currentClass, editingOrig, "");
        showClass(currentClass);
        selectRow(editingOrig);
        app.onTranslationChanged();
    }

    private void useDictionary() {
        if (currentClass == null || editingOrig == null || editingInternal || app == null) {
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

    public String currentClass() {
        return currentClass;
    }

    /** 表格选中行的原字符串（搜索窗口跳转用）。 */
    public String selectedOrig() {
        Row row = table.getSelectionModel().getSelectedItem();
        return row == null ? null : row.orig;
    }
}
