package com.jartrans.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * 类内查找/替换栏（Ctrl+F / Ctrl+R）。
 *
 * <p>只负责界面与输入：读取关键词/范围/替换文本、显示命中数、处理
 * Enter（下一个）、Shift+Enter（上一个）、Esc（关闭）。至于「过滤哪些行」与
 * 「怎样替换」由 {@link Handler} 回调给 EditorPane 落地。</p>
 */
final class FindReplaceBar extends VBox {

    /** 宿主回调：过滤条件变化与各按钮动作都由 EditorPane 实现。 */
    interface Handler {
        void onFilterChanged();

        void onFindNext();

        void onFindPrev();

        void onReplaceCurrent();

        void onReplaceAll();

        void onDismiss();
    }

    private final TextField findField = new TextField();
    private final ComboBox<String> scopeBox = new ComboBox<>();
    private final Label countLabel = new Label("");
    private final TextField replaceField = new TextField();
    private final HBox replaceRow = new HBox(6);

    FindReplaceBar(Handler handler) {
        super(4);
        setPadding(new Insets(0, 2, 6, 2));
        getStyleClass().add("find-bar");
        setVisible(false);
        setManaged(false);
        build(handler);
    }

    private void build(Handler handler) {
        // 第一行：查找
        findField.getStyleClass().add("find-input");
        findField.setPromptText("在原字符串 / 译文中查找…");
        findField.setPrefWidth(240);
        HBox.setHgrow(findField, Priority.ALWAYS);
        scopeBox.getItems().addAll("两者", "原字符串", "译文");
        scopeBox.setValue("两者");
        scopeBox.setPrefWidth(110);
        Button prevBtn = new Button("↑");
        prevBtn.setOnAction(e -> handler.onFindPrev());
        Button nextBtn = new Button("↓");
        nextBtn.setOnAction(e -> handler.onFindNext());
        countLabel.setStyle("-fx-text-fill: -jr-muted;");
        Button replaceModeBtn = new Button("替换…");
        replaceModeBtn.setOnAction(e -> showReplace(true));
        Button closeBtn = new Button("✕");
        closeBtn.getStyleClass().add("flat");
        closeBtn.setOnAction(e -> handler.onDismiss());
        HBox row1 = new HBox(6, new Label("查找："), findField, scopeBox,
                prevBtn, nextBtn, countLabel, replaceModeBtn, closeBtn);
        row1.setAlignment(Pos.CENTER_LEFT);

        // 第二行：替换（替换模式才显示）
        replaceField.getStyleClass().add("replace-input");
        replaceField.setPromptText("新的译文文本");
        replaceField.setPrefWidth(240);
        HBox.setHgrow(replaceField, Priority.ALWAYS);
        Button replaceOneBtn = new Button("替换当前");
        replaceOneBtn.setOnAction(e -> handler.onReplaceCurrent());
        Button replaceAllBtn = new Button("全部替换");
        replaceAllBtn.getStyleClass().add("accent");
        replaceAllBtn.setOnAction(e -> handler.onReplaceAll());
        replaceRow.getChildren().setAll(new Label("替换为："), replaceField,
                replaceOneBtn, replaceAllBtn);
        replaceRow.setAlignment(Pos.CENTER_LEFT);
        showReplace(false);

        getChildren().setAll(row1, replaceRow);

        findField.textProperty().addListener((o, ov, nv) -> handler.onFilterChanged());
        scopeBox.valueProperty().addListener((o, ov, nv) -> handler.onFilterChanged());
        findField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                if (e.isShiftDown()) {
                    handler.onFindPrev();
                } else {
                    handler.onFindNext();
                }
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                handler.onDismiss();
                e.consume();
            }
        });
        replaceField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                handler.onReplaceCurrent();
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE) {
                handler.onDismiss();
                e.consume();
            }
        });
    }

    /** 当前查找关键词（小写、去首尾空白）；空串表示不过滤。 */
    String keyword() {
        String text = findField.getText();
        return text == null ? "" : text.trim().toLowerCase();
    }

    /** 查找范围：orig / trans / both。 */
    String scope() {
        return switch (scopeBox.getValue() == null ? "两者" : scopeBox.getValue()) {
            case "原字符串" -> "orig";
            case "译文" -> "trans";
            default -> "both";
        };
    }

    /** 当前替换文本。 */
    String replacement() {
        return replaceField.getText();
    }

    /** 命中数提示；空串表示不显示。 */
    void setCount(String text) {
        countLabel.setText(text);
    }

    /** 展开查找栏；withReplace 决定是否同时显示替换行。 */
    void open(boolean withReplace) {
        setVisible(true);
        setManaged(true);
        showReplace(withReplace);
        if (withReplace) {
            replaceField.requestFocus();
        } else {
            findField.requestFocus();
            findField.selectAll();
        }
    }

    /** 收起查找栏并清空关键词（清空会触发一次过滤回调，由宿主决定后续）。 */
    void closeBar() {
        findField.clear();
        setVisible(false);
        setManaged(false);
        showReplace(false);
    }

    boolean isOpen() {
        return isVisible();
    }

    /** 替换模式第二行的显示/隐藏。 */
    void showReplace(boolean visible) {
        replaceRow.setVisible(visible);
        replaceRow.setManaged(visible);
    }

    TextField findNode() {
        return findField;
    }

    TextField replaceNode() {
        return replaceField;
    }
}
