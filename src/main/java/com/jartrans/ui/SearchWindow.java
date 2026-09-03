package com.jartrans.ui;

import com.jartrans.core.Project;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 全局搜索窗口（对应 gui/search_panel.py）。 */
public class SearchWindow extends Stage {

    private final MainApp app;
    private final TextField queryField = new TextField();
    private final ComboBox<String> scopeBox = new ComboBox<>();
    private final TableView<Project2Row> table = new TableView<>();
    private final Label countLabel = new Label("");

    private record Project2Row(String cls, String orig, String trans, String statusKey) {
    }

    private static final Map<String, String> SCOPE_MAP = new LinkedHashMap<>(Map.of(
            "原字符串和译文", "both", "仅原字符串", "orig", "仅译文", "trans"));

    public SearchWindow(MainApp app) {
        this.app = app;
        setTitle("全局搜索");
        setWidth(980);
        setHeight(560);
        initModality(Modality.NONE);
        initOwner(app.stage());
        setResizable(true);
        setMinWidth(720);
        setMinHeight(420);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.getStyleClass().add("root-pane");

        HBox top = new HBox(8);
        top.setPadding(new Insets(0, 0, 6, 0));
        top.setAlignment(Pos.CENTER_LEFT);
        queryField.setPrefWidth(280);
        queryField.setOnAction(e -> doSearch());
        scopeBox.getItems().addAll(SCOPE_MAP.keySet());
        scopeBox.getSelectionModel().selectFirst();
        scopeBox.setPrefWidth(130);
        Button searchBtn = new Button("搜索");
        searchBtn.setOnAction(e -> doSearch());
        top.getChildren().addAll(new Label("关键词："), queryField, scopeBox, searchBtn, countLabel);
        root.setTop(top);

        TableColumn<Project2Row, String> colCls = new TableColumn<>("类");
        colCls.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().cls()));
        colCls.setPrefWidth(260);
        TableColumn<Project2Row, String> colOrig = new TableColumn<>("原字符串");
        colOrig.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                Texts.displayText(d.getValue().orig())));
        colOrig.setPrefWidth(340);
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
        table.setColumnResizePolicy(javafx.scene.control.TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
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
        root.setCenter(table);

        Label tip = new Label("双击结果跳转到对应类的对应字符串。");
        tip.setPadding(new Insets(6, 0, 0, 0));
        root.setBottom(tip);

        setScene(new Scene(root));
        app.theme().attach(getScene());
        queryField.requestFocus();
    }

    private void doSearch() {
        String keyword = queryField.getText().trim();
        if (keyword.isEmpty()) {
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
}
