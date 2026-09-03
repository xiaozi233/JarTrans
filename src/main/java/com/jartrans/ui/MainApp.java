package com.jartrans.ui;

import com.jartrans.core.AppMeta;
import com.jartrans.core.LangPack;
import com.jartrans.core.Project;
import com.jartrans.core.Settings;
import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import com.jartrans.core.java.JavaEnv;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.DataFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * JavaFX 主应用与控制器（对应 Python gui/app.py）。
 * 界面骨架在 main_view.fxml 中，本类通过 FXMLLoader.setController 注入。
 */
public class MainApp extends Application {

    // 类状态常量
    static final Map<String, String> STATE_LABEL = new LinkedHashMap<>(Map.of(
            "empty", "无字符串", "todo", "未开始", "doing", "翻译中",
            "done", "已完成", "ignore", "已忽略"));
    static final List<String[]> STATE_FILTERS = List.of(
            new String[]{"all", "全部"}, new String[]{"todo", "未开始"},
            new String[]{"doing", "翻译中"}, new String[]{"done", "已完成"},
            new String[]{"ignore", "已忽略"}, new String[]{"empty", "无字符串"});

    // 可自定义快捷键的定义与解析见 Shortcuts（SHORTCUT_DEFS 单一来源，存于 settings 的 "key_<id>"）。

    private Settings settings;
    private Theme theme;
    private Project project;
    private Stage stage;
    private Scene mainScene;
    /** 输入防抖：过滤框每字符即时过滤、工具栏搜索框即时反馈。 */
    private final PauseTransition filterDebounce =
            new PauseTransition(Duration.millis(120));
    private final PauseTransition searchDebounce =
            new PauseTransition(Duration.millis(180));

    private String javaPathCache;
    private String javaVersionCache;
    private boolean decompiling;
    /** 进行中的单类反编译任务（供「取消反编译」中断与丢弃结果）。 */
    private Task<List<Path>> activeDecompileTask;
    private Map<String, Path> indexCache;
    /** 已按单类反编译过的类 → 产出的 .java 文件（本会话内复用）。 */
    private final Map<String, List<Path>> singleClassFiles = new HashMap<>();
    private boolean javaPrompted;

    // ---------- FXML 控件 ----------
    @FXML
    private CheckMenuItem showInternalItem;
    @FXML
    private CheckMenuItem hideEmptyItem;
    @FXML
    private RadioMenuItem themeSystemItem;
    @FXML
    private RadioMenuItem themeLightItem;
    @FXML
    private RadioMenuItem themeDarkItem;
    @FXML
    private ToggleGroup themeGroup;
    @FXML
    private ComboBox<String> dictBox;
    @FXML
    private TextField searchField;
    @FXML
    private TextField filterField;
    @FXML
    private ComboBox<String> statusBox;
    @FXML
    private CheckBox hideEmptyCheck;
    @FXML
    private FlowPane legendBox;
    @FXML
    private TreeView<String> classTree;
    @FXML
    private TabPane tabPane;
    @FXML
    private Tab editorTab;
    @FXML
    private Tab sourceTab;
    @FXML
    private EditorPane editor;
    @FXML
    private SourcePanel sourcePanel;
    @FXML
    private Label statusLabel;
    @FXML
    private ProgressBar progressBar;
    @FXML
    private Label statsLabel;

    // 类树状态与过滤/构建/查询逻辑（纯模型，不碰 TreeView 控件）
    private ClassTreeModel treeModel;
    private ContextMenu lastTreeMenu; // 最近一次类树右键菜单（左键点其它处时收起）

    // 图例控件（圆点走 CSS 状态类，无代码上色）

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        this.settings = new Settings();
        this.theme = new Theme(settings.getString("theme"));
        this.project = new Project();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/jartrans/ui/main_view.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage.setTitle(AppMeta.APP_NAME + " — " + AppMeta.APP_TAGLINE);
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        Scene scene = new Scene(root, 1320, 840);
        this.mainScene = scene;
        theme.attach(scene);
        // 快捷键集中注册（可自定义，见首选项）：撤销/重做/保存/查找/替换/全局搜索/首选项
        registerShortcuts(scene);
        stage.setScene(scene);
        stage.show();
        stage.setOnCloseRequest(e -> onClose());

