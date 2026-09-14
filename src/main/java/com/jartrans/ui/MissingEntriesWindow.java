package com.jartrans.ui;

import com.jartrans.core.LangPack;
import java.util.List;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/**
 * 「失效条目」窗口：显示导入语言包时未命中的条目（类/字符串已不存在），
 * 可一键把仍有价值的译文全部存入当前词典供新版本复用。
 */
public class MissingEntriesWindow extends AppWindow {

    private final List<LangPack.MissingEntry> missing;

    public MissingEntriesWindow(MainApp app) {
        super(app, "失效条目（" + app.project().lastMissing().size() + "）",
                880, 460, 640, 360, Modality.NONE);
        this.missing = app.project().lastMissing();

        VBox vbox = new VBox(6);
        vbox.setPadding(new Insets(6));
        vbox.getStyleClass().add("root-pane");

        TableView<LangPack.MissingEntry> table = new TableView<>();
        VBox.setVgrow(table, Priority.ALWAYS);
        TableColumn<LangPack.MissingEntry, String> colCls =
                TableColumns.text("类", 80, LangPack.MissingEntry::cls);
        TableColumn<LangPack.MissingEntry, String> colOrig =
                TableColumns.text("原字符串", 80, m -> Texts.displayText(m.orig()));
        TableColumn<LangPack.MissingEntry, String> colTrans =
                TableColumns.text("译文", 80, m -> Texts.displayText(m.trans()));
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
            app.project().dictionary().saveQuietly();
            Dialogs.info("完成", "已将 " + missing.size() + " 条失效条目存入词典。");
        });
        vbox.getChildren().add(new HBox(toDict));

        mount(vbox);
    }
}
