package com.jartrans.ui;

import com.jartrans.core.AppMeta;
import com.jartrans.core.Project;
import com.jartrans.core.Settings;
import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.log.Log;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * JavaFX 主应用与控制器（对应 Python gui/app.py）。
 * 界面骨架在 main_view.fxml 中，本类通过 FXMLLoader.setController 注入。
 */
public class MainApp extends Application {

    private static final System.Logger LOG = Log.get(MainApp.class);

    // 类状态文案与筛选项的唯一来源在 ClassStates；这里保留别名，供既有调用与守护测试使用。
    static final Map<String, String> STATE_LABEL = ClassStates.LABELS;
    static final List<String[]> STATE_FILTERS = ClassStates.FILTERS;

    // 可自定义快捷键的定义与解析见 Shortcuts（SHORTCUT_DEFS 单一来源，存于 settings 的 "key_<id>"）。

    private Settings settings;
    private Theme theme;
    private Project project;
    private Stage stage;
    private Scene mainScene;
    /** 重操作统一后台执行（互斥单飞：进行中拒绝并发触发）。 */
    private BackgroundOps bgOps;
    /** 输入防抖：过滤框每字符即时过滤、工具栏搜索框即时反馈。 */
    private final PauseTransition filterDebounce = new PauseTransition(Duration.millis(120));

    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(180));

    /** 重操作后台执行器（ProjectIoActions 等协作者使用）。 */
    BackgroundOps bgOps() {
        return bgOps;
    }

    /** 类树控件（TreeContextMenu 使用）。 */
    TreeView<String> classTree() {
        return classTree;
    }

    /** 类树模型（TreeContextMenu 使用）。 */
    ClassTreeModel treeModel() {
        return treeModel;
    }

    /** 主窗词典下拉（DictCoordinator 使用）。 */
    ComboBox<String> dictBox() {
        return dictBox;
    }

    private final DecompileController decompile = new DecompileController(this);
    /** 撤销/重做：译文修改 · 「不翻译」标记 · 类状态标记。 */
    private final UndoRedo undoRedo = new UndoRedo(this);
    /** jar/语言包文件 IO 动作（含后台执行与完成回调）。 */
    private final ProjectIoActions ioActions = new ProjectIoActions(this);
    /** 类树右键菜单（标记/填充/复制/展开折叠）。 */
    private final TreeContextMenu treeMenu = new TreeContextMenu(this);
    /** 词典监听器与主窗下拉/统计同步。 */
    private final DictCoordinator dicts = new DictCoordinator(this);
    /** FX 线程无响应看门狗（卡死时把线程栈写入日志）。 */
    private final FxWatchdog watchdog = new FxWatchdog();
    /** 词典结构/激活词典变化时需要同步的窗口回调（词典条目窗口、词典管理窗口多实例）。 */

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

    @FXML
    private Button openJarBtn;

    @FXML
    private Button exportJarBtn;

    @FXML
    private MenuItem globalSearchItem;

    @FXML
    private MenuItem findInClassItem;

    @FXML
    private MenuItem replaceInClassItem;

    @FXML
    private VBox treePlaceholder;

    @FXML
    private Label treePlaceholderTitle;

    @FXML
    private Label treePlaceholderHint;

    // 类树状态与过滤/构建/查询逻辑（纯模型，不碰 TreeView 控件）
    private ClassTreeModel treeModel;

    // 图例控件（圆点走 CSS 状态类，无代码上色）

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        this.settings = new Settings();
        this.theme = new Theme(settings.getString(Settings.Keys.THEME));
        this.project = new Project();
        this.bgOps = new BackgroundOps(stage, this::setStatus, busy -> {
            if (busy) {
                progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
            } else {
                updateStats(); // 恢复为翻译进度
            }
        });

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/com/jartrans/ui/main_view.fxml"));
        loader.setController(this);
        Parent root = loader.load();

        stage.setTitle(AppMeta.APP_NAME + " — " + AppMeta.APP_TAGLINE);
        stage.setMinWidth(1100);
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
        updateJarDependentActions();
        updateTreePlaceholder();
        watchdog.start();
    }

    // ---------- 初始化 ----------

    private void initUi() {
        treeModel = new ClassTreeModel(project);
        // 主题
        theme.onChange(this::onThemeChanged);
        String mode = theme.mode();
        setThemeSelections(mode);

        showInternalItem.setSelected(false);
        hideEmptyItem.setSelected(settings.getBool(Settings.Keys.HIDE_EMPTY));
        hideEmptyCheck.setSelected(settings.getBool(Settings.Keys.HIDE_EMPTY));
        hideEmptyItem.selectedProperty().addListener((o, ov, nv) -> hideEmptyCheck.setSelected(nv));
        hideEmptyCheck.selectedProperty().addListener((o, ov, nv) -> hideEmptyItem.setSelected(nv));

        // 词典切换
        dictBox.valueProperty().addListener((o, ov, nv) -> onDictSelected(nv));
        syncDictBox();

        // 状态筛选
        statusBox.getItems().setAll(STATE_FILTERS.stream().map(f -> f[1]).toList());
        statusBox.setValue(stateLabelOfKey(settings.getString(Settings.Keys.STATUS_FILTER)));
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
        // 多选：右键菜单可对选中的多个类批量标记/填充/复制路径
        classTree.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
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
            if (e.getButton() == MouseButton.PRIMARY) {
                treeMenu.dismiss();
            }
        });
        classTree.setOnContextMenuRequested(e -> {
            TreeItem<String> item = classTree.getSelectionModel().getSelectedItem();
            if (item == null) {
                return;
            }
            treeMenu.show(item, e.getScreenX(), e.getScreenY());
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

    /** 填充类状态图例（是否显示由首选项「显示类状态图例」控制）。 */
    private void buildLegend() {
        legendBox.getChildren().clear();
        legendBox.setAlignment(Pos.CENTER_LEFT);
        if (!legendVisible()) {
            return;
        }
        for (String st : ClassStates.ALL) {
            Circle dot = new Circle(4);
            dot.getStyleClass().addAll("state-dot", "cell-state-" + st);
            Tooltip.install(dot, new Tooltip(STATE_LABEL.get(st)));
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
        ioActions.openJar();
    }

    @FXML
    public void onExportJar(ActionEvent e) {
        ioActions.exportJar();
    }

    @FXML
    public void onImportPack(ActionEvent e) {
        ioActions.importPack();
    }

    @FXML
    public void onExportPack(ActionEvent e) {
        ioActions.exportPack();
    }

    @FXML
    public void onShowMissing(ActionEvent e) {
        ioActions.showMissing();
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
        ioActions.fillClassFromDictionary();
    }

    @FXML
    public void onFillFromDictionary(ActionEvent e) {
        ioActions.fillFromDictionary();
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
        decompile.invalidate();
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
        Dialogs.info(
                "关于",
                AppMeta.APP_NAME + " — " + AppMeta.APP_TAGLINE + "\n"
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
        String stored = settings.getString(Settings.Keys.SHORTCUT_PREFIX + id);
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
        settings.setOrIgnore(Settings.Keys.SHORTCUT_PREFIX + id, norm);
        registerShortcuts(mainScene);
        return true;
    }

    public void resetShortcut(String id) {
        settings.setOrIgnore(Settings.Keys.SHORTCUT_PREFIX + id, Shortcuts.defaultFor(id));
        registerShortcuts(mainScene);
    }

    public void resetAllShortcuts() {
        for (String[] d : Shortcuts.DEFS) {
            settings.setOrIgnore(Settings.Keys.SHORTCUT_PREFIX + d[0], d[2]);
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
        scene.getAccelerators()
                .put(
                        new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                        this::redoAction);
        put(scene, "save", () -> {
            if (project.hasJar()) {
                editor.saveTranslation();
            }
        });
        put(scene, "find", this::openFindInClass);
        put(scene, "replace", this::openReplaceInClass);
        put(scene, "gsearch", this::openSearch);
        put(scene, "prefs", this::openPreferences);
        refreshMenuShortcutHints();
    }

    /**
     * 菜单里的快捷键文案按当前绑定刷新：用户自定义快捷键后不再显示过期提示
     * （FXML 里只写动作名，提示由这里统一补全）。
     */
    private void refreshMenuShortcutHints() {
        globalSearchItem.setText("全包搜索 (" + shortcutText("gsearch") + ")");
        findInClassItem.setText("当前类内搜索 (" + shortcutText("find") + ")");
        replaceInClassItem.setText("当前类内替换译文 (" + shortcutText("replace") + ")");
    }

    /** 未打开 jar 时禁用依赖工程内容的工具栏动作；打开成功后恢复。 */
    void updateJarDependentActions() {
        exportJarBtn.setDisable(!project.hasJar());
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
        settings.setOrIgnore(Settings.Keys.THEME, theme.mode());
        String suffix = theme.mode().equals(Theme.MODE_SYSTEM) ? "（" + (theme.dark() ? "系统为深色" : "系统为浅色") + "）" : "";
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
        return ClassStates.filterKeyOf(label);
    }

    /** 状态筛选键 → 显示名；未知返回 "全部"。 */
    static String stateLabelOfKey(String key) {
        return ClassStates.filterLabelOf(key);
    }

    /** 重建左侧类树（ioActions 打开 jar 后调用）。 */
    void rebuildTree() {
        TreeItem<String> newRoot = new TreeItem<>("");
        classTree.setRoot(newRoot);
        if (!project.hasJar()) {
            updateTreePlaceholder();
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
        updateTreePlaceholder();
    }

    /** 类树空状态：未打开 jar 或过滤后无匹配类时显示引导文案。 */
    private void updateTreePlaceholder() {
        boolean empty =
                classTree.getRoot() == null || classTree.getRoot().getChildren().isEmpty();
        treePlaceholder.setVisible(empty);
        treePlaceholder.setManaged(empty);
        if (!empty) {
            return;
        }
        if (project.hasJar()) {
            treePlaceholderTitle.setText("没有匹配的类");
            treePlaceholderHint.setText("调整过滤词或「状态」筛选，或在「查看」菜单勾选隐藏空类");
        } else {
            treePlaceholderTitle.setText("先打开一个 Jar");
            treePlaceholderHint.setText("菜单「文件 → 打开 Jar」，或点工具栏的「打开 Jar」");
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

    void expandAll() {
        ClassTreeModel.expandRecursively(classTree.getRoot());
    }

    void collapseAll() {
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

    void mark(List<String> classes, String state) {
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
        refreshAfterModelChange(false);
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

    // ---------- 统计 ----------

    void updateStats() {
        if (!project.hasJar()) {
            statsLabel.setText("");
            statsLabel.setTooltip(null);
            progressBar.setProgress(0);
            progressBar.setVisible(false);
            progressBar.setManaged(false);
            return;
        }
        long[] totalDone = project.stats();
        long total = totalDone[0];
        long done = totalDone[1];
        progressBar.setVisible(true);
        progressBar.setManaged(true);
        progressBar.setProgress(total == 0 ? 0 : done * 1.0 / total);
        // 状态栏只显示最关键的进度与词典规模；各状态类数收进 tooltip，避免长串挤占
        statsLabel.setText(
                "已完成 " + done + "/" + total + " · 词典 " + project.dictionary().size() + " 条");
        Map<String, Integer> cs = project.classStats();
        StringBuilder parts = new StringBuilder();
        for (String st : ClassStates.STATS_ORDER) {
            if (parts.length() > 0) {
                parts.append("  ");
            }
            parts.append(STATE_LABEL.get(st)).append(" ").append(cs.get(st));
        }
        statsLabel.setTooltip(new Tooltip("类：" + parts + "    词典「" + project.dictionaryLabel() + "」"));
    }

    // ---------- 词典 ----------

    public void syncDictBox() {
        dicts.syncBox();
    }

    /** 注册词典变化回调（词典条目/管理窗口在构造时调用，窗口隐藏时注销）。 */
    public void addDictsListener(Runnable listener) {
        dicts.addListener(listener);
    }

    public void removeDictsListener(Runnable listener) {
        dicts.removeListener(listener);
    }

    /**
     * 词典结构（新建/删除/重命名/导入）或激活词典变化后统一刷新：
     * 主窗下拉与统计 + 所有已打开的词典相关窗口。
     */
    void notifyDictsChanged() {
        dicts.notifyChanged();
    }

    /**
     * 切换当前词典并同步全部相关界面（主窗下拉/统计、编辑区、所有词典窗口）。
     * 主窗下拉、词典条目窗口、词典管理窗口三处的切换协议在此单点实现。
     */
    public void switchDictionary(String name) {
        if (name == null || project.dicts() == null) {
            return;
        }
        if (!name.equals(project.dicts().activeName())) {
            try {
                project.useDictionary(name);
            } catch (Exception e) {
                // 切换失败不阻断：界面照常刷新到当前激活词典
                LOG.log(System.Logger.Level.WARNING, "词典切换失败：" + name, e);
            }
        }
        // 无论工程侧是否已激活（新建后已 setActive），选择都要落盘
        settings.setOrIgnore(Settings.Keys.DICTIONARY, name);
        setStatus("已切换到词典「" + name + "」（" + project.dictionary().size() + " 条）");
        if (editor.currentClass() != null) {
            editor.refreshRows();
        }
        notifyDictsChanged();
    }

    private void onDictSelected(String name) {
        if (dicts.isAdjusting()) {
            return; // syncBox 程序化设置 items/value 触发的回流，忽略
        }
        switchDictionary(name);
    }

    void manageDictionaries() {
        if (project.dicts() == null) {
            Dialogs.info("提示", "当前为命令行/单文件模式运行，未启用多词典。");
            return;
        }
        new DictManagerDialog(this).show();
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
            setStatus(n == 0 ? "全局搜索「" + kw + "」：没有匹配结果" : "全局搜索「" + kw + "」：共 " + n + " 处匹配（回车或点按钮查看明细）");
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

    // ---------- 源码查看（实现见 DecompileController，此处转发） ----------

    public void invalidateDecompilerState() {
        decompile.invalidate();
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
        if (orig == null
                && tabPane.getSelectionModel().getSelectedItem() == sourceTab
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
            setStatus("源码字符串不在翻译列表中：" + Texts.displayText(literal).substring(0, Math.min(60, literal.length())));
            return;
        }
        tabPane.getSelectionModel().select(editorTab);
        editor.showClass(target);
        editor.selectRow(literal);
    }

    /** 按需准备某个类的反编译源码；回调语义见 DecompileController。 */
    public void ensureClassSources(String cls, BiConsumer<String, Object> then) {
        decompile.ensureClassSources(cls, then);
    }

    /** 取消进行中的单类反编译；无任务时只在状态栏提示。 */
    public void cancelDecompile() {
        decompile.cancel();
    }

    // ---------- 其他 ----------

    /** 未打开 jar 时提示并返回 false（ioActions 各动作的前置检查）。 */
    boolean requireJar() {
        if (!project.hasJar()) {
            Dialogs.info("提示", "请先打开一个 jar 文件。");
            return false;
        }
        return true;
    }

    private void persistHideEmpty() {
        settings.setOrIgnore(Settings.Keys.HIDE_EMPTY, hideEmptyCheck.isSelected());
    }

    private void onStatusFilterChanged() {
        settings.setOrIgnore(Settings.Keys.STATUS_FILTER, stateFilterKey());
        rebuildTree();
        updateStats();
    }

    public void onTranslationChanged() {
        refreshAfterModelChange(false);
    }

    /** 模型变化后的统一刷新：类列表（节点文案/颜色） + 统计；refreshEditor 时一并刷新翻译表格。 */
    void refreshAfterModelChange(boolean refreshEditor) {
        refreshClassNodes();
        updateStats();
        if (refreshEditor) {
            editor.refreshRows();
        }
    }

    // ---------- 撤销 / 重做（实现见 UndoRedo，此处转发） ----------

    /** 记录一次译文变更（EditorPane 保存/清空后按生效值调用）。 */
    void recordTranslation(String cls, String orig, String before, String after) {
        undoRedo.recordTranslation(cls, orig, before, after);
    }

    /** 把一组替换变更合并为单条撤销记录（「全部替换」一步回滚）。 */
    void recordBatchTranslation(List<Project.ReplaceChange> changes, String after) {
        undoRedo.recordBatchTranslation(changes, after);
    }

    /** 记录「不翻译」切换。 */
    void recordSkip(String orig, boolean before, boolean after) {
        undoRedo.recordSkip(orig, before, after);
    }

    /** 记录类状态手动标记（null 视为自动）。 */
    void recordClassMark(String cls, String beforeManual, String afterManual) {
        undoRedo.recordClassMark(cls, beforeManual, afterManual);
    }

    private void undoAction() {
        undoRedo.undo();
    }

    private void redoAction() {
        undoRedo.redo();
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
        return settings.getBool(Settings.Keys.LEGEND_VISIBLE);
    }

    public void applyLegendVisible(boolean v) {
        settings.setOrIgnore(Settings.Keys.LEGEND_VISIBLE, v);
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
        settings.setOrIgnore(Settings.Keys.THEME, theme.mode());
        // 译文自动保存：退出前冲刷编辑区未落库内容
        try {
            editor.flushEdit();
        } catch (Exception e) {
            LOG.log(System.Logger.Level.WARNING, "退出前冲刷译文失败", e);
        }
        // 防抖的设置/进度写盘在退出前强制落盘
        project.flushProgress();
        settings.flush();
        watchdog.stop();
        cancelDecompile();
        DecompilerManager.cleanupOnExit();
        Platform.exit();
    }
}
