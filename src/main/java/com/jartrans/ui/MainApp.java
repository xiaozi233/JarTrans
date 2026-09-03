package com.jartrans.ui;

import com.jartrans.core.AppMeta;
import com.jartrans.core.LangPack;
import com.jartrans.core.Project;
import com.jartrans.core.Settings;
import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.JavaEnv;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JavaFX 主应用与控制器（对应 Python gui/app.py）。
 * 界面骨架在 main_view.fxml 中，本类通过 FXMLLoader.setController 注入。
 */
public class MainApp extends javafx.application.Application {

    // 类状态常量
    static final List<String> CLASS_STATES = Project.CLASS_STATES;
    static final List<String> MARK_STATES = Project.MANUAL_STATES;
    static final Map<String, String> STATE_LABEL = new LinkedHashMap<>(Map.of(
            "empty", "无字符串", "todo", "未开始", "doing", "翻译中",
            "done", "已完成", "ignore", "已忽略"));
    static final List<String[]> STATE_FILTERS = List.of(
            new String[]{"all", "全部"}, new String[]{"todo", "未开始"},
            new String[]{"doing", "翻译中"}, new String[]{"done", "已完成"},
            new String[]{"ignore", "已忽略"}, new String[]{"empty", "无字符串"});
    static final Map<String, String> THEME_ITEMS = new LinkedHashMap<>(Map.of(
            "system", "跟随系统", "light", "浅色", "dark", "深色"));

    // 类树节点信息
    private static final String TYPE_ROOT = "root";
    private static final String TYPE_DIR = "dir";
    private static final String TYPE_CLASS = "class";    private Settings settings;
    private Theme theme;
    private Project project;
    private Stage stage;

    private String javaPathCache;
    private String javaVersionCache;
    private boolean decompiling;
    private volatile boolean decompileCancelled;
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
    private javafx.scene.control.RadioMenuItem themeSystemItem;
    @FXML
    private javafx.scene.control.RadioMenuItem themeLightItem;
    @FXML
    private javafx.scene.control.RadioMenuItem themeDarkItem;
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
    private javafx.scene.layout.FlowPane legendBox;
    @FXML
    private TreeView<String> classTree;
    @FXML
    private TabPane tabPane;
    @FXML
    private javafx.scene.control.Tab editorTab;
    @FXML
    private javafx.scene.control.Tab sourceTab;
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

    // 树内部状态
    private final Map<String, TreeItem<String>> classNodes = new HashMap<>();
    private final Map<TreeItem<String>, String[]> nodeInfo = new HashMap<>(); // type / path / cls
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
        javafx.scene.Parent root = loader.load();

