package com.jartrans.ui;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.control.ComboBox;

/**
 * 词典协调者：维护已打开的词典窗口监听器，并在词典结构/激活变化后统一刷新
 * 主窗下拉与统计、广播给所有词典窗口。
 *
 * <p>从 MainApp 抽出的协调层；切换协议本身见 {@link MainApp#switchDictionary}。</p>
 */
final class DictCoordinator {

    private final MainApp app;
    private final List<Runnable> listeners = new ArrayList<>();

    DictCoordinator(MainApp app) {
        this.app = app;
    }

    /** 主窗下拉：多词典模式列全部词典并选中激活项；单词典模式禁用并显示名称。 */
    void syncBox() {
        ComboBox<String> box = app.dictBox();
        if (app.project().dicts() == null) {
            box.setDisable(true);
            box.getItems().clear();
            box.setValue(app.project().dictionaryLabel());
            return;
        }
        box.setDisable(false);
        box.getItems().setAll(app.project().dicts().names());
        box.setValue(app.project().dicts().activeName());
    }

    /** 注册词典变化回调（词典条目/管理窗口在构造时调用，窗口隐藏时注销）。 */
    void addListener(Runnable listener) {
        listeners.add(listener);
    }

    void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** 词典结构（新建/删除/重命名/导入）或激活词典变化后统一刷新。 */
    void notifyChanged() {
        syncBox();
        app.updateStats();
        for (Runnable r : new ArrayList<>(listeners)) {
            r.run();
        }
    }
}
