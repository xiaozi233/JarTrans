package com.jartrans.ui;

import java.util.function.Function;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.TableColumn;

/**
 * 表格列构造的统一入口。
 *
 * <p>界面里大量列都只是「取一个字符串 + 设宽度」，这里把
 * {@code setCellValueFactory + SimpleStringProperty + setPrefWidth} 的样板收敛到一处。</p>
 */
final class TableColumns {

    private TableColumns() {
    }

    /** 文本列。 */
    static <T> TableColumn<T, String> text(String title, double width, Function<T, String> value) {
        return build(title, width, value, null);
    }

    /** 内容居中的文本列（状态、次数这类短列）。 */
    static <T> TableColumn<T, String> centered(String title, double width,
                                               Function<T, String> value) {
        return build(title, width, value, "-fx-alignment: CENTER;");
    }

    private static <T> TableColumn<T, String> build(String title, double width,
                                                    Function<T, String> value, String alignment) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(d -> new SimpleStringProperty(value.apply(d.getValue())));
        column.setPrefWidth(width);
        if (alignment != null) {
            column.setStyle(alignment);
        }
        return column;
    }
}
