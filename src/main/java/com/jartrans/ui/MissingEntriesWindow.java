package com.jartrans.ui;

import com.jartrans.core.LangPack;
import java.util.List;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

/**
 * 「失效条目」窗口：显示导入语言包时未命中的条目（类/字符串已不存在），
 * 可一键把仍有价值的译文全部存入当前词典供新版本复用。
 */
public class MissingEntriesWindow extends Stage {

    private final MainApp app;
    private final List<LangPack.MissingEntry> missing;

    public MissingEntriesWindow(MainApp app) {
        this.app = app;
        this.missing = app.project().lastMissing();
        setTitle("失效条目（" + missing.size() + "）");
        setWidth(880);
        setHeight(460);
        initOwner(app.stage());
        setResizable(true);
        setMinWidth(640);
        setMinHeight(360);
        initModality(Modality.NONE);

        VBox vbox = new VBox(6);
        vbox.setPadding(new Insets(6));
        vbox.getStyleClass().add("root-pane");

        TableView<LangPack.MissingEntry> table = new TableView<>();
        VBox.setVgrow(table, Priority.ALWAYS);
        TableColumn<LangPack.MissingEntry, String> colCls = new TableColumn<>("类");
        colCls.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().cls()));
        TableColumn<LangPack.MissingEntry, String> colOrig = new TableColumn<>("原字符串");
        colOrig.setCellValueFactory(d -> new SimpleStringProperty(
                Texts.displayText(d.getValue().orig())));
        TableColumn<LangPack.MissingEntry, String> colTrans = new TableColumn<>("译文");
        colTrans.setCellValueFactory(d -> new SimpleStringProperty(
                Texts.displayText(d.getValue().trans())));
        //noinspection unchecked
        table.getColumns().addAll(colCls, colOrig, colTrans);
        table.getItems().setAll(missing);
        vbox.getChildren().add(table);

        Button toDict = new Button("全部存入词典（供新版本复用）");
        toDict.setOnAction(ev -> {
            for (LangPack.MissingEntry m : missing) {
                if (!m.trans().isEmpty()) {
                    app.project().dictionary().add(m.orig(), m.trans());
                }
            }
            try {
                app.project().dictionary().save();
            } catch (Exception ignored) {
                // 写盘失败不阻断
            }
            Dialogs.info("完成", "已将 " + missing.size() + " 条失效条目存入词典。");
        });
        vbox.getChildren().add(new HBox(toDict));

        setScene(new Scene(vbox));
        app.theme().attach(getScene());
    }
}
