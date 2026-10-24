package com.jartrans.ui;

import com.jartrans.core.Project;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.animation.PauseTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
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
import javafx.util.Duration;

/** 全局搜索窗口：输入即搜，支持原文/译文范围，并可批量替换译文。 */
public class SearchWindow extends AppWindow {

    private final TextField queryField = new TextField();
    private final ComboBox<String> scopeBox = new ComboBox<>();
    private final TableView<SearchRow> table = new TableView<>();
    private final Label countLabel = new Label("");
    private final TextField replaceField = new TextField();
    private final PauseTransition debounce =
            new PauseTransition(Duration.millis(160));
    /** 异步搜索代际：只显示最新一次请求的结果（旧请求完成即丢弃）。 */
    private final AtomicInteger searchGeneration = new AtomicInteger();
    /** 状态色缓存（按调色板失效；Color.web 解析不再每行重复执行）。 */
    private final Map<String, Color> statusColors = new LinkedHashMap<>();
    private Map<String, String> statusColorsPalette;

    private record SearchRow(String cls, String orig, String trans, String statusKey) {
    }

    private static final Map<String, String> SCOPE_MAP = new LinkedHashMap<>();

    static {
        // 显式插入顺序：默认首项为「原字符串和译文」（Map.of 迭代顺序不稳定）
        SCOPE_MAP.put("原字符串和译文", "both");
        SCOPE_MAP.put("仅原字符串", "orig");
        SCOPE_MAP.put("仅译文", "trans");
    }

    public SearchWindow(MainApp app, String initial) {
        super(app, "全局搜索（全包）", 1020, 600, 760, 440, Modality.NONE);

        BorderPane root = rootPane();

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

        TableColumn<SearchRow, String> colCls = TableColumns.text("类", 240, SearchRow::cls);
        TableColumn<SearchRow, String> colOrig =
                TableColumns.text("原字符串", 330, r -> Texts.displayText(r.orig()));
        TableColumn<SearchRow, String> colTrans =
                TableColumns.text("译文", 260, r -> Texts.displayText(r.trans()));
        TableColumn<SearchRow, String> colStatus =
                TableColumns.text("状态", 90, r -> EditorPane.STATUS_TEXT.get(r.statusKey()));
        colStatus.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item);
                if (!empty && getTableRow().getItem() != null) {
                    setTextFill(statusColor(getTableRow().getItem().statusKey()));
                }
            }
        });
        //noinspection unchecked
        table.getColumns().addAll(colCls, colOrig, colTrans, colStatus);
        table.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        // 双击跳转：行工厂里挂一次，避免旧实现在每次 updateItem 里重复设置处理器
        table.setRowFactory(tv -> {
            TableRow<SearchRow> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                SearchRow item = row.getItem();
                if (e.getClickCount() == 2 && item != null) {
                    app.jumpTo(item.cls(), item.orig());
                    toFront();
                }
            });
            return row;
        });

        VBox center = new VBox(2, replaceRow, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        root.setCenter(center);

        Label tip = new Label("双击结果跳转到对应类的字符串。批量替换会写入撤销历史（Ctrl+Z 可回退）。");
        tip.setPadding(new Insets(6, 0, 0, 0));
        root.setBottom(tip);

        mount(root);

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

    /** 状态 -> Color（按当前调色板缓存，主题切换后自动重建）。 */
    private Color statusColor(String statusKey) {
        Map<String, String> palette = app.theme().palette();
        if (palette != statusColorsPalette) {
            statusColors.clear();
            statusColorsPalette = palette;
        }
        return statusColors.computeIfAbsent(statusKey,
                k -> Color.web(app.theme().statusColor(k)));
    }

    private void doSearch() {
        String keyword = queryField.getText().trim();
        if (keyword.isEmpty()) {
            searchGeneration.incrementAndGet(); // 使在途搜索作废
            table.getItems().clear();
            countLabel.setText("输入关键词开始搜索");
            return;
        }
        String scope = SCOPE_MAP.getOrDefault(scopeBox.getValue(), "both");
        int generation = searchGeneration.incrementAndGet();
        countLabel.setText("搜索中…");
        // 全库线性扫描放后台线程，大 jar 不冻结界面；按代际丢弃过期结果
        Thread worker = new Thread(() -> {
            List<SearchRow> rows = new ArrayList<>();
            for (Project.SearchResult r : app.project().search(keyword, scope)) {
                rows.add(new SearchRow(r.cls(), r.orig(), r.trans(), r.status().key));
            }
            javafx.application.Platform.runLater(() -> {
                if (generation != searchGeneration.get()) {
                    return; // 已有更新的搜索在途/完成，丢弃本次结果
                }
                table.getItems().setAll(rows);
                countLabel.setText(rows.isEmpty() ? "没有匹配结果" : rows.size() + " 条结果");
            });
        }, "jartrans-search");
        worker.setDaemon(true);
        worker.start();
    }

    private void replaceSelected() {
        SearchRow row = table.getSelectionModel().getSelectedItem();
        if (row == null) {
            Dialogs.info("提示", "请先在结果里选中一行。");
            return;
        }
        String replacement = replaceField.getText();
        List<Project.ReplaceChange> changes = app.project().replaceTranslations(
                List.of(new Project.ReplaceTarget(row.cls(), row.orig())), replacement);
        if (changes.isEmpty()) {
            Dialogs.info("提示", "该行无需替换（译文相同或为「不翻译」）。");
            return;
        }
        app.recordBatchTranslation(changes, replacement);
        doSearch();
        afterBulkReplace();
        countLabel.setText("已替换 1 条译文");
    }

    private void replaceAll() {
        List<SearchRow> rows = new ArrayList<>(table.getItems());
        if (rows.isEmpty()) {
            Dialogs.info("提示", "当前结果没有可替换的行。");
            return;
        }
        String replacement = replaceField.getText();
        long diff = rows.stream()
                .filter(r -> !app.project().isSkipped(r.orig())
                        && !app.project().effective(r.cls())
                                .getOrDefault(r.orig(), "").equals(replacement))
                .count();
        if (diff == 0) {
            Dialogs.info("提示", "所有结果的译文都已与目标一致，无需替换。");
            return;
        }
        if (!Dialogs.confirm("全部替换",
                "将把当前 " + diff + " 条匹配结果的译文全部替换为：\n\n「"
                        + Texts.displayText(replacement) + "」\n\n"
                        + "（跳过已标记「不翻译」的行；可 Ctrl+Z 一步撤销）\n确定继续吗？")) {
            return;
        }
        List<Project.ReplaceTarget> targets = rows.stream()
                .map(r -> new Project.ReplaceTarget(r.cls(), r.orig())).toList();
        // 跳过规则与写入由 core 单点执行；变更合并为单条撤销记录
        List<Project.ReplaceChange> changes = app.project().replaceTranslations(targets, replacement);
        app.recordBatchTranslation(changes, replacement);
        doSearch();
        afterBulkReplace();
        countLabel.setText("已替换 " + changes.size() + " 条译文");
    }

    private void afterBulkReplace() {
        app.onTranslationChanged();
        app.editor().refreshAfterExternalChange();
    }
}
