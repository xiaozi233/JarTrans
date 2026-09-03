package com.jartrans.ui;

import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.TextInputDialog;
import javafx.stage.Window;

import java.util.Optional;

/** 消息框/输入框统一封装（对齐 tkinter messagebox/simpledialog 的行为）。 */
public final class Dialogs {

    private Dialogs() {
    }

    public static void info(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(alert.getDialogPane());
        alert.showAndWait();
    }

    public static void warn(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(alert.getDialogPane());
        alert.showAndWait();
    }

    public static void error(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(alert.getDialogPane());
        alert.showAndWait();
    }

    public static boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        applyTheme(alert.getDialogPane());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    /** 返回 null 表示取消。 */
    public static String ask(String title, String message, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial == null ? "" : initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(message);
        applyTheme(dialog.getDialogPane());
        Optional<String> result = dialog.showAndWait();
        return result.orElse(null);
    }

    /** 从任一已显示窗口复制主题样式表，让系统对话框与应用主题一致。 */
    private static void applyTheme(DialogPane pane) {
        for (Window w : Window.getWindows()) {
            Scene s = w.getScene();
            if (s != null && !s.getStylesheets().isEmpty()) {
                pane.getStylesheets().setAll(s.getStylesheets());
                return;
            }
        }
    }
}
