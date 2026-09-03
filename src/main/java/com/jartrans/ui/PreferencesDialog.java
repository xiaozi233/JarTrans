package com.jartrans.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/** 全局首选项：集中管理主题/图例/过滤/词典/翻译行为与快捷键绑定。 */
public class PreferencesDialog extends Stage {

    private final MainApp app;
    private final ToggleGroup themeGroup = new ToggleGroup();
    private final RadioButton themeSystem = new RadioButton("跟随系统");
    private final RadioButton themeLight = new RadioButton("浅色");
    private final RadioButton themeDark = new RadioButton("深色");
    private final CheckBox legendVisible = new CheckBox("显示类状态图例（彩色圆点+说明）");
    private final CheckBox hideEmpty = new CheckBox("隐藏无可翻译字符串的类");
    private final ComboBox<String> statusFilterBox = new ComboBox<>();
    private final CheckBox onlyUntranslated = new CheckBox("翻译表格只看未翻译");
    private final CheckBox saveToDict = new CheckBox("保存译文时自动记入词典");
    private final CheckBox autoSaveTrans = new CheckBox("译文自动保存（输入停顿或切换行/类时自动写入）");
    private final CheckBox dblclickSource = new CheckBox("双击翻译行跳转到源码");
    private final TextField authorField = new TextField();
    private final ObservableList<ShortcutRow> shortcutRows = FXCollections.observableArrayList();
    private final TableView<ShortcutRow> shortcutTable = new TableView<>(shortcutRows);
    private final Label hintLabel = new Label("");
    private String recordingId; // 非 null 时正在录制该动作的新快捷键

    private record ShortcutRow(String id, String label) {
    }

    public PreferencesDialog(MainApp app) {
        this.app = app;
        setTitle("首选项");
        setWidth(680);
        setHeight(620);
        initOwner(app.stage());
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        setMinWidth(600);
        setMinHeight(520);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(12));
        root.getStyleClass().add("root-pane");

        // ---------- 外观 ----------
        Label secAppearance = section("外观");
        themeSystem.setToggleGroup(themeGroup);
        themeLight.setToggleGroup(themeGroup);
        themeDark.setToggleGroup(themeGroup);
        HBox themeRow = new HBox(10, new Label("主题："), themeSystem, themeLight, themeDark);
        themeRow.setAlignment(Pos.CENTER_LEFT);
        legendVisible.setSelected(app.legendVisible());
        legendVisible.setOnAction(e -> app.applyLegendVisible(legendVisible.isSelected()));
        hideEmpty.setSelected(app.hideEmptyEnabled());
        hideEmpty.setOnAction(e -> app.applyHideEmpty(hideEmpty.isSelected()));
        statusFilterBox.getItems().setAll(
                MainApp.STATE_FILTERS.stream().map(f -> f[1]).toList());
        statusFilterBox.setValue(MainApp.stateLabelOfKey(app.statusFilterKey()));
        statusFilterBox.setOnAction(e ->
                app.applyStatusFilter(MainApp.stateKeyOfLabel(statusFilterBox.getValue())));
        HBox statusRow = new HBox(10, new Label("类列表默认状态筛选："), statusFilterBox);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        // ---------- 翻译 ----------
        Label secTranslate = section("翻译");
        onlyUntranslated.setSelected(app.settings().getBool("only_untranslated"));
        onlyUntranslated.setOnAction(e -> app.editor().applyOnlyUntranslated(onlyUntranslated.isSelected()));
        saveToDict.setSelected(app.settings().getBool("save_to_dict"));
        saveToDict.setOnAction(e -> app.editor().applySaveDict(saveToDict.isSelected()));
        autoSaveTrans.setSelected(app.settings().getBool("auto_save_translation"));
        autoSaveTrans.setOnAction(e -> setSetting("auto_save_translation", autoSaveTrans.isSelected()));
        dblclickSource.setSelected(app.settings().getBool("dblclick_source"));
        dblclickSource.setOnAction(e -> setSetting("dblclick_source", dblclickSource.isSelected()));

        // ---------- 语言包 ----------
        Label secPack = section("语言包");
        authorField.setText(app.settings().getString("pack_author"));
        authorField.setPromptText("导出语言包时写入 author 字段（可留空）");
        authorField.setPrefWidth(260);
        authorField.setOnAction(e -> applyAuthor());
        authorField.focusedProperty().addListener((o, ov, focused) -> {
            if (!focused) {
                applyAuthor();
            }
        });
        HBox authorRow = new HBox(8, new Label("导出作者（默认值）："), authorField);
        authorRow.setAlignment(Pos.CENTER_LEFT);