        initUi();
    }

    // ---------- 初始化 ----------

    private void initUi() {
        treeModel = new ClassTreeModel(project);
        // 主题
        theme.onChange(this::onThemeChanged);
        String mode = theme.mode();
        setThemeSelections(mode);

        showInternalItem.setSelected(false);
        hideEmptyItem.setSelected(settings.getBool("hide_empty"));
        hideEmptyCheck.setSelected(settings.getBool("hide_empty"));
        hideEmptyItem.selectedProperty().addListener((o, ov, nv) ->
                hideEmptyCheck.setSelected(nv));
        hideEmptyCheck.selectedProperty().addListener((o, ov, nv) ->
                hideEmptyItem.setSelected(nv));

        // 词典切换
        dictBox.valueProperty().addListener((o, ov, nv) -> onDictSelected(nv));
        syncDictBox();

        // 状态筛选
        statusBox.getItems().setAll(STATE_FILTERS.stream().map(f -> f[1]).toList());
        statusBox.setValue(stateLabelOfKey(settings.getString("status_filter")));
        statusBox.valueProperty().addListener((o, ov, nv) -> onStatusFilterChanged());

        // 过滤框即时过滤：每输入一个字符立即重建类列表，无需回车
        filterDebounce.setOnFinished(x -> {
            if (project.hasJar()) {
                rebuildTree();
                updateStats();
            }
        });
        filterField.textProperty().addListener((o, ov, nv) -> filterDebounce.playFromStart());
        // 工具栏「全局搜索」框：输入即时在状态栏反馈匹配数，回车/按钮打开明细窗口
        searchDebounce.setOnFinished(x -> quickSearchStatus());
        searchField.textProperty().addListener((o, ov, nv) -> searchDebounce.playFromStart());

        // 类树
        classTree.setShowRoot(false);
        classTree.setCellFactory(tv -> new TreeCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item);
                // 颜色全部走 CSS 状态类：选中/未选中/主题切换都由 CSS 统一重算，
                // 避免代码 setTextFill 被 CSS 脉冲覆盖导致色值漂移（dot 与文字必同步）
                getStyleClass().removeIf(c -> c.startsWith("cell-state-") || c.equals("cell-dir"));
                setGraphic(null);
                String kind = treeModel.kindOf(getTreeItem());
                if (ClassTreeModel.TYPE_CLASS.equals(kind)) {
                    String cls = treeModel.targetOf(getTreeItem());
                    String st = project.classState(cls);
                    getStyleClass().add("cell-state-" + st);
                    // 彩色状态圆点：与文字同规则（.state-dot.cell-state-*）
                    Circle dot = new Circle(3.5);
                    dot.getStyleClass().addAll("state-dot", "cell-state-" + st);
                    setGraphic(dot);
                } else if (ClassTreeModel.TYPE_DIR.equals(kind)) {
                    getStyleClass().add("cell-dir");
                }
            }
        });
        classTree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> onTreeSelect(nv));
        // 左键点树任意处即收起右键菜单（修复右键后再左键点同类不关闭）
        classTree.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == MouseButton.PRIMARY
                    && lastTreeMenu != null && lastTreeMenu.isShowing()) {
                lastTreeMenu.hide();
            }
        });
        classTree.setOnContextMenuRequested(e -> {
            TreeItem<String> item = classTree.getSelectionModel().getSelectedItem();
            if (item == null) {
                return;
            }
            showTreeMenu(item, e.getScreenX(), e.getScreenY());
        });

        editor.setApp(this);
        sourcePanel.setApp(this);
        sourcePanel.refreshTheme();

        // 切到「源码」页签 = 对当前类反编译并查看（避免与编辑器里的按钮重复）
        tabPane.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> {
            if (nv == sourceTab) {
                onSourceTabOpened();
            }
        });

        // 初始状态
        buildLegend();
        refreshClassNodes();
        updateStats();
        setStatus("请先打开一个 jar 文件");
    }

    /** 填充类状态图例。始终保留一个「显示/隐藏」开关按钮；开启时附带彩色圆点+文字。 */
    private void buildLegend() {
        legendBox.getChildren().clear();
        legendBox.setAlignment(Pos.CENTER_LEFT);
        final boolean visible = legendVisible();
        Button toggle = new Button(visible ? "隐藏图例" : "显示图例");
        toggle.setStyle("-fx-font-size: 11px; -fx-padding: 1 8 1 8;");
        toggle.setTooltip(new Tooltip(
                visible ? "收起类状态图例" : "展开类状态图例（彩色圆点+说明）"));
        toggle.setOnAction(e -> applyLegendVisible(!legendVisible()));
        legendBox.getChildren().add(toggle);
        if (!visible) {
            return;
        }
        for (String st : List.of("todo", "doing", "done", "ignore", "empty")) {
            Circle dot = new Circle(4);
            dot.getStyleClass().addAll("state-dot", "cell-state-" + st);
            Tooltip.install(dot,
                    new Tooltip(STATE_LABEL.get(st)));
            Label text = new Label(STATE_LABEL.get(st));
            text.getStyleClass().addAll("legend-txt", "cell-state-" + st);
            text.setStyle("-fx-font-size: 11px;");
            HBox pair = new HBox(3, dot, text);
            pair.setAlignment(Pos.CENTER_LEFT);
            legendBox.getChildren().add(pair);
        }
    }

    private void setThemeSelections(String mode) {
        themeSystemItem.setSelected("system".equals(mode));
        themeLightItem.setSelected("light".equals(mode));
        themeDarkItem.setSelected("dark".equals(mode));
    }

    private void onThemeChanged() {
        // 图例颜色（圆点 + 文字随主题重建）
        buildLegend();
        refreshClassNodes();
        if (sourcePanel != null) {
            sourcePanel.refreshTheme();
        }
        editor.refreshClassStateUi();
    }

    // ---------- 菜单/工具栏动作 ----------

    @FXML
    public void onOpenJar(ActionEvent e) {
        openJar();
    }

    @FXML
    public void onExportJar(ActionEvent e) {
        exportJar();
    }

    @FXML
    public void onImportPack(ActionEvent e) {
        importPack();
    }

    @FXML
    public void onExportPack(ActionEvent e) {
        exportPack();
    }

    @FXML
    public void onShowMissing(ActionEvent e) {
        showMissing();
    }

    @FXML
    public void onManageDictionary(ActionEvent e) {
        new DictDialog(this).show();
    }

    @FXML
    public void onManageDictionaries(ActionEvent e) {
        manageDictionaries();
    }

    @FXML
    public void onFillClassFromDictionary(ActionEvent e) {
        fillClassFromDictionary();
    }

    @FXML
    public void onFillFromDictionary(ActionEvent e) {
        fillFromDictionary();
    }

    @FXML
    public void onMarkTodo(ActionEvent e) {
        markCurrent("todo");
    }

    @FXML
    public void onMarkDoing(ActionEvent e) {
        markCurrent("doing");
    }

    @FXML
    public void onMarkDone(ActionEvent e) {
        markCurrent("done");
    }

    @FXML
    public void onMarkIgnore(ActionEvent e) {
        markCurrent("ignore");
    }

    @FXML
    public void onMarkAuto(ActionEvent e) {
        markCurrent(null);
    }

    @FXML
    public void onCollapseAll(ActionEvent e) {
        collapseAll();
    }

    @FXML
    public void onExpandAll(ActionEvent e) {
        expandAll();
    }

    @FXML
    public void onToggleInternal(ActionEvent e) {
        editor.showClass(editor.currentClass());
    }

    @FXML
    public void onFilterChanged(ActionEvent e) {
        persistHideEmpty();
        rebuildTree();
        updateStats();
    }

    @FXML
    public void onClearFilter(ActionEvent e) {
        filterField.clear();
        rebuildTree();
    }

    @FXML
    public void onThemeSystem(ActionEvent e) {
        setThemeMode("system");
    }

    @FXML
    public void onThemeLight(ActionEvent e) {
        setThemeMode("light");
    }

    @FXML
    public void onThemeDark(ActionEvent e) {
        setThemeMode("dark");
    }

    @FXML
    public void onViewCurrentSource(ActionEvent e) {
        viewCurrentSource();
    }

    @FXML
    public void onClearDecompileCache(ActionEvent e) {
        DecompilerManager.clearAllCache();
        indexCache = null;
        singleClassFiles.clear();
        setStatus("反编译缓存已清理。");
    }

    @FXML
    public void onOpenSearch(ActionEvent e) {
        openSearch();
    }

    @FXML
    public void onOpenPreferences(ActionEvent e) {
        openPreferences();
    }

    @FXML
    public void onFindInClass(ActionEvent e) {
        openFindInClass();
    }

    @FXML
    public void onReplaceInClass(ActionEvent e) {
        openReplaceInClass();
    }

    @FXML
    public void onGlobalSearch(ActionEvent e) {
        openSearch();
    }

    @FXML
    public void onAbout(ActionEvent e) {
        Dialogs.info("关于", AppMeta.APP_NAME + " — " + AppMeta.APP_TAGLINE + "\n"
                + "Java + JavaFX 版，核心引擎仅用 JDK 标准库实现，不依赖字节码库。\n\n"
                + "原理：只重定向常量池中的字符串引用并追加新条目，\n"
                + "不改动、不移动任何原有常量和字节码结构。\n"
                + "写回前会自动做结构校验，确保 class 文件可被 JVM 正常加载。\n\n"
                + "支持浅色/深色/跟随系统主题、多词典切换、类状态标记与过滤、\n"
                + "内置 Vineflower / CFR / Procyon 三种反编译器，开箱即用。");
    }

    @FXML
    public void onExit(ActionEvent e) {
        onClose();
    }

    // ---------- 快捷键（可自定义，存于 settings key_<id>） ----------

    /** 当前生效的快捷键文本（未自定义时为默认值）。 */
    public String shortcutText(String id) {
        String stored = settings.getString("key_" + id);
        return stored.isBlank() ? Shortcuts.defaultFor(id) : stored;
    }

    /** 把某个动作绑定到新的组合键；成功返回 true。重复组合返回 false。 */
    public boolean applyShortcut(String id, String comboText) {
        String norm = Shortcuts.normalize(comboText);
        if (norm.isEmpty() || Shortcuts.parse(norm) == null) {
            return false;
        }
        for (String[] d : Shortcuts.DEFS) {
            if (d[0].equals(id)) {
                continue;
            }
            if (shortcutText(d[0]).equalsIgnoreCase(norm)) {
                return false; // 已被其它动作占用
            }
        }
        try {
            settings.set("key_" + id, norm);
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        registerShortcuts(mainScene);
        return true;
    }

    public void resetShortcut(String id) {
        try {
            settings.set("key_" + id, Shortcuts.defaultFor(id));
        } catch (Exception ignored) {
            // 忽略
        }
        registerShortcuts(mainScene);
    }

    public void resetAllShortcuts() {
        for (String[] d : Shortcuts.DEFS) {
            try {
                settings.set("key_" + d[0], d[2]);
            } catch (Exception ignored) {
                // 忽略
            }
        }
        registerShortcuts(mainScene);
    }

    /** 清空并重建主场景的全部快捷键（含 Ctrl+Shift+Z 重做别名）。 */
    void registerShortcuts(Scene scene) {
        if (scene == null) {
            return;
        }
        scene.getAccelerators().clear();
        put(scene, "undo", this::undoAction);
        put(scene, "redo", this::redoAction);
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Z,
                        KeyCombination.CONTROL_DOWN,
                        KeyCombination.SHIFT_DOWN), this::redoAction);
        put(scene, "save", () -> {
            if (project.hasJar()) {
                editor.saveTranslation();
            }
        });
        put(scene, "find", this::openFindInClass);
        put(scene, "replace", this::openReplaceInClass);
        put(scene, "gsearch", this::openSearch);
        put(scene, "prefs", this::openPreferences);
    }

    private void put(Scene scene, String id, Runnable action) {
        KeyCodeCombination combo = Shortcuts.parse(shortcutText(id));
        if (combo != null) {
            scene.getAccelerators().put(combo, action);
        }
    }

    private void ensureEditorClass() {
        if (!project.hasJar()) {
            return;
        }
        if (editor.currentClass() == null && !project.classOrder().isEmpty()) {
            editor.showClass(project.classOrder().get(0));
        }
    }

    /** Ctrl+F：切到翻译页并呼出「类内查找」。 */
    void openFindInClass() {
        if (!project.hasJar()) {
            setStatus("请先打开一个 jar 文件");
            return;
        }
        tabPane.getSelectionModel().select(editorTab);
        ensureEditorClass();
        editor.beginFind();
    }

    /** Ctrl+R：切到翻译页并呼出「类内替换」。 */
    void openReplaceInClass() {
        if (!project.hasJar()) {
            setStatus("请先打开一个 jar 文件");
            return;
        }
        tabPane.getSelectionModel().select(editorTab);
        ensureEditorClass();
        editor.beginReplace();
    }

    // ---------- 首选项 ----------

    private PreferencesDialog prefDialog;

    public void openPreferences() {
        if (prefDialog != null && prefDialog.isShowing()) {
            prefDialog.toFront();
            return;
        }
        prefDialog = new PreferencesDialog(this);
        prefDialog.show();
    }

    /** 供测试/复用：最近一次打开的首选项窗口（可能为 null）。 */
    public PreferencesDialog preferencesDialog() {
        return prefDialog;
    }

    // ---------- 主题 ----------

    private void setThemeMode(String mode) {
        theme.setMode(mode);
        setThemeSelections(theme.mode());
        try {
            settings.set("theme", theme.mode());
        } catch (Exception ignored) {
            // 设置写盘失败不阻断
        }
        String suffix = theme.mode().equals(Theme.MODE_SYSTEM)
                ? "（" + (theme.dark() ? "系统为深色" : "系统为浅色") + "）"
                : "";
        setStatus("主题已切换：" + Theme.LABELS.get(theme.mode()) + suffix);
    }

    // ---------- 类树 ----------

    /** 当前类树过滤条件（从过滤框/状态下拉/隐藏空类控件读取）。 */
    private ClassTreeModel.ViewOptions currentView() {
        return new ClassTreeModel.ViewOptions(
                filterField.getText().trim().toLowerCase(),
                stateKeyOfLabel(statusBox.getValue()),
                hideEmptyCheck.isSelected());
    }

    /** 状态筛选显示名 → 状态键；未知返回 "all"。 */
    static String stateKeyOfLabel(String label) {
        for (String[] f : STATE_FILTERS) {
            if (f[1].equals(label)) {
                return f[0];
            }
        }
        return "all";
    }

    /** 状态筛选键 → 显示名；未知返回 "全部"。 */
    static String stateLabelOfKey(String key) {
        for (String[] f : STATE_FILTERS) {
            if (f[0].equals(key)) {
                return f[1];
            }
        }
        return "全部";
    }

    private void rebuildTree() {
        TreeItem<String> newRoot = new TreeItem<>("");
        classTree.setRoot(newRoot);
        if (!project.hasJar()) {
            return;
        }
        String keep = editor.currentClass();
        TreeItem<String> content = treeModel.rebuild(project.jarName(), currentView());
        newRoot.getChildren().add(content);
        if (keep != null) {
            TreeItem<String> kept = treeModel.nodeOf(keep);
            if (kept != null) {
                treeModel.expandPathTo(kept);
                classTree.getSelectionModel().select(kept);
                int row = classTree.getRow(kept);
                if (row >= 0) {
                    classTree.scrollTo(row);
                }
            }
        }
    }

    /** 当前状态下拉选中的状态键。 */
    private String stateFilterKey() {
        return stateKeyOfLabel(statusBox.getValue());
    }

    /** 只刷新已有节点的文字与颜色（不改变展开状态）；过滤中则整体重建。 */
    void refreshClassNodes() {
        if (currentView().isFiltering()) {
            rebuildTree();
            return;
        }
        treeModel.refreshTexts();
        classTree.refresh();
    }

    private void expandAll() {
        ClassTreeModel.expandRecursively(classTree.getRoot());
    }

    private void collapseAll() {
        for (TreeItem<String> child : classTree.getRoot().getChildren()) {
            ClassTreeModel.collapseRecursively(child);
        }
    }

    private void onTreeSelect(TreeItem<String> node) {
        if (node == null) {
            return;
        }
        if (!ClassTreeModel.TYPE_CLASS.equals(treeModel.kindOf(node))) {
            return;
        }
        String cls = treeModel.targetOf(node);
        if (editor.currentClass() == null || !editor.currentClass().equals(cls)) {
            editor.showClass(cls);
        }
        if (tabPane.getSelectionModel().getSelectedItem() == sourceTab) {
            sourcePanel.display(cls, null);
        }
    }

    private void showTreeMenu(TreeItem<String> node, double x, double y) {
        List<String> targets = treeModel.targetsFor(node);
        if (targets.isEmpty()) {
            return;
        }
        if (lastTreeMenu != null && lastTreeMenu.isShowing()) {
            lastTreeMenu.hide();
        }
        ContextMenu menu = new ContextMenu();
        MenuItem markItem = new MenuItem("标记状态（" + targets.size() + " 个类）");
        MenuItem autoItem = new MenuItem("自动（按翻译进度）");
        autoItem.setOnAction(ev -> mark(targets, null));
        MenuItem todoItem = new MenuItem("未开始");
        todoItem.setOnAction(ev -> mark(targets, "todo"));
        MenuItem doingItem = new MenuItem("翻译中");
        doingItem.setOnAction(ev -> mark(targets, "doing"));
        MenuItem doneItem = new MenuItem("已完成");
        doneItem.setOnAction(ev -> mark(targets, "done"));
        MenuItem ignoreItem = new MenuItem("已忽略");
        ignoreItem.setOnAction(ev -> mark(targets, "ignore"));
        MenuItem fillItem = new MenuItem("用词典填充这些类");
        fillItem.setOnAction(ev -> fillClasses(targets));
        MenuItem copyItem = new MenuItem("复制类路径");
        copyItem.setOnAction(ev -> copyPaths(targets));
        MenuItem expandItem = new MenuItem("展开全部");
        expandItem.setOnAction(ev -> expandAll());
        MenuItem collapseItem = new MenuItem("折叠全部");
        collapseItem.setOnAction(ev -> collapseAll());
        menu.getItems().addAll(markItem, autoItem, todoItem, doingItem, doneItem,
                ignoreItem, new SeparatorMenuItem(),
                fillItem, copyItem, new SeparatorMenuItem(),
                expandItem, collapseItem);
        lastTreeMenu = menu;
        menu.show(classTree, x, y);
    }

    private void mark(List<String> classes, String state) {
        Map<String, String> olds = new HashMap<>();
        for (String cls : classes) {
            olds.put(cls, project.classStatus().get(cls));
        }
        try {
            project.setClassStatusBulk(classes, state);
        } catch (Exception exc) {
            Dialogs.warn("提示", exc.getMessage());
            return;
        }
        for (String cls : classes) {
            recordClassMark(cls, olds.get(cls), state);
        }
        refreshClassNodes();
        updateStats();
        String label = state == null ? "自动判定" : STATE_LABEL.get(state);
        setStatus("已将 " + classes.size() + " 个类标记为「" + label + "」");
    }

    private void markCurrent(String state) {
        String cls = editor.currentClass();
        if (cls == null) {
            Dialogs.info("提示", "请先在左侧选择一个类。");
            return;
        }
        mark(List.of(cls), state);
    }

    private void fillClasses(List<String> classes) {
        if (!project.hasJar()) {
            return;
        }
        int count = project.fillFromDictionary(classes);
        refreshClassNodes();
        updateStats();
        if (editor.currentClass() != null && classes.contains(editor.currentClass())) {
            editor.refreshRows();
        }
        setStatus("词典填充了 " + count + " 条未翻译字符串");
    }

    private void copyPaths(List<String> classes) {
        List<String> sorted = new ArrayList<>(classes);
        sorted.sort(String::compareTo);
        String text = String.join("\n", sorted);
        Clipboard.getSystemClipboard().setContent(
                Map.of(DataFormat.PLAIN_TEXT, text));
        setStatus("已复制 " + classes.size() + " 个类路径到剪贴板");
    }

    // ---------- 统计 ----------

    void updateStats() {
        if (!project.hasJar()) {
            statsLabel.setText("");
            progressBar.setProgress(0);
            return;
        }
        long[] totalDone = project.stats();
        long total = totalDone[0];
        long done = totalDone[1];
        progressBar.setProgress(total == 0 ? 0 : done * 1.0 / total);
        statsLabel.setText(done + "/" + total);
        Map<String, Integer> cs = project.classStats();
        StringBuilder parts = new StringBuilder();
        for (String st : List.of("done", "doing", "todo", "ignore")) {
            if (parts.length() > 0) {
                parts.append("  ");
            }
            parts.append(STATE_LABEL.get(st)).append(" ").append(cs.get(st));
        }
        statsLabel.setText("类：" + parts + "    词典「" + project.dictionaryLabel() + "」"
                + project.dictionary().size() + " 条");
    }

    // ---------- 词典 ----------

    public void syncDictBox() {
        if (project.dicts() == null) {
            dictBox.setDisable(true);
            dictBox.getItems().clear();
            dictBox.setValue(project.dictionaryLabel());
            return;
        }
        dictBox.setDisable(false);
        dictBox.getItems().setAll(project.dicts().names());
        dictBox.setValue(project.dicts().activeName());
    }

    private void onDictSelected(String name) {
        if (name == null || project.dicts() == null
                || name.equals(project.dicts().activeName())) {
            return;
        }
        try {
            project.useDictionary(name);
            settings.set("dictionary", name);
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        updateStats();
        setStatus("已切换到词典「" + name + "」（" + project.dictionary().size() + " 条）");
        if (editor.currentClass() != null) {
            editor.refreshRows();
        }
    }

    void manageDictionaries() {
        if (project.dicts() == null) {
            Dialogs.info("提示", "当前为命令行/单文件模式运行，未启用多词典。");
            return;
        }
        new DictManagerDialog(this).show();
    }

    private void fillFromDictionary() {
        if (!requireJar()) {
            return;
        }
        int count = project.fillFromDictionary();
        refreshClassNodes();
        updateStats();
        editor.refreshRows();
        Dialogs.info("完成", "词典自动填充了 " + count + " 条未翻译字符串（标记为\"自动）。");
    }

    private void fillClassFromDictionary() {
        if (!requireJar()) {
            return;
        }
        String cls = editor.currentClass();
        if (cls == null) {
            Dialogs.info("提示", "请先在左侧选择一个类。");
            return;
        }
        int count = project.fillFromDictionary(List.of(cls));
        refreshClassNodes();
        updateStats();
        editor.refreshRows();
        setStatus("「" + cls + "」：词典填充了 " + count + " 条");
    }

    // ---------- 动作 ----------

    /** 打开文件选择框（可多组扩展名过滤）；取消返回 null。 */
    private Path askOpenFile(String title, FileChooser.ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filters);
        File file = chooser.showOpenDialog(stage);
        return file == null ? null : file.toPath();
    }

    /** 保存文件选择框；取消返回 null。 */
    private Path askSaveFile(String title, String initialName,
                             FileChooser.ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (initialName != null) {
            chooser.setInitialFileName(initialName);
        }
        chooser.getExtensionFilters().addAll(filters);
        File file = chooser.showSaveDialog(stage);
        return file == null ? null : file.toPath();
    }

    private void openJar() {
        Path path = askOpenFile("选择 jar 文件",
                new FileChooser.ExtensionFilter("Jar 文件", "*.jar"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        if (path == null) {
            return;
        }
        setStatus("正在解析 jar …");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() throws Exception {
                project.openJar(path);
                return true;
            }
        };
        task.setOnSucceeded(ev -> {
            rebuildTree();
            editor.showClass(null);
            // 新 jar：旧 jar 的反编译产物/索引全部失效
            invalidateDecompilerState();
            updateStats();
            String msg = "已打开 " + project.jarName() + "：共 "
                    + project.classOrder().size() + " 个类";
            if (!project.failed().isEmpty()) {
                msg += "（" + project.failed().size() + " 个类解析失败，已跳过）";
            }
            setStatus(msg);
        });
        task.setOnFailed(ev -> {
            Throwable ex = task.getException();
            String detail = ex == null ? null : ex.getMessage();
            Dialogs.error("错误", "打开 jar 失败：\n"
                    + (detail == null || detail.isEmpty()
                    ? "文件损坏或不是有效的 jar/zip" : detail));
            setStatus("打开失败");
        });
        new Thread(task, "open-jar").start();
    }

    private void exportJar() {
        if (!requireJar()) {
            return;
        }
        Path out = askSaveFile("导出汉化 Jar", "translated_" + project.jarName(),
                new FileChooser.ExtensionFilter("Jar 文件", "*.jar"));
        if (out == null) {
            return;
        }
        try {
            int count = project.exportJar(out, true);
            Dialogs.info("完成", "已写入 " + count + " 个汉化的类：\n" + out);
        } catch (Exception exc) {
            Dialogs.error("错误", "导出失败：\n" + exc.getMessage());
        }
    }

    private void importPack() {
        if (!requireJar()) {
            return;
        }
        Path file = askOpenFile("选择语言包",
                new FileChooser.ExtensionFilter("JSON 文件", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        if (file == null) {
            return;
        }
        Map<String, Object> pack;
        try {
            pack = LangPack.readPack(file);
        } catch (LangPack.LangPackException exc) {
            Dialogs.error("错误", exc.getMessage());
            return;
        }
        String expected = String.valueOf(pack.getOrDefault("target_jar_sha256", ""));
        try {
            String actual = project.jarSha256();
            if (!expected.isEmpty() && !expected.equals(actual)) {
                if (!Dialogs.confirm("警告",
                        "语言包的 SHA-256 与当前 jar 不匹配（可能不是同一版本）。\n仍要继续应用吗？")) {
                    return;
                }
            }
            long applied = project.applyLanguagePack(pack);
            refreshClassNodes();
            updateStats();
            editor.refreshRows();
            if (!project.lastMissing().isEmpty()) {
                showMissing();
                setStatus("语言包已应用：成功 " + applied + " 条，失效 "
                        + project.lastMissing().size() + " 条");
            } else {
                Dialogs.info("完成", "语言包已应用：" + applied + " 条翻译");
            }
        } catch (Exception exc) {
            Dialogs.error("错误", "应用语言包失败：" + exc.getMessage());
        }
    }

    private void exportPack() {
        if (!requireJar()) {
            return;
        }
        Path file = askSaveFile("导出语言包", "langpack.json",
                new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
        if (file == null) {
            return;
        }
        // 作者取首选项默认值（settings.pack_author），不再每次弹窗询问
        String author = settings.getString("pack_author").trim();
        try {
            project.exportPack(file, author);
            Dialogs.info("完成", "语言包已导出到：\n" + file
                    + "\n作者：" + (author.isEmpty() ? "（未设置，可在首选项中填写）" : author)
                    + "\n（含 " + project.classStatus().size() + " 个类的手动状态标记）");
        } catch (Exception exc) {
            Dialogs.error("错误", "导出失败：\n" + exc.getMessage());
        }
    }

    /** 展示导入语言包时的失效条目列表（独立窗口）。 */
    private void showMissing() {
        new MissingEntriesWindow(this).show();
    }

    private SearchWindow searchWindow;

    /** 工具栏全局搜索框输入后：状态栏即时显示匹配数（防抖触发）。 */
    private void quickSearchStatus() {
        if (!project.hasJar()) {
            setStatus("");
            return;
        }
        String kw = searchField.getText().trim();
        if (kw.isEmpty()) {
            setStatus("");
            return;
        }
        try {
            int n = project.search(kw, "both").size();
            setStatus(n == 0
                    ? "全局搜索「" + kw + "」：没有匹配结果"
                    : "全局搜索「" + kw + "」：共 " + n + " 处匹配（回车或点按钮查看明细）");
        } catch (Exception exc) {
            setStatus("搜索失败：" + exc.getMessage());
        }
    }

    void openSearch() {
        openGlobalSearch(searchField.getText().trim());
    }

    /** 打开全局搜索窗口并预填关键词；已打开则复用并更新。 */
    void openGlobalSearch(String keyword) {
        if (!requireJar()) {
            return;
        }
        if (searchWindow != null && searchWindow.isShowing()) {
            searchWindow.setQuery(keyword == null ? "" : keyword);
            searchWindow.toFront();
            return;
        }
        searchWindow = new SearchWindow(this, keyword == null ? "" : keyword);
        searchWindow.show();
    }

    // ---------- 源码查看 ----------

    public void setJavaInfo(String path, String version) {
        javaPathCache = path;
        javaVersionCache = version;
    }

    public String getJavaQuick() {
        if (javaPathCache != null) {
            return javaPathCache;
        }
        String configured = settings.getString("java_path");
        if (!configured.isEmpty() && new File(configured).isFile()) {
            return configured;
        }
        JavaEnv.JavaResult result = JavaEnv.findJava(settings);
        if (result.path() != null) {
            javaPathCache = result.path();
            javaVersionCache = result.version();
            return result.path();
        }
        return null;
    }

    public void invalidateDecompilerState() {
        indexCache = null;
        singleClassFiles.clear();
        sourcePanel.refreshTheme();
    }

    private void viewCurrentSource() {
        if (!requireJar()) {
            return;
        }
        String cls = editor.currentClass();
        if (cls == null && !project.classOrder().isEmpty()) {
            cls = project.classOrder().get(0);
        }
        if (cls == null) {
            return;
        }
        showSourceFor(cls, null);
    }

    private void onSourceTabOpened() {
        if (!project.hasJar()) {
            return;
        }
        String cls = editor.currentClass();
        if (cls == null && !project.classOrder().isEmpty()) {
            cls = project.classOrder().get(0);
        }
        if (cls == null) {
            return;
        }
        if (cls.equals(sourcePanel.currentClass())) {
            return; // 该类的源码已在显示中，无需重复反编译
        }
        sourcePanel.display(cls, null);
    }

    public void showSourceFor(String cls, String orig) {
        // 已在源码页且正显示该类时，切页签触发的自动反编译已覆盖，避免重复
        if (orig == null && tabPane.getSelectionModel().getSelectedItem() == sourceTab
                && cls.equals(sourcePanel.currentClass())) {
            return;
        }
        tabPane.getSelectionModel().select(sourceTab);
        sourcePanel.display(cls, orig);
    }

    public void jumpTo(String cls, String orig) {
        TreeItem<String> node = treeModel.nodeOf(cls);
        if (node != null) {
            classTree.getSelectionModel().select(node);
            int row = classTree.getRow(node);
            if (row >= 0) {
                classTree.scrollTo(row);
            }
        }
        editor.showClass(cls);
        editor.selectRow(orig);
    }

    public void jumpSourceToTable(String literal) {
        if (!project.hasJar()) {
            return;
        }
        String target = null;
        String current = sourcePanel.currentClass();
        if (current != null && project.textSet(current).contains(literal)) {
            target = current;
        } else {
            for (String c : project.classOrder()) {
                if (project.textSet(c).contains(literal)) {
                    target = c;
                    break;
                }
            }
        }
        if (target == null) {
            setStatus("源码字符串不在翻译列表中：" + Texts.displayText(literal).substring(0,
                    Math.min(60, literal.length())));
            return;
        }
        tabPane.getSelectionModel().select(editorTab);
        editor.showClass(target);
        editor.selectRow(literal);
    }

    /** 取当前反编译器（内置 Vineflower 自动兜底）。 */
    private DecompilerManager.Selection currentTool() {
        // 三种反编译器均内置：缺失时自动释放，保证有可用引擎
        DecompilerManager.ensureBundledAll();
        return DecompilerManager.currentDecompiler(settings);
    }

    /** class 内部名（UI 里类键带 .class 后缀，反编译器需要去掉）。 */
    private static String internalName(String cls) {
        return cls.endsWith(".class")
                ? cls.substring(0, cls.length() - ".class".length()) : cls;
    }

    /**
     * 按需准备某个类的反编译源码（不再整 jar 反编译）：
     * 已有整 jar 缓存 → "whole"(Path 缓存根)；已单类反编译过 → "single"(List&lt;Path&gt;)；
     * 正在反编译 → "loading"；不具备条件 → "bytecode"。结果均在 FX 线程回调。
     */
    public void ensureClassSources(String cls, java.util.function.BiConsumer<String, Object> then) {
        if (!project.hasJar()) {
            then.accept("bytecode", null);
            return;
        }
        String sha;
        try {
            sha = project.jarSha256();
        } catch (IOException e) {
            then.accept("bytecode", null);
            return;
        }
        Path whole = DecompilerManager.findCache(sha);
        if (whole != null) {
            then.accept("whole", whole);
            return;
        }
        List<Path> cached = singleClassFiles.get(cls);
        if (cached != null) {
            then.accept("single", cached);
            return;
        }
        DecompilerManager.Selection dec = currentTool();
        if (dec == null) {
            maybePromptSetup();
            then.accept("bytecode", null);
            return;
        }
        if (decompiling) {
            then.accept("loading", null);
            return;
        }
        DecompilerType type = dec.type();
        String inner = internalName(cls);
        // 目标类 + 内部类（如 Foo 与 Foo$Inner），整组一次喂给反编译器
        List<String> names = new ArrayList<>();
        for (String c : project.classOrder()) {
            String n = internalName(c);
            if (n.equals(inner) || n.startsWith(inner + "$")) {
                names.add(n);
            }
        }
        decompiling = true;
        setStatus("正在反编译 " + inner + " …");
        Task<List<java.nio.file.Path>> task = new Task<>() {
            @Override
            protected List<java.nio.file.Path> call() throws Exception {
                JavaEnv.JavaResult java = JavaEnv.findJava(settings);
                if (java.path() == null) {
                    throw new DecompilerManager.DecompileException(
                            "反编译器初始化失败，请重试或清理反编译缓存后再试。");
                }
                setJavaInfo(java.path(), java.version());
                return DecompilerManager.decompileClasses(project.jarPath().toString(), sha,
                        names, type);
            }
        };
        activeDecompileTask = task;
        task.setOnSucceeded(ev -> {
            decompiling = false;
            activeDecompileTask = null;
            List<java.nio.file.Path> files = task.getValue();
            if (files.isEmpty()) {
                setStatus("反编译未生成源码（可能类被混淆/无法解析），已回退字节码视图");
                then.accept("bytecode", null);
                return;
            }
            singleClassFiles.put(cls, files);
            setStatus("已完成「" + inner + "」反编译");
            then.accept("single", files);
        });
        task.setOnFailed(ev -> {
            decompiling = false;
            activeDecompileTask = null;
            String msg = task.getException() == null ? "" : task.getException().getMessage();
            setStatus("反编译失败：" + msg);
            if (!String.valueOf(msg).contains("已取消")) {
                Dialogs.error("反编译失败", String.valueOf(msg));
            }
            then.accept("bytecode", null);
        });
        task.setOnCancelled(ev -> {
            // 用户点击「取消反编译」：丢弃未回填的结果并回退字节码视图
            decompiling = false;
            activeDecompileTask = null;
            setStatus("已取消反编译");
            then.accept("bytecode", null);
        });
        new Thread(task, "decompile-class").start();
        then.accept("loading", null);
    }

    /**
     * 「取消反编译」：中断进行中的单类反编译，丢弃其尚未回填的结果并回退字节码视图。
     * 进程内反编译器为黑盒引擎，中断后旧线程可能仍在后台自然收尾，但结果不再上屏、
     * 状态与缓存均不写入（onCancelled 同步给出「已取消反编译」反馈）。
     */
    public void cancelDecompile() {
        Task<List<Path>> task = activeDecompileTask;
        if (task == null) {
            setStatus("当前没有正在进行的反编译");
            return;
        }
        task.cancel(true);
        // cancel(true) 会同步触发 onCancelled：置 decompiling=false、回退字节码、更新状态栏
    }

    public Map<String, Path> decompiledIndex() {
        if (indexCache == null) {
            try {
                Path cache = DecompilerManager.findCache(project.jarSha256());
                indexCache = cache != null ? DecompilerManager.buildFileIndex(cache) : Map.of();
            } catch (IOException e) {
                indexCache = Map.of();
            }
        }
        return indexCache;
    }

    private void maybePromptSetup() {
        if (javaPrompted || getJavaQuick() != null) {
            return;
        }
        javaPrompted = true;
        Dialogs.info("提示",
                "反编译器未能就绪，已回退字节码视图。\n"
                        + "可在「源码 → 清理反编译缓存」后重试。");
    }

    // ---------- 其他 ----------

    private boolean requireJar() {
        if (!project.hasJar()) {
            Dialogs.info("提示", "请先打开一个 jar 文件。");
            return false;
        }
        return true;
    }

    private void persistHideEmpty() {
        try {
            settings.set("hide_empty", hideEmptyCheck.isSelected());
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
    }

    private void onStatusFilterChanged() {
        try {
            settings.set("status_filter", stateFilterKey());
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        rebuildTree();
        updateStats();
    }

    public void onTranslationChanged() {
        refreshClassNodes();
        updateStats();
    }

    // ---------- 撤销 / 重做（翻译 · 不翻译 · 类状态） ----------

    /** 撤销/重做历史栈（回放动作在本类 applyHist 实现）。 */
    private final EditHistory history = new EditHistory();

    /** 记录一次译文变更（EditorPane 保存/清空后按生效值调用）。 */
    void recordTranslation(String cls, String orig, String before, String after) {
        if (before.equals(after)) {
            return;
        }
        history.push(new EditHistory.Hist("trans", cls, orig, before, after));
    }

    /** 记录「不翻译」切换。 */
    void recordSkip(String orig, boolean before, boolean after) {
        if (before == after) {
            return;
        }
        history.push(new EditHistory.Hist("skip", null, orig,
                before ? "1" : "0", after ? "1" : "0"));
    }

    /** 记录类状态手动标记（null 视为自动）。 */
    void recordClassMark(String cls, String beforeManual, String afterManual) {
        String b = beforeManual == null ? EditHistory.AUTO : beforeManual;
        String a = afterManual == null ? EditHistory.AUTO : afterManual;
        if (b.equals(a)) {
            return;
        }
        history.push(new EditHistory.Hist("mark", cls, null, b, a));
    }

    private String histLabel(String kind) {
        return switch (kind) {
            case "trans" -> "译文修改";
            case "skip" -> "「不翻译」标记";
            case "mark" -> "类状态标记";
            default -> kind;
        };
    }

    private void undoAction() {
        if (!history.canUndo()) {
            setStatus("没有可撤销的操作");
            return;
        }
        EditHistory.Hist h = history.takeUndo();
        applyHist(h, h.before());
        setStatus("已撤销：" + histLabel(h.kind()));
    }

    private void redoAction() {
        if (!history.canRedo()) {
            setStatus("没有可重做的操作");
            return;
        }
        EditHistory.Hist h = history.takeRedo();
        applyHist(h, h.after());
        setStatus("已重做：" + histLabel(h.kind()));
    }

    private void applyHist(EditHistory.Hist h, String target) {
        try {
            switch (h.kind()) {
                case "trans" -> project.setTranslation(h.cls(), h.orig(), target);
                case "skip" -> project.setTextSkipped(h.orig(), "1".equals(target));
                case "mark" -> project.setClassStatus(h.cls(),
                        EditHistory.AUTO.equals(target) ? null : target);
                default -> {
                    return;
                }
            }
        } catch (Exception exc) {
            return; // 写盘失败等不回滚界面
        }
        refreshClassNodes();
        updateStats();
        if (project.hasJar()) {
            String keepOrig = h.orig();
            editor.refreshRows();
            if (keepOrig != null) {
                editor.selectRow(keepOrig);
            }
        }
    }

    public void setStatus(String text) {
        statusLabel.setText(text);
    }

    public Settings settings() {
        return settings;
    }

    public Theme theme() {
        return theme;
    }

    public Project project() {
        return project;
    }

    public EditorPane editor() {
        return editor;
    }

    public SourcePanel sourcePane() {
        return sourcePanel;
    }

    public Stage stage() {
        return stage;
    }

    public boolean showInternal() {
        return showInternalItem.isSelected();
    }

    // ---------- 首选项联动入口 ----------

    public String themeMode() {
        return theme.mode();
    }

    public void applyThemeMode(String mode) {
        setThemeMode(mode);
    }

    public boolean hideEmptyEnabled() {
        return hideEmptyCheck.isSelected();
    }

    public void applyHideEmpty(boolean v) {
        hideEmptyCheck.setSelected(v);
        hideEmptyItem.setSelected(v);
        onFilterChanged(null);
    }

    public boolean legendVisible() {
        return settings.getBool("legend_visible");
    }

    public void applyLegendVisible(boolean v) {
        try {
            settings.set("legend_visible", v);
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        buildLegend();
    }

    public String statusFilterKey() {
        return stateFilterKey();
    }

    public void applyStatusFilter(String key) {
        for (String[] f : STATE_FILTERS) {
            if (f[0].equals(key)) {
                statusBox.setValue(f[1]);
                return;
            }
        }
    }

    private void onClose() {
        try {
            settings.set("theme", theme.mode());
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        // 译文自动保存：退出前冲刷编辑区未落库内容
        try {
            editor.flushEdit();
        } catch (Exception ignored) {
            // 忽略
        }
        cancelDecompile();
        DecompilerManager.cleanupOnExit();
        Platform.exit();
    }
}