        stage.setTitle(AppMeta.APP_NAME + " — " + AppMeta.APP_TAGLINE);
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        Scene scene = new Scene(root, 1320, 840);
        theme.attach(scene);
        // Ctrl+Z 撤销 / Ctrl+Y、Ctrl+Shift+Z 重做（翻译、不翻译、类状态）
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.Z,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN), this::undoAction);
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.Y,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN), this::redoAction);
        scene.getAccelerators().put(
                new javafx.scene.input.KeyCodeCombination(javafx.scene.input.KeyCode.Z,
                        javafx.scene.input.KeyCombination.CONTROL_DOWN,
                        javafx.scene.input.KeyCombination.SHIFT_DOWN), this::redoAction);
        stage.setScene(scene);
        stage.show();
        stage.setOnCloseRequest(e -> onClose());

        initUi();
    }

    // ---------- 初始化 ----------

    private void initUi() {
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
        String savedFilter = settings.getString("status_filter");
        statusBox.setValue(STATE_FILTERS.stream()
                .filter(f -> f[0].equals(savedFilter)).map(f -> f[1]).findFirst()
                .orElse("全部"));
        statusBox.valueProperty().addListener((o, ov, nv) -> onStatusFilterChanged());

        // 类树
        classTree.setShowRoot(false);
        classTree.setCellFactory(tv -> new javafx.scene.control.TreeCell<>() {
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
                String[] info = nodeInfo.get(getTreeItem());
                if (info != null && TYPE_CLASS.equals(info[0])) {
                    String st = project.classState(info[1]);
                    getStyleClass().add("cell-state-" + st);
                    // 彩色状态圆点：与文字同规则（.state-dot.cell-state-*）
                    javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(3.5);
                    dot.getStyleClass().addAll("state-dot", "cell-state-" + st);
                    setGraphic(dot);
                } else if (info != null && TYPE_DIR.equals(info[0])) {
                    getStyleClass().add("cell-dir");
                }
            }
        });
        classTree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> onTreeSelect(nv));
        // 左键点树任意处即收起右键菜单（修复右键后再左键点同类不关闭）
        classTree.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() == javafx.scene.input.MouseButton.PRIMARY
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

    private boolean isLegendCompact() {
        try {
            return settings.getBool("legend_compact");
        } catch (Exception ignored) {
            return false;
        }
    }

    /** 填充类状态图例。详细模式 = 彩色文字+圆点；精简模式 = 只留彩色圆点。
        颜色全部由 CSS（.state-dot/.legend-txt 的 cell-state-* 类）提供。 */
    private void buildLegend() {
        legendBox.getChildren().clear();
        legendBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        final boolean compact = isLegendCompact();
        // 精简/详细切换按钮，置于最前
        Button toggle = new Button(compact ? "详细" : "精简");
        toggle.setStyle("-fx-font-size: 11px; -fx-padding: 1 8 1 8;");
        toggle.setTooltip(new javafx.scene.control.Tooltip(compact
                ? "图例仅圆点，点击显示文字说明" : "图例精简：只保留彩色圆点"));
        toggle.setOnAction(e -> {
            try {
                settings.set("legend_compact", !compact);
            } catch (Exception ignored) {
                // 写盘失败不阻断
            }
            buildLegend();
        });
        legendBox.getChildren().add(toggle);
        for (String st : List.of("todo", "doing", "done", "ignore", "empty")) {
            javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
            dot.getStyleClass().addAll("state-dot", "cell-state-" + st);
            javafx.scene.control.Tooltip.install(dot,
                    new javafx.scene.control.Tooltip(STATE_LABEL.get(st)));
            if (compact) {
                legendBox.getChildren().add(dot);
                continue;
            }
            Label text = new Label(STATE_LABEL.get(st));
            text.getStyleClass().addAll("legend-txt", "cell-state-" + st);
            text.setStyle("-fx-font-size: 11px;");
            HBox pair = new HBox(3, dot, text);
            pair.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
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
    public void onOpenDecompilerManager(ActionEvent e) {
        new DecompilerManagerDialog(this).show();
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
        setStatus("主题已切换：" + THEME_ITEMS.get(theme.mode()) + suffix);
    }

    // ---------- 类树 ----------

    private List<String> filteredClasses() {
        String keyword = filterField.getText().trim().toLowerCase();
        String state = stateFilterKey();
        boolean hideEmpty = hideEmptyCheck.isSelected();
        List<String> out = new ArrayList<>();
        for (String cls : project.classOrder()) {
            if (hideEmpty && !project.hasTranslatable(cls)) {
                continue;
            }
            if (!"all".equals(state) && !project.classState(cls).equals(state)) {
                continue;
            }
            if (!keyword.isEmpty() && !cls.toLowerCase().contains(keyword)) {
                continue;
            }
            out.add(cls);
        }
        return out;
    }

    private String stateFilterKey() {
        String label = statusBox.getValue();
        for (String[] f : STATE_FILTERS) {
            if (f[1].equals(label)) {
                return f[0];
            }
        }
        return "all";
    }

    private void rebuildTree() {
        String keep = editor.currentClass();
        classTree.getRoot();
        TreeItem<String> newRoot = new TreeItem<>("");
        classNodes.clear();
        nodeInfo.clear();
        classTree.setRoot(newRoot);

        if (!project.hasJar()) {
            return;
        }
        List<String> visible = filteredClasses();
        boolean filtering = !filterField.getText().trim().isEmpty()
                || !"all".equals(stateFilterKey()) || hideEmptyCheck.isSelected();
        TreeItem<String> root = new TreeItem<>(project.jarName() + "（"
                + visible.size() + "/" + project.classOrder().size() + " 类）");
        root.setExpanded(true);
        newRoot.getChildren().add(root);
        nodeInfo.put(root, new String[]{TYPE_ROOT, ""});
        Map<String, TreeItem<String>> dirs = new HashMap<>();
        for (String cls : visible) {
            String[] parts = cls.split("/");
            TreeItem<String> parent = root;
            StringBuilder path = new StringBuilder();
            for (int i = 0; i < parts.length - 1; i++) {
                String part = parts[i];
                path.append(path.length() > 0 ? "/" : "").append(part);
                String key = path.toString();
                TreeItem<String> d = dirs.get(key);
                if (d == null) {
                    d = new TreeItem<>(part);
                    d.setExpanded(filtering);
                    parent.getChildren().add(d);
                    nodeInfo.put(d, new String[]{TYPE_DIR, key});
                    dirs.put(key, d);
                }
                parent = d;
            }
            String st = project.classState(cls);
            TreeItem<String> node = new TreeItem<>(nodeText(cls, st));
            parent.getChildren().add(node);
            nodeInfo.put(node, new String[]{TYPE_CLASS, cls});
            classNodes.put(cls, node);
        }
        if (keep != null && classNodes.containsKey(keep)) {
            TreeItem<String> kept = classNodes.get(keep);
            kept.setExpanded(true);
            TreeItem<String> p = kept.getParent();
            while (p != null) {
                p.setExpanded(true);
                p = p.getParent();
            }
            classTree.getSelectionModel().select(kept);
            int row = classTree.getRow(kept);
            if (row >= 0) {
                classTree.scrollTo(row);
            }
        }
    }

    private String nodeText(String cls, String state) {
        String name = cls.substring(cls.lastIndexOf('/') + 1);
        switch (state) {
            case "done":
                return name + "  ✓";
            case "ignore":
                return name + "  —";
            case "empty":
                return name + "  (0)";
            default: {
                int n = project.untranslatedCount(cls);
                return name + "  (" + n + ")";
            }
        }
    }

    /** 只刷新已有节点的文字与颜色（不改变展开状态）。 */
    void refreshClassNodes() {
        if (!"all".equals(stateFilterKey()) || hideEmptyCheck.isSelected()
                || !filterField.getText().trim().isEmpty()) {
            rebuildTree();
            return;
        }
        for (Map.Entry<String, TreeItem<String>> e : classNodes.entrySet()) {
            String cls = e.getKey();
            String st = project.classState(cls);
            e.getValue().setValue(nodeText(cls, st));
        }
        classTree.refresh();
    }

    private void expandAll() {
        expandNode(classTree.getRoot());
    }

    private void expandNode(TreeItem<String> node) {
        node.setExpanded(true);
        for (TreeItem<String> child : node.getChildren()) {
            expandNode(child);
        }
    }

    private void collapseAll() {
        for (TreeItem<String> child : classTree.getRoot().getChildren()) {
            collapseNode(child);
        }
    }

    private void collapseNode(TreeItem<String> node) {
        for (TreeItem<String> child : node.getChildren()) {
            collapseNode(child);
        }
        node.setExpanded(false);
    }

    private void onTreeSelect(TreeItem<String> node) {
        if (node == null) {
            return;
        }
        String[] info = nodeInfo.get(node);
        if (info == null || !TYPE_CLASS.equals(info[0])) {
            return;
        }
        String cls = info[1];
        if (editor.currentClass() == null || !editor.currentClass().equals(cls)) {
            editor.showClass(cls);
        }
        if (tabPane.getSelectionModel().getSelectedItem() == sourceTab) {
            sourcePanel.display(cls, null);
        }
    }

    private void showTreeMenu(TreeItem<String> node, double x, double y) {
        String[] info = nodeInfo.get(node);
        List<String> targets;
        if (info == null || TYPE_ROOT.equals(info[0])) {
            targets = new ArrayList<>(classNodes.keySet());
        } else if (TYPE_DIR.equals(info[0])) {
            String prefix = info[1] + "/";
            targets = new ArrayList<>();
            for (String c : classNodes.keySet()) {
                if (c.startsWith(prefix)) {
                    targets.add(c);
                }
            }
        } else {
            targets = List.of(info[1]);
        }
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
                ignoreItem, new javafx.scene.control.SeparatorMenuItem(),
                fillItem, copyItem, new javafx.scene.control.SeparatorMenuItem(),
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
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(
                Map.of(javafx.scene.input.DataFormat.PLAIN_TEXT, text));
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
        Dialogs.info("完成", "词典自动填充了 " + count + " 条未翻译字符串（标记为\"自动填充" + "）。");
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

    private void openJar() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 jar 文件");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Jar 文件", "*.jar"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        Path path = file.toPath();
        setStatus("正在解析 jar …");
        Task<Boolean> task = new Task<>() {
            @Override
            protected Boolean call() {
                try {
                    project.openJar(path);
                    return true;
                } catch (Exception exc) {
                    return false;
                }
            }
        };
        task.setOnSucceeded(ev -> {
            boolean ok = task.getValue();
            if (!ok) {
                Dialogs.error("错误", "打开 jar 失败：\n"
                        + (task.getException() != null ? task.getException().getMessage()
                        : "文件损坏或不是有效的 jar/zip"));
                setStatus("打开失败");
                return;
            }
            rebuildTree();
            editor.showClass(null);
            singleClassFiles.clear();
            updateStats();
            String msg = "已打开 " + project.jarName() + "：共 "
                    + project.classOrder().size() + " 个类";
            if (!project.failed().isEmpty()) {
                msg += "（" + project.failed().size() + " 个类解析失败，已跳过）";
            }
            setStatus(msg);
        });
        new Thread(task, "open-jar").start();
    }

    private void exportJar() {
        if (!requireJar()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出汉化 Jar");
        chooser.setInitialFileName("translated_" + project.jarName());
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Jar 文件", "*.jar"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            int count = project.exportJar(file.toPath(), true);
            Dialogs.info("完成", "已写入 " + count + " 个汉化的类：\n" + file.toPath());
        } catch (Exception exc) {
            Dialogs.error("错误", "导出失败：\n" + exc.getMessage());
        }
    }

    private void importPack() {
        if (!requireJar()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择语言包");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("JSON 文件", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        Map<String, Object> pack;
        try {
            pack = LangPack.readPack(file.toPath());
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
        String language = Dialogs.ask("导出语言包", "语言代码（如 zh_CN）：", "zh_CN");
        if (language == null) {
            return;
        }
        String author = Dialogs.ask("导出语言包", "作者：", "");
        if (author == null) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("导出语言包");
        chooser.setInitialFileName("langpack.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
        File file = chooser.showSaveDialog(stage);
        if (file == null) {
            return;
        }
        try {
            project.exportPack(file.toPath(), language.isBlank() ? "zh_CN" : language,
                    author.isBlank() ? "" : author);
            Dialogs.info("完成", "语言包已导出到：\n" + file.toPath()
                    + "\n（含 " + project.classStatus().size() + " 个类的手动状态标记）");
        } catch (Exception exc) {
            Dialogs.error("错误", "导出失败：\n" + exc.getMessage());
        }
    }

    private void showMissing() {
        List<LangPack.MissingEntry> missing = project.lastMissing();
        Stage win = new Stage();
        win.setTitle("失效条目（" + missing.size() + "）");
        win.setWidth(880);
        win.setHeight(460);
        win.initOwner(stage);
        win.setResizable(true);
        win.setMinWidth(640);
        win.setMinHeight(360);
        win.initModality(javafx.stage.Modality.NONE);
        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(6);
        vbox.setPadding(new Insets(6));
        vbox.getStyleClass().add("root-pane");
        javafx.scene.control.TableView<LangPack.MissingEntry> table =
                new javafx.scene.control.TableView<>();
        javafx.scene.layout.VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);
        javafx.scene.control.TableColumn<LangPack.MissingEntry, String> c1 =
                new javafx.scene.control.TableColumn<>("类");
        c1.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(d.getValue().cls()));
        javafx.scene.control.TableColumn<LangPack.MissingEntry, String> c2 =
                new javafx.scene.control.TableColumn<>("原字符串");
        c2.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                Texts.displayText(d.getValue().orig())));
        javafx.scene.control.TableColumn<LangPack.MissingEntry, String> c3 =
                new javafx.scene.control.TableColumn<>("译文");
        c3.setCellValueFactory(d -> new javafx.beans.property.SimpleStringProperty(
                Texts.displayText(d.getValue().trans())));
        //noinspection unchecked
        table.getColumns().addAll(c1, c2, c3);
        table.getItems().setAll(missing);
        vbox.getChildren().add(table);
        javafx.scene.control.Button toDict = new javafx.scene.control.Button(
                "全部存入词典（供新版本复用）");
        toDict.setOnAction(ev -> {
            for (LangPack.MissingEntry m : missing) {
                if (!m.trans().isEmpty()) {
                    project.dictionary().add(m.orig(), m.trans());
                }
            }
            try {
                project.dictionary().save();
            } catch (Exception ignored) {
                // 写盘失败不阻断
            }
            Dialogs.info("完成", "已将 " + missing.size() + " 条失效条目存入词典。");
        });
        HBox bottom = new HBox(toDict);
        vbox.getChildren().add(bottom);
        win.setScene(new Scene(vbox));
        theme.attach(win.getScene());
        win.show();
    }

    void openSearch() {
        if (!requireJar()) {
            return;
        }
        new SearchWindow(this).show();
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
        TreeItem<String> node = classNodes.get(cls);
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
    private Object[] currentTool() {
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
        Object[] dec = currentTool();
        if (dec == null) {
            maybePromptSetup();
            then.accept("bytecode", null);
            return;
        }
        if (decompiling) {
            then.accept("loading", null);
            return;
        }
        com.jartrans.core.java.DecompilerType type =
                (com.jartrans.core.java.DecompilerType) dec[0];
        String decPath = (String) dec[1];
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
        decompileCancelled = false;
        setStatus("正在反编译 " + inner + " …");
        Task<List<java.nio.file.Path>> task = new Task<>() {
            @Override
            protected List<java.nio.file.Path> call() throws Exception {
                JavaEnv.JavaResult java = JavaEnv.findJava(settings);
                if (java.path() == null) {
                    throw new DecompilerManager.DecompileException(
                            "未找到可用的 java，请通过菜单「源码 → 反编译管理器」手动指定。");
                }
                setJavaInfo(java.path(), java.version());
                return DecompilerManager.decompileClasses(project.jarPath().toString(), sha,
                        names, java.path(), decPath, type,
                        line -> {
                            if (line != null && !line.isBlank()) {
                                System.out.println("[decomp] " + line);
                            }
                        },
                        () -> decompileCancelled);
            }
        };
        task.setOnSucceeded(ev -> {
            decompiling = false;
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
            String msg = task.getException() == null ? "" : task.getException().getMessage();
            setStatus("反编译失败：" + msg);
            if (!String.valueOf(msg).contains("已取消")) {
                Dialogs.error("反编译失败", String.valueOf(msg));
            }
            then.accept("bytecode", null);
        });
        new Thread(task, "decompile-class").start();
        then.accept("loading", null);
    }

    public void cancelDecompile() {
        decompileCancelled = true;
        DecompilerManager.cancelActive();
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
        if (Dialogs.confirm("提示",
                "查看反编译源码需要：java 运行时 + 反编译器 jar（Vineflower/CFR/Procyon）。\n"
                        + "当前没有检测到 java，也未配置反编译器。\n\n现在打开反编译管理器吗？"
                        + "（关闭后仍可使用字节码视图）")) {
            new DecompilerManagerDialog(this).show();
        }
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

    /** 一条可撤销操作。kind: trans(译文)/skip(不翻译)/mark(类状态)。 */
    private record Hist(String kind, String cls, String orig, String before, String after) {
    }

    private static final String AUTO = "*auto";
    private static final int HIST_LIMIT = 200;
    private final java.util.ArrayDeque<Hist> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Hist> redoStack = new java.util.ArrayDeque<>();

    private void pushHist(Hist h) {
        undoStack.addFirst(h);
        if (undoStack.size() > HIST_LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    /** 记录一次译文变更（EditorPane 保存/清空后按生效值调用）。 */
    void recordTranslation(String cls, String orig, String before, String after) {
        if (before.equals(after)) {
            return;
        }
        pushHist(new Hist("trans", cls, orig, before, after));
    }

    /** 记录「不翻译」切换。 */
    void recordSkip(String orig, boolean before, boolean after) {
        if (before == after) {
            return;
        }
        pushHist(new Hist("skip", null, orig, before ? "1" : "0", after ? "1" : "0"));
    }

    /** 记录类状态手动标记（null 视为自动）。 */
    void recordClassMark(String cls, String beforeManual, String afterManual) {
        String b = beforeManual == null ? AUTO : beforeManual;
        String a = afterManual == null ? AUTO : afterManual;
        if (b.equals(a)) {
            return;
        }
        pushHist(new Hist("mark", cls, null, b, a));
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
        if (undoStack.isEmpty()) {
            setStatus("没有可撤销的操作");
            return;
        }
        Hist h = undoStack.removeFirst();
        applyHist(h, h.before());
        redoStack.addFirst(h);
        setStatus("已撤销：" + histLabel(h.kind()));
    }

    private void redoAction() {
        if (redoStack.isEmpty()) {
            setStatus("没有可重做的操作");
            return;
        }
        Hist h = redoStack.removeFirst();
        applyHist(h, h.after());
        undoStack.addFirst(h);
        setStatus("已重做：" + histLabel(h.kind()));
    }

    private void applyHist(Hist h, String target) {
        try {
            switch (h.kind()) {
                case "trans" -> project.setTranslation(h.cls(), h.orig(), target);
                case "skip" -> project.setTextSkipped(h.orig(), "1".equals(target));
                case "mark" -> project.setClassStatus(h.cls(), AUTO.equals(target) ? null : target);
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

    private void onClose() {
        try {
            settings.set("theme", theme.mode());
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        cancelDecompile();
        DecompilerManager.cleanupOnExit();
        Platform.exit();
    }
}
