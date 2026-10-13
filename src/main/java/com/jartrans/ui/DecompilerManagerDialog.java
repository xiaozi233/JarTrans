package com.jartrans.ui;

import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

/** 内置反编译器选择窗口。引擎与应用一起升级，无需额外安装。 */
public class DecompilerManagerDialog extends AppWindow {
    private final ListView<String> engines = new ListView<>();
    private final Label detail = new Label();
    private final Button defaultButton = new Button("设为默认");

    public DecompilerManagerDialog(MainApp app) {
        super(app, "反编译管理器", 700, 380, 560, 300, Modality.NONE);
        BorderPane root = rootPane();
        engines.getItems().setAll(DecompilerManager.engines().stream()
                .map(info -> info.type().displayName() + " " + info.version()).toList());
        engines.setPrefWidth(220);
        root.setLeft(engines);
        BorderPane.setMargin(engines, new Insets(0, 16, 0, 0));
        detail.setWrapText(true);
        Label hint = new Label("三种反编译器均已内置，可离线使用。\n"
                + "切换后重新打开源码页即可使用所选引擎。");
        hint.setWrapText(true);
        root.setCenter(new VBox(16, detail, defaultButton, hint));
        engines.getSelectionModel().selectedIndexProperty().addListener((o, old, value) -> refresh());
        defaultButton.setOnAction(e -> {
            DecompilerType type = selectedType();
            if (type != null) {
                app.settings().setOrIgnore("decompiler_type", type.key);
                app.invalidateDecompilerState();
                refresh();
            }
        });
        engines.getSelectionModel().select(DecompilerManager.currentDecompiler(app.settings()).ordinal());
        mount(root);
        setOnShown(e -> refresh());
        refresh();
    }

    private DecompilerType selectedType() {
        int index = engines.getSelectionModel().getSelectedIndex();
        return index < 0 ? null : DecompilerManager.engines().get(index).type();
    }

    private void refresh() {
        DecompilerType selected = selectedType();
        DecompilerType current = DecompilerManager.currentDecompiler(app.settings());
        detail.setText("当前默认：" + current.displayName()
                + (selected == null ? "" : "\n已选择：" + selected.displayName()));
        defaultButton.setDisable(selected == null || selected == current);
    }
}
