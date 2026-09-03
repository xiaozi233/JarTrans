package com.jartrans.ui;

import com.jartrans.core.Project;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全局搜索窗口：输入即搜，支持原文/译文范围，并可批量替换译文。 */
public class SearchWindow extends Stage {

    private final MainApp app;
    private final TextField queryField = new TextField();
    private final ComboBox<String> scopeBox = new ComboBox<>();
    private final TableView<Project2Row> table = new TableView<>();
    private final Label countLabel = new Label("");
    private final TextField replaceField = new TextField();
    private final PauseTransition debounce =
            new PauseTransition(Duration.millis(160));

    private record Project2Row(String cls, String orig, String trans, String statusKey) {
    }

    private static final Map<String, String> SCOPE_MAP = new LinkedHashMap<>(Map.of(
            "原字符串和译文", "both", "仅原字符串", "orig", "仅译文", "trans"));

    public SearchWindow(MainApp app, String initial) {
        this.app = app;
        setTitle("全局搜索（全包）");
        setWidth(1020);
        setHeight(600);
        initModality(Modality.NONE);
        initOwner(app.stage());
        setResizable(true);
        setMinWidth(760);
        setMinHeight(440);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.getStyleClass().add("root-pane");

        // 第一行：关键词 + 范围 + 搜索按钮 + 计数
        HBox top = new HBox(8);
        top.setPadding(new Insets(0, 0, 4, 0));
        top.setAlignment(Pos.CENTER_LEFT);
        queryField.setPrefWidth(280);
        queryField.setPromptText("搜索原字符串 / 译文…（输入即搜）");
        scopeBox.getItems().addAll(SCOPE_MAP.keySet());
        scopeBox.getSelectionModel().selectFirst();
        scopeBox.setPrefWidth(140);
        Button searchBtn = new Button("搜索");
        searchBtn.setOnAction(e -> doSearch());
        top.getChildren().addAll(new Label("关键词："), queryField, scopeBox, searchBtn, countLabel);
        root.setTop(top);

        // 第二行：批量替换译文
        HBox replaceRow = new HBox(8);
        replaceRow.setPadding(new Insets(0, 0, 6, 0));
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        replaceField.setPrefWidth(300);
        replaceField.setPromptText("要替换成的译文文本");
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        Button repSelBtn = new Button("替换选中");
        repSelBtn.setOnAction(e -> replaceSelected());
        Button repAllBtn = new Button("全部替换");
        repAllBtn.getStyleClass().add("accent");
        repAllBtn.setOnAction(e -> replaceAll());
        Label repTip = new Label("Ctrl+R 聚焦此框");
        repTip.setStyle("-fx-text-fill: -jr-muted;");
        replaceRow.getChildren().addAll(new Label("替换为："), replaceField,
                repSelBtn, repAllBtn, repTip);

        TableColumn<Project2Row, String> colCls = new TableColumn<>("类");
        colCls.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().cls()));
        colCls.setPrefWidth(240);
        TableColumn<Project2Row, String> colOrig = new TableColumn<>("原字符串");
        colOrig.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                Texts.displayText(d.getValue().orig())));
        colOrig.setPrefWidth(330);
        TableColumn<Project2Row, String> colTrans = new TableColumn<>("译文");
        colTrans.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                Texts.displayText(d.getValue().trans())));
        colTrans.setPrefWidth(260);
        TableColumn<Project2Row, String> colStatus = new TableColumn<>("状态");
        colStatus.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                EditorPane.STATUS_TEXT.get(d.getValue().statusKey())));
        colStatus.setPrefWidth(90);
        colStatus.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty && getTableRow().getItem() != null) {
                    setTextFill(Color.web(app.theme().statusColor(
                            getTableRow().getItem().statusKey())));
                }
            }
        });
        //noinspection unchecked
        table.getColumns().addAll(colCls, colOrig, colTrans, colStatus);
        table.setColumnResizePolicy(
                javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        table.setRowFactory(tv -> new javafx.scene.control.TableRow<>() {
            @Override
            protected void updateItem(Project2Row item, boolean empty) {
                super.updateItem(item, empty);
                if (!empty && item != null) {
                    setOnMouseClicked(e -> {
                        if (e.getClickCount() == 2) {
                            app.jumpTo(item.cls(), item.orig());
                            toFront();
                        }
                    });
                }
            }
        });

        VBox center = new VBox(2, replaceRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setCenter(center);

        Label tip = new Label("双击结果跳转到对应类的字符串。批量替换会写入撤销历史（Ctrl+Z 可回退）。");
        tip.setPadding(new Insets(6, 0, 0, 0));
        root.setBottom(tip);

        setScene(new Scene(root));
        app.theme().attach(getScene());

        // 输入即搜（带防抖）+ 范围切换即搜
        debounce.setOnFinished(e -> doSearch());
        queryField.textProperty().addListener((o, ov, nv) -> {
            debounce.stop();
            debounce.playFromStart();
        });
        scopeBox.valueProperty().addListener((o, ov, nv) -> doSearch());
        queryField.setOnAction(e -> doSearch());

        // 窗口内快捷键：Ctrl+F 聚焦搜索框、Ctrl+R 聚焦替换框
        getScene().getAccelerators().put(
                new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                queryField::requestFocus);
        getScene().getAccelerators().put(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN),
                replaceField::requestFocus);

        queryField.setText(initial == null ? "" : initial);
        if (!queryField.getText().isBlank()) {
            doSearch();
        }
        queryField.requestFocus();
        queryField.selectAll();
    }

    /** 供主窗口复用窗口时更新关键词。 */
    public void setQuery(String keyword) {
        queryField.setText(keyword == null ? "" : keyword);
        doSearch();
    }

    /** 当前结果条数（测试/状态提示用）。 */
    public int resultCount() {
        return table.getItems().size();
    }

    private void doSearch() {
        String keyword = queryField.getText().trim();
        if (keyword.isEmpty()) {
            table.getItems().clear();
            countLabel.setText("输入关键词开始搜索");
            return;
        }
        String scope = SCOPE_MAP.getOrDefault(scopeBox.getValue(), "both");
        List<Project.SearchResult> results = app.project().search(keyword, scope);
        table.getItems().clear();
        for (Project.SearchResult r : results) {
            table.getItems().add(new Project2Row(r.cls(), r.orig(), r.trans(), r.status().key));
        }
        countLabel.setText(results.isEmpty() ? "没有匹配结果" : results.size() + " 条结果");
    }

    private List<Project2Row> replaceable() {
        List<Project2Row> out = new ArrayList<>();
        for (Project2Row r : table.getItems()) {
            if ("skipped".equals(r.statusKey())) {
                continue;
            }
            out.add(r);
        }
        return out;
    }

    private boolean performReplace(Project2Row r, String replacement) {
        if ("skipped".equals(r.statusKey())) {
            return false;
        }
        String before = app.project().effective(r.cls()).getOrDefault(r.orig(), "");
        if (before.equals(replacement)) {
            return false;
        }
        try {
            app.project().setTranslation(r.cls(), r.orig(), replacement);
        } catch (Exception exc) {
            Dialogs.warn("替换失败", exc.getMessage());
            return false;
        }
        app.recordTranslation(r.cls(), r.orig(), before, replacement);
        return true;
    }

    private void replaceSelected() {
        Project2Row row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            Dialogs.info("提示", "请先在结果里选中一行。");
            return;
        }
        if (performReplace(row, replaceField.getText())) {
            doSearch();
            afterBulkReplace();
            countLabel.setText("已替换 1 条译文");
        } else {
            Dialogs.info("提示", "该行无需替换（译文相同或为「不翻译」）。");
        }
    }

    private void replaceAll() {
        List<Project2Row> rows = replaceable();
        if (rows.isEmpty()) {
            Dialogs.info("提示", "当前结果没有可替换的行。");
            return;
        }
        String replacement = replaceField.getText();
        long diff = rows.stream()
                .filter(r -> !app.project().effective(r.cls())
                        .getOrDefault(r.orig(), "").equals(replacement))
                .count();
        if (diff == 0) {
            Dialogs.info("提示", "所有结果的译文都已与目标一致，无需替换。");
            return;
        }
        if (!Dialogs.confirm("全部替换",
                "将把当前 " + diff + " 条匹配结果的译文全部替换为：\n\n「"
                        + Texts.displayText(replacement) + "」\n\n"
                        + "（跳过已标记「不翻译」的行；可 Ctrl+Z 撤销）\n确定继续吗？")) {
            return;
        }
        int n = 0;
        for (Project2Row r : rows) {
            if (performReplace(r, replacement)) {
                n++;
            }
        }
        doSearch();
        afterBulkReplace();
        countLabel.setText("已替换 " + n + " 条译文");
    }

    private void afterBulkReplace() {
        app.onTranslationChanged();
        app.editor().refreshAfterExternalChange();
    }
}
