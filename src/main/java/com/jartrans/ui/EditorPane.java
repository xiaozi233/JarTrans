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
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
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
    /** 当前类的全部行（未经查找过滤），查找栏过滤后显示在表格里的子集。 */
    private final List<Row> allRows = new ArrayList<>();
    private final ObservableList<Row> rows = FXCollections.observableArrayList();
    private final TableView<Row> table = new TableView<>(rows);
    private final TextArea editor = new TextArea();
    private final javafx.scene.control.ContextMenu colMenu = new javafx.scene.control.ContextMenu();
    private final javafx.scene.control.ContextMenu rowMenu = new javafx.scene.control.ContextMenu();
    private final Label hint = new Label("");
    private final javafx.scene.shape.Circle stateDot = new javafx.scene.shape.Circle(4);
    private final ComboBox<String> classStateBox = new ComboBox<>();
    private final CheckBox onlyUntranslated = new CheckBox("只看未翻译");
    private final CheckBox saveDict = new CheckBox("保存时记入词典");
    /** 程序化同步下拉选中值时置位，避免 valueProperty 监听误触发写状态。 */
    private boolean syncingState;

    // ---------- 类内查找 / 替换（Ctrl+F / Ctrl+R） ----------
    private final VBox findBar = new VBox(4);
    private final TextField findField = new TextField();
    private final ComboBox<String> findScopeBox = new ComboBox<>();
    private final Label findCount = new Label("");
    private final TextField replaceField = new TextField();
    private String findKeyword = "";   // 查找关键词（小写，空 = 不过滤）
    private String findScope = "both"; // orig / trans / both

    // ---------- 译文自动保存（输入停顿或切换行/类时落库） ----------
    private final javafx.animation.PauseTransition autoSave =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(700));
    /** 编辑区文本是否已被用户改过（尚未落库）。 */
    private boolean editDirty;
    /** 程序化加载文本（setEditor/回显）时抑制“视为用户修改”。 */
    private boolean loadingEditor;

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

        // 顶部 = 类状态行 + （可隐藏的）查找/替换栏
        findBar.setPadding(new Insets(0, 2, 6, 2));
        findBar.getStyleClass().add("find-bar");
        findBar.setVisible(false);
        findBar.setManaged(false);
        setTop(new VBox(head, findBar));

        // ---- 类内查找 / 替换栏（默认隐藏，Ctrl+F / Ctrl+R 呼出） ----
        buildFindBar();

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
        // 支持 Shift/Ctrl 多选，配合右键批量操作
        table.getSelectionModel().setSelectionMode(
                javafx.scene.control.SelectionMode.MULTIPLE);
        // 左键点表格任意处都收起上下文菜单（修复右键后再左键不关闭的问题）
        table.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY) {
                if (colMenu.isShowing()) {
                    colMenu.hide();
                }
                if (rowMenu.isShowing()) {
                    rowMenu.hide();
                }
            }
        });
        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2 && app != null
                    && app.settings().getBool("dblclick_source")) {
                viewInSource();
            }
        });

        // ---- 右键菜单：表头=显示/隐藏列；数据行=不翻译/恢复/清空等（支持多选批量） ----
        table.setOnContextMenuRequested(e -> {
            rowMenu.hide();
            colMenu.hide();
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
                selectRowUnder(e);
                showRowMenu(e.getScreenX(), e.getScreenY());
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
        buttons.getStyleClass().add("edit-toolbar");
        Button saveBtn = new Button("保存译文 (Ctrl+S)");
        saveBtn.getStyleClass().add("accent");
        saveBtn.setOnAction(e -> saveTranslation());
        Button useDictBtn = new Button("使用词典译文");
        useDictBtn.setOnAction(e -> useDictionary());
        Button clearBtn = new Button("清空译文");
        clearBtn.setOnAction(e -> clearTranslation());
        hint.setStyle("-fx-text-fill: -jr-muted;");
        buttons.getChildren().addAll(saveBtn, useDictBtn, clearBtn, saveDict, hint);
        saveDict.setOnAction(e -> setQuiet("save_to_dict", saveDict.isSelected()));

        VBox editBox = new VBox(buttons, editor);
        editBox.setPadding(new Insets(0, 6, 6, 6));
        VBox.setVgrow(editor, Priority.ALWAYS);
        editor.setPrefRowCount(4);
        editor.setMinHeight(72);
        editor.setWrapText(true);
        editor.setStyle("-fx-font-family: 'Microsoft YaHei UI', monospace;");

        vSplit.getItems().add(editBox);

        BorderPane.setMargin(vSplit, new Insets(0, 0, 4, 0));
        setCenter(vSplit);
        // 保存译文快捷键统一由主窗口（可重新绑定）处理，此处不再本地拦截

        // ---- 译文自动保存：输入停顿 700ms 落库；失焦立即落库 ----
        autoSave.setOnFinished(x -> flushEdit());
        editor.textProperty().addListener((o, ov, nv) -> {
            if (loadingEditor || editingInternal || editingOrig == null || app == null) {
                return;
            }
            if (editingLocked()) {
                editDirty = false;
                return;
            }
            if (!app.settings().getBool("auto_save_translation")) {
                editDirty = false;
                return;
            }
            editDirty = true;
            autoSave.stop();
            autoSave.playFromStart();
        });
        editor.focusedProperty().addListener((o, ov, focused) -> {
            if (!focused) {
                autoSave.stop();
                flushEdit();
            }
        });
    }

    // ---------- 类内查找 / 替换 ----------

    private void buildFindBar() {
        // 第一行：查找
        Label findLbl = new Label("查找：");
        findField.getStyleClass().add("find-input");
        findField.setPromptText("在原字符串 / 译文中查找…");
        findField.setPrefWidth(240);
        HBox.setHgrow(findField, Priority.ALWAYS);
        findScopeBox.getItems().addAll("两者", "原字符串", "译文");
        findScopeBox.setValue("两者");
        findScopeBox.setPrefWidth(110);
        Button prevBtn = new Button("↑");
        prevBtn.setOnAction(e -> findPrev());
        Button nextBtn = new Button("↓");
        nextBtn.setOnAction(e -> findNext());
        findCount.setStyle("-fx-text-fill: -jr-muted;");
        Button replaceModeBtn = new Button("替换…");
        replaceModeBtn.setOnAction(e -> replaceMode());
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("flat");
        closeBtn.setOnAction(e -> closeFindBar());
        HBox row1 = new HBox(6, findLbl, findField, findScopeBox,
                prevBtn, nextBtn, findCount, replaceModeBtn, closeBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        // 第二行：替换（替换模式才显示）
        Label repLbl = new Label("替换为：");
        replaceField.getStyleClass().add("replace-input");
        replaceField.setPromptText("新的译文文本");
        replaceField.setPrefWidth(240);
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        Button repOne = new Button("替换当前");
        repOne.setOnAction(e -> replaceCurrent());
        Button repAll = new Button("全部替换");
        repAll.getStyleClass().add("accent");
        repAll.setOnAction(e -> replaceAll());
        HBox row2 = new HBox(6, repLbl, replaceField, repOne, repAll);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setVisible(false);
        row2.setManaged(false);
        row2.setId("replace-row");

        findBar.getChildren().setAll(row1, row2);

        // 输入即过滤
        findField.textProperty().addListener((o, ov, nv) -> {
            findKeyword = nv == null ? "" : nv.trim().toLowerCase();
            applyFindFilter();
        });
        findScopeBox.valueProperty().addListener((o, ov, nv) -> {
            findScope = switch (nv == null ? "两者" : nv) {
                case "原字符串" -> "orig";
                case "译文" -> "trans";
                default -> "both";
            };
            applyFindFilter();
        });
        // Enter=下一个，Shift+Enter=上一个，Esc=关闭并恢复
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    findPrev();
                } else {
                    findNext();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                closeFindBar();
                e.consume();
            }
        });
        replaceField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                replaceCurrent();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                closeFindBar();
                e.consume();
            }
        });
    }

    /** Ctrl+F：呼出查找栏并聚焦。 */
    public void beginFind() {
        if (currentClass == null || app == null || !app.project().hasJar()) {
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        row2Visible(false);
        findField.requestFocus();
        findField.selectAll();
    }

    /** Ctrl+R：呼出查找栏并进入替换模式。 */
    public void beginReplace() {
        if (currentClass == null || app == null || !app.project().hasJar()) {
            return;
        }
        findBar.setVisible(true);
        findBar.setManaged(true);
        row2Visible(true);
        replaceField.requestFocus();
        if (!findField.getText().isBlank()) {
            applyFindFilter();
        }
    }

    private void row2Visible(boolean v) {
        for (javafx.scene.Node n : findBar.getChildren()) {
            if (n instanceof HBox h && "replace-row".equals(h.getId())) {
                h.setVisible(v);
                h.setManaged(v);
            }
        }
    }

    private void replaceMode() {
        beginReplace();
    }

    private void closeFindBar() {
        findField.clear();
        findKeyword = "";
        findBar.setVisible(false);
        findBar.setManaged(false);
        row2Visible(false);
        applyFindFilter();
        if (table.getScene() != null) {
            table.requestFocus();
        }
    }

    private boolean rowMatches(Row r) {
        String q = findKeyword;
        if (q.isEmpty()) {
            return true;
        }
        boolean hitOrig = !"trans".equals(findScope)
                && r.orig.toLowerCase().contains(q);
        boolean hitTrans = !"orig".equals(findScope)
                && r.trans != null && r.trans.toLowerCase().contains(q);
        return hitOrig || hitTrans;
    }

    /** 按查找词重建可见行，并保持选中。 */
    private void applyFindFilter() {
        List<Row> visible = new ArrayList<>();
        boolean filtering = !findKeyword.isEmpty();
        for (Row r : allRows) {
            if (!filtering || rowMatches(r)) {
                visible.add(r);
            }
        }
        String keep = editingOrig;
        rows.setAll(visible);
        if (filtering) {
            findCount.setText(visible.size() + " 条");
        } else {
            findCount.setText("");
        }
        if (visible.isEmpty()) {
            if (filtering) {
                table.getSelectionModel().clearSelection();
                if (editingOrig != null) {
                    editingOrig = null;
                }
            }
            return;
        }
        if (keep != null && selectRow(keep)) {
            return;
        }
        table.getSelectionModel().selectFirst();
        onSelect();
    }

    /** 跳到下一个匹配行。 */
    private void findNext() {
        int cur = table.getSelectionModel().getSelectedIndex();
        if (rows.isEmpty()) {
            return;
        }
        int next = cur < 0 ? 0 : (cur + 1) % rows.size();
        table.getSelectionModel().select(next);
        table.scrollTo(next);
        onSelect();
    }

    /** 跳到上一个匹配行。 */
    private void findPrev() {
        int cur = table.getSelectionModel().getSelectedIndex();
        if (rows.isEmpty()) {
            return;
        }
        int prev = cur < 0 ? rows.size() - 1 : (cur - 1 + rows.size()) % rows.size();
        table.getSelectionModel().select(prev);
        table.scrollTo(prev);
        onSelect();
    }

    /** 跳转到行并刷新编辑区内容（与 TableView 选中联动）。 */
    private void gotoRow(int index) {
        table.getSelectionModel().select(index);
        table.scrollTo(index);
        onSelect();
    }

    private boolean canEditRow(Row r) {
        return r != null && !r.internal
                && !app.project().isSkipped(r.orig);
    }

    /** 替换当前选中的匹配行译文，然后跳到下一个。 */
    public void replaceCurrent() {
        flushEdit();
        if (currentClass == null || app == null) {
            return;
        }
        int idx = table.getSelectionModel().getSelectedIndex();
        Row row = idx >= 0 && idx < rows.size() ? rows.get(idx) : null;
        if (row == null) {
            app.setStatus("没有选中的行可替换");
            return;
        }
        if (!canEditRow(row)) {
            app.setStatus("该行为内部只读或已标记「不翻译」，不可替换");
            return;
        }
        String replacement = replaceField.getText();
        String before = app.project().effective(currentClass).getOrDefault(row.orig, "");
        if (before.equals(replacement)) {
            findNext();
            return;
        }
        try {
            app.project().setTranslation(currentClass, row.orig, replacement);
        } catch (Exception exc) {
            Dialogs.warn("替换失败", exc.getMessage());
            return;
        }
        app.recordTranslation(currentClass, row.orig, before, replacement);
        app.onTranslationChanged();
        // 刷新后跳到下一条
        refreshRows();
        int next = Math.min(idx + 1, rows.size() - 1);
        if (next >= 0) {
            gotoRow(next);
        }
    }

    /** 替换当前可见（匹配查找条件）的全部可编辑行译文。 */
    public void replaceAll() {
        flushEdit();
        if (currentClass == null || app == null) {
            return;
        }
        String replacement = replaceField.getText();
        if (findKeyword.isEmpty() && !Dialogs.confirm("全部替换",
                "当前没有输入查找词，将把本类全部行译文替换为：\n\n「"
                        + Texts.displayText(replacement) + "」\n\n确定继续吗？")) {
            return;
        }
        List<Row> targets = new ArrayList<>();
        for (Row r : rows) {
            if (canEditRow(r) && !app.project().effective(currentClass)
                    .getOrDefault(r.orig, "").equals(replacement)) {
                targets.add(r);
            }
        }
        if (targets.isEmpty()) {
            app.setStatus("没有需要替换的行（译文与目标相同）");
            return;
        }
        int n = 0;
        for (Row r : targets) {
            String before = app.project().effective(currentClass).getOrDefault(r.orig, "");
            try {
                app.project().setTranslation(currentClass, r.orig, replacement);
            } catch (Exception exc) {
                Dialogs.warn("替换失败", exc.getMessage());
                break;
            }
            app.recordTranslation(currentClass, r.orig, before, replacement);
            n++;
        }
        app.onTranslationChanged();
        refreshRows();
        app.setStatus("已替换 " + n + " 行译文");
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
        app.recordClassMark(currentClass, app.project().classStatus().get(currentClass), state);
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
        flushEdit(); // 先落库上一行/上一类未保存的输入
        currentClass = cls;
        allRows.clear();
        rows.clear();
        if (cls == null || app == null || !app.project().classes().containsKey(cls)) {
            setEditor(null, false);
            refreshClassStateUi();
            applyFindFilter();
            return;
        }
        Project p = app.project();
        Map<String, String> eff = p.effective(cls);
        for (Project.TextCount tc : p.texts(cls)) {
            if (onlyUntranslated.isSelected() && eff.get(tc.text()) != null) {
                continue;
            }
            allRows.add(makeRow(tc.text(), tc.count(), eff.getOrDefault(tc.text(), ""), false));
        }
        if (app.showInternal() && !onlyUntranslated.isSelected()) {
            for (String text : p.internalTexts(cls)) {
                allRows.add(makeRow(text, 0, "", true));
            }
        }
        setEditor(null, false);
        refreshClassStateUi();
        applyFindFilter();
    }

    private Row makeRow(String orig, int cnt, String trans, boolean internal) {
        String status;
        if (internal) {
            status = "internal";
        } else if (app.project().isSkipped(orig)) {
            status = "skipped";
        } else {
            status = app.project().status(currentClass, orig).key;
        }
        String methods = internal ? "" : String.join(", ", app.project().methodNames(currentClass, orig));
        return new Row(orig, trans, status, internal ? "" : String.valueOf(cnt),
                methods, internal);
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
        flushEdit(); // 切行前自动保存上一行输入
        Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            return;
        }
        setEditor(row.orig, row.internal);
    }

    private void setEditor(String orig, boolean internal) {
        autoSave.stop();
        editDirty = false;
        loadingEditor = true;
        try {
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
        } finally {
            loadingEditor = false;
        }
    }

    /** 把编辑区当前文本写入工程（自动保存/切行/切类/关窗前调用）。幂等。 */
    public void flushEdit() {
        if (app == null || !editDirty) {
            return;
        }
        autoSave.stop();
        if (editingOrig == null || editingInternal || editingLocked()) {
            editDirty = false;
            return;
        }
        String trans = editor.getText();
        String beforeEff = app.project().effective(currentClass).getOrDefault(editingOrig, "");
        if (trans.equals(beforeEff)) {
            editDirty = false;
            return;
        }
        try {
            app.project().setTranslation(currentClass, editingOrig, trans);
        } catch (Exception ignored) {
            // 写盘失败保留脏标记，等待下次尝试
            return;
        }
        app.recordTranslation(currentClass, editingOrig, beforeEff, trans);
        editDirty = false;
        app.onTranslationChanged();
    }

    public void saveTranslation() {
        if (currentClass == null || editingOrig == null || app == null) {
            return;
        }
        if (editingLocked()) {
            return;
        }
        flushEdit(); // 未等自动保存的输入先落库，统一走同一条记录
        String orig = editingOrig;
        String trans = editor.getText();
        Project p = app.project();
        String beforeEff = p.effective(currentClass).getOrDefault(orig, "");
        p.setTranslation(currentClass, orig, trans);
        String afterEff = p.effective(currentClass).getOrDefault(orig, "");
        app.recordTranslation(currentClass, orig, beforeEff, afterEff);
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
        flushEdit(); // 先落库未保存输入，再清空，避免被后续重建覆盖
        String beforeEff = app.project().effective(currentClass).getOrDefault(editingOrig, "");
        app.project().setTranslation(currentClass, editingOrig, "");
        app.recordTranslation(currentClass, editingOrig, beforeEff, "");
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
        hint.setText("已填入词典译文（切换行/类时自动保存，或点「保存译文」立即生效）");
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

    /** 右键先选中鼠标所在行（不破坏已有 Shift/Ctrl 多选）。 */
    private void selectRowUnder(javafx.scene.input.ContextMenuEvent e) {
        javafx.scene.Node n = e.getPickResult().getIntersectedNode();
        while (n != null && !(n instanceof javafx.scene.control.TableRow)) {
            n = n.getParent();
        }
        if (n instanceof javafx.scene.control.TableRow<?> row && row.getItem() instanceof Row item
                && !table.getSelectionModel().getSelectedItems().contains(item)) {
            table.getSelectionModel().select(item);
        }
    }

    /** 右键菜单：支持多选批量（保留原文/恢复/清空译文/复制）。 */
    private void showRowMenu(double x, double y) {
        java.util.List<Row> rowsSel = new java.util.ArrayList<>(
                table.getSelectionModel().getSelectedItems());
        rowsSel.removeIf(r -> r.internal);
        if (rowsSel.isEmpty() || currentClass == null || app == null) {
            return;
        }
        String label = rowsSel.size() > 1 ? "（" + rowsSel.size() + " 行）" : "";
        long skippedCnt = rowsSel.stream()
                .filter(r -> app.project().isSkipped(r.orig)).count();
        rowMenu.getItems().clear();
        javafx.scene.control.MenuItem keep = new javafx.scene.control.MenuItem(
                skippedCnt == rowsSel.size()
                        ? "恢复翻译" + label
                        : "保留原文（不翻译）" + label);
        keep.setOnAction(e -> bulkSkip(rowsSel,
                !(skippedCnt == rowsSel.size())));
        javafx.scene.control.MenuItem clear = new javafx.scene.control.MenuItem(
                "清空译文" + label);
        clear.setOnAction(e -> bulkClear(rowsSel));
        javafx.scene.control.MenuItem copy = new javafx.scene.control.MenuItem(
                rowsSel.size() == 1 ? "复制原字符串" : "复制首个原字符串");
        copy.setOnAction(e -> javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, rowsSel.get(0).orig)));
        rowMenu.getItems().addAll(keep, clear, copy);
        rowMenu.show(table, x, y);
    }

    /** 批量设置「不翻译」。 */
    private void bulkSkip(java.util.List<Row> rowsSel, boolean skip) {
        int n = 0;
        for (Row r : rowsSel) {
            boolean cur = app.project().isSkipped(r.orig);
            if (cur == skip) {
                continue;
            }
            app.recordSkip(r.orig, cur, skip);
            try {
                app.project().setTextSkipped(r.orig, skip);
            } catch (Exception ignored) {
                // 写盘失败不阻断
            }
            n++;
        }
        refreshAfterBulk(rowsSel.get(0).orig);
        app.setStatus(n == 0 ? "无需变更" : "已将 " + n + " 条文本设为"
                + (skip ? "「不翻译」" : "可翻译"));
    }

    /** 批量清空译文。 */
    private void bulkClear(java.util.List<Row> rowsSel) {
        int n = 0;
        for (Row r : rowsSel) {
            if (app.project().isSkipped(r.orig)) {
                continue; // 保留原文的不可清（本就无生效译文）
            }
            String before = app.project().effective(currentClass).getOrDefault(r.orig, "");
            if (before.isEmpty()) {
                continue;
            }
            app.project().setTranslation(currentClass, r.orig, "");
            app.recordTranslation(currentClass, r.orig, before, "");
            n++;
        }
        refreshAfterBulk(rowsSel.get(0).orig);
        app.setStatus("已清空 " + n + " 条译文");
    }

    private void refreshAfterBulk(String orig) {
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

    // ---------- 供首选项/自动化验证使用的访问器 ----------

    public TextField findFieldNode() {
        return findField;
    }

    public TextField replaceFieldNode() {
        return replaceField;
    }

    /** 当前表格可见行数（未过滤时=类内全部行）。 */
    public int visibleRowCount() {
        return rows.size();
    }

    public boolean findBarShowing() {
        return findBar.isVisible();
    }

    /** 首选项联动：只看未翻译（保持与顶部复选框一致并持久化）。 */
    void applyOnlyUntranslated(boolean v) {
        if (onlyUntranslated.isSelected() != v) {
            onlyUntranslated.setSelected(v);
        }
        setQuiet("only_untranslated", v);
        refreshRows();
    }

    /** 首选项联动：保存时记入词典。 */
    void applySaveDict(boolean v) {
        if (saveDict.isSelected() != v) {
            saveDict.setSelected(v);
        }
        setQuiet("save_to_dict", v);
    }

    /** 首选项联动：恢复当前选中行（供撤销/外部刷新后回看）。 */
    void refreshAfterExternalChange() {
        if (currentClass == null) {
            return;
        }
        refreshRows();
        if (editingOrig != null) {
            selectRow(editingOrig);
        }
    }
}
