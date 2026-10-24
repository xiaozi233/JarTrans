package com.jartrans.ui;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;

/**
 * 空状态占位（表格/列表无内容时）：一句「现在该做什么」+ 一句操作提示。
 *
 * <p>空表比空白更容易让新用户迷路；所有 TableView/ListView 的统一占位都经此构造，
 * 保证文案层级与间距一致（.empty-title / .empty-hint）。</p>
 */
final class EmptyStates {

    private EmptyStates() {}

    static VBox create(String title, String hint) {
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("empty-title");
        Label hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("empty-hint");
        hintLabel.setWrapText(true);
        VBox box = new VBox(6, titleLabel, hintLabel);
        box.setAlignment(Pos.CENTER);
        box.setMaxWidth(420);
        return box;
    }
}
