package com.jartrans;

import com.jartrans.ui.MainApp;
import javafx.application.Application;

/**
 * 入口：不直接继承 Application（避免平台启动器类加载问题），
 * 只负责转发到 JavaFX 的 MainApp。
 */
public final class Main {

    public static void main(String[] args) {
        Application.launch(MainApp.class, args);
    }

    private Main() {
    }
}
