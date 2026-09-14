package com.jartrans.ui;

import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 应用内独立窗口的基类：标题、尺寸、最小尺寸、模态方式、属主窗口与主题样式表的
 * 挂载统一在这里完成，子类只关心窗口内容。
 */
abstract class AppWindow extends Stage {

    /** 宿主应用（子类通过它访问 settings / project / 主窗口）。 */
    protected final MainApp app;

    protected AppWindow(MainApp app, String title, double width, double height,
                        double minWidth, double minHeight, Modality modality) {
        this.app = app;
        setTitle(title);
        setWidth(width);
        setHeight(height);
        setMinWidth(minWidth);
        setMinHeight(minHeight);
        setResizable(true);
        initModality(modality);
        initOwner(app.stage());
    }

    /** 挂载内容并按当前主题套用样式表（含滚动条皮肤）。 */
    protected void mount(Parent content) {
        setScene(new Scene(content));
        app.theme().attach(getScene());
    }

    /** 内容根容器：root-pane 主题类 + 默认 10px 内边距。 */
    protected static BorderPane rootPane() {
        return rootPane(10);
    }

    /** 内容根容器：root-pane 主题类 + 指定内边距。 */
    protected static BorderPane rootPane(double padding) {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(padding));
        root.getStyleClass().add("root-pane");
        return root;
    }
}
