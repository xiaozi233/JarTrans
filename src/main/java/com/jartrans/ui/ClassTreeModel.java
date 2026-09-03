package com.jartrans.ui;

import com.jartrans.core.Project;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TreeItem;

/**
 * 类树的纯模型：按包路径归组构建 TreeItem、按过滤条件筛类、生成节点文案，
 * 并提供节点类型/目标的查询与展开折叠。
 *
 * <p>不依赖 TreeView 控件与 JavaFX toolkit——TreeItem 只是普通 bean，可在无 UI
 * 环境直接构造，便于单元测试（原 MainApp 内联实现的等价搬运）。
 */
public final class ClassTreeModel {

    public static final String TYPE_ROOT = "root";
    public static final String TYPE_DIR = "dir";
    public static final String TYPE_CLASS = "class";

    /** 类列表过滤视图的全部条件（keyword 已小写）。 */
    public record ViewOptions(String keyword, String stateKey, boolean hideEmpty) {
        /** 任一过滤条件生效即为"过滤中"（决定目录节点初始展开与否）。 */
        public boolean isFiltering() {
            return !keyword.isEmpty() || !"all".equals(stateKey) || hideEmpty;
        }
    }

    private final Project project;
    private final Map<String, TreeItem<String>> classNodes = new HashMap<>();
    private final Map<TreeItem<String>, String> nodeKind = new HashMap<>();
    private final Map<TreeItem<String>, String> nodeTarget = new HashMap<>();

    public ClassTreeModel(Project project) {
        this.project = project;
    }

    /** 按视图条件过滤 project 的类（保持 project.classOrder 顺序）。 */
    public List<String> filteredClasses(ViewOptions opts) {
        List<String> out = new ArrayList<>();
        for (String cls : project.classOrder()) {
            if (opts.hideEmpty() && !project.hasTranslatable(cls)) {
                continue;
            }
            if (!"all".equals(opts.stateKey()) && !project.classState(cls).equals(opts.stateKey())) {
                continue;
            }
            if (!opts.keyword().isEmpty() && !cls.toLowerCase().contains(opts.keyword())) {
                continue;
            }
            out.add(cls);
        }
        return out;
    }

    /**
     * 重建目录树并返回内容根（"jar名（n/m 类）"节点）。
     * 过滤中目录默认展开，否则收起；目录/类节点信息写入内部表供后续查询。
     */
    public TreeItem<String> rebuild(String jarName, ViewOptions opts) {
        classNodes.clear();
        nodeKind.clear();
        nodeTarget.clear();
        List<String> visible = filteredClasses(opts);
        TreeItem<String> root = new TreeItem<>(jarName + "（" + visible.size() + "/"
                + project.classOrder().size() + " 类）");
        root.setExpanded(true);
        nodeKind.put(root, TYPE_ROOT);
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
                    d.setExpanded(opts.isFiltering());
                    parent.getChildren().add(d);
                    nodeKind.put(d, TYPE_DIR);
                    nodeTarget.put(d, key);
                    dirs.put(key, d);
                }
                parent = d;
            }
            String st = project.classState(cls);
            TreeItem<String> node = new TreeItem<>(nodeText(cls, st));
            parent.getChildren().add(node);
            nodeKind.put(node, TYPE_CLASS);
            nodeTarget.put(node, cls);
            classNodes.put(cls, node);
        }
        return root;
    }

    /** 类节点文案：完成/忽略/空类用符号特判，其余带未翻译计数。 */
    public String nodeText(String cls, String state) {
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

    /** 就地刷新全部类节点文案（不改变树结构，供状态变化后使用）。 */
    public void refreshTexts() {
        for (Map.Entry<String, TreeItem<String>> e : classNodes.entrySet()) {
            String cls = e.getKey();
            e.getValue().setValue(nodeText(cls, project.classState(cls)));
        }
    }

    /** 节点的类型（root/dir/class；未知返回 null）。 */
    public String kindOf(TreeItem<String> node) {
        return nodeKind.get(node);
    }

    /** 节点目标：class 节点为类名、dir 节点为目录前缀；root 为 null。 */
    public String targetOf(TreeItem<String> node) {
        return nodeTarget.get(node);
    }

    /** 当前可见类 → 树节点。 */
    public TreeItem<String> nodeOf(String cls) {
        return classNodes.get(cls);
    }

    /** 右键菜单的目标类集合：root=全部可见类、dir=该前缀下、class=自身。 */
    public List<String> targetsFor(TreeItem<String> node) {
        String kind = nodeKind.get(node);
        List<String> targets;
        if (kind == null || TYPE_ROOT.equals(kind)) {
            targets = new ArrayList<>(classNodes.keySet());
        } else if (TYPE_DIR.equals(kind)) {
            String prefix = nodeTarget.get(node) + "/";
            targets = new ArrayList<>();
            for (String c : classNodes.keySet()) {
                if (c.startsWith(prefix)) {
                    targets.add(c);
                }
            }
        } else {
            targets = List.of(nodeTarget.get(node));
        }
        return targets;
    }

    /** 展开 node 并一路展开其祖先（用于恢复上次选中类的位置）。 */
    public void expandPathTo(TreeItem<String> node) {
        node.setExpanded(true);
        TreeItem<String> p = node.getParent();
        while (p != null) {
            p.setExpanded(true);
            p = p.getParent();
        }
    }

    /** 递归展开 node 及其全部后代。 */
    public static void expandRecursively(TreeItem<String> node) {
        node.setExpanded(true);
        for (TreeItem<String> child : node.getChildren()) {
            expandRecursively(child);
        }
    }

    /** 递归折叠 node 的全部后代（node 自身保持原样）。 */
    public static void collapseRecursively(TreeItem<String> node) {
        for (TreeItem<String> child : node.getChildren()) {
            collapseRecursively(child);
        }
        node.setExpanded(false);
    }
}