        // ---------- 快捷键 ----------
        Label secShortcut = section("快捷键（点击组合可修改）");
        TableColumn<ShortcutRow, String> colName = new TableColumn<>("功能");
        colName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().label()));
        colName.setPrefWidth(240);
        TableColumn<ShortcutRow, String> colKey = new TableColumn<>("组合键");
        colKey.setCellValueFactory(d -> new SimpleStringProperty(app.shortcutText(d.getValue().id())));
        colKey.setPrefWidth(160);
        //noinspection unchecked
        shortcutTable.getColumns().addAll(colName, colKey);
        shortcutTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        shortcutTable.setPrefHeight(180);
        shortcutTable.setPlaceholder(new Label("（无）"));
        for (String[] d : Shortcuts.DEFS) {
            shortcutRows.add(new ShortcutRow(d[0], d[1]));
        }
        Button setKeyBtn = new Button("修改所选快捷键…");
        setKeyBtn.setOnAction(e -> beginRecord());
        Button resetKeyBtn = new Button("恢复所选默认");
        resetKeyBtn.setOnAction(e -> {
            ShortcutRow row = shortcutTable.getSelectionModel().getSelectedItem();
            if (row == null) {
                Dialogs.info("提示", "请先在上方表格选择一行。");
                return;
            }
            app.resetShortcut(row.id());
            shortcutTable.refresh();
            status("已恢复「" + row.label() + "」为默认："
                    + app.shortcutText(row.id()));
        });
        Button resetAllBtn = new Button("全部恢复默认");
        resetAllBtn.setOnAction(e -> {
            app.resetAllShortcuts();
            shortcutTable.refresh();
            status("所有快捷键已恢复默认");
        });
        HBox shortcutButtons = new HBox(8, setKeyBtn, resetKeyBtn, resetAllBtn);
        shortcutButtons.setAlignment(Pos.CENTER_LEFT);

        // ---------- 组装 ----------
        VBox body = new VBox(10, secAppearance, themeRow, legendVisible, hideEmpty,
                statusRow, secTranslate, onlyUntranslated, saveToDict,
                autoSaveTrans, dblclickSource, secPack, authorRow, secShortcut,
                shortcutTable, shortcutButtons, hintLabel);
        VBox.setVgrow(shortcutTable, Priority.ALWAYS);
        root.setCenter(new ScrollPane(body) {{
            setFitToWidth(true);
            setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        }});

        HBox bottom = new HBox(8);
        bottom.setPadding(new Insets(10, 0, 0, 0));
        bottom.setAlignment(Pos.CENTER_RIGHT);
        Button closeBtn = new Button("关闭");
        closeBtn.setOnAction(e -> close());
        bottom.getChildren().add(closeBtn);
        root.setBottom(bottom);

        setScene(new Scene(root));
        app.theme().attach(getScene());

        // 主题选择立即生效
        themeSystem.setOnAction(e -> applyTheme("system"));
        themeLight.setOnAction(e -> applyTheme("light"));
        themeDark.setOnAction(e -> applyTheme("dark"));
        syncThemeRadios(app.themeMode());

        // 录制快捷键的按键捕获（仅录制期间生效）
        addEventFilter(KeyEvent.KEY_PRESSED, this::onRecordingKey);
        closeBtn.requestFocus();
    }

    private Label section(String text) {
        Label l = new Label(text);
        l.setStyle("-fx-font-weight: bold; -fx-font-size: 12px; -fx-text-fill: -jr-accent;");
        l.setPadding(new Insets(4, 0, 0, 0));
        return l;
    }

    private void status(String text) {
        hintLabel.setText(text);
    }

    private void setSetting(String key, boolean value) {
        try {
            app.settings().set(key, value);
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
    }

    private void applyAuthor() {
        String v = authorField.getText().trim();
        try {
            app.settings().set("pack_author", v);
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
    }

    private void applyTheme(String mode) {
        app.applyThemeMode(mode);
        syncThemeRadios(app.themeMode());
    }

    private void syncThemeRadios(String mode) {
        themeSystem.setSelected("system".equals(mode));
        themeLight.setSelected("light".equals(mode));
        themeDark.setSelected("dark".equals(mode));
    }

    // ---------- 快捷键录制 ----------

    private void beginRecord() {
        ShortcutRow row = shortcutTable.getSelectionModel().getSelectedItem();
        if (row == null) {
            Dialogs.info("提示", "请先在上方表格选择要修改的功能行。");
            return;
        }
        if (recordingId != null) {
            return;
        }
        recordingId = row.id();
        status("正在为「" + row.label() + "」设置快捷键：请按下新的组合键（Ctrl/Alt/Shift + 字母/数字/F 键），按 Esc 取消");
    }

    private void onRecordingKey(KeyEvent e) {
        if (recordingId == null) {
            return;
        }
        if (e.getCode() == KeyCode.ESCAPE) {
            recordingId = null;
            status("已取消修改快捷键");
            e.consume();
            return;
        }
        String combo = Shortcuts.comboText(e);
        if (combo == null) {
            return; // 单个修饰键或无法表示的按键，继续等待
        }
        String id = recordingId;
        if (!app.applyShortcut(id, combo)) {
            status("无法使用「" + combo + "」：格式不支持或已被其它功能占用，请重试（Esc 取消）");
            e.consume();
            return; // 仍处于录制状态，可继续尝试
        }
        recordingId = null;
        shortcutTable.refresh();
        status("已将「" + labelOf(id) + "」绑定为 " + combo);
        e.consume();
    }

    private String labelOf(String id) {
        for (ShortcutRow r : shortcutRows) {
            if (r.id().equals(id)) {
                return r.label();
            }
        }
        return id;
    }
}
