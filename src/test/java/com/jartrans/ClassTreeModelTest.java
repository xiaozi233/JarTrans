package com.jartrans;

import com.jartrans.core.Project;
import com.jartrans.ui.ClassTreeModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javafx.scene.control.TreeItem;

/** ClassTreeModel 纯逻辑测试（TreeItem 为普通 bean，无需 JavaFX toolkit）。 */
class ClassTreeModelTest {

    @TempDir
    Path tmp; // 实例级：每个测试方法独立目录，class_status.json 互不串扰

    @BeforeEach
    void isolateRuntimeFiles() {
        // 防 setClassStatus 等把 class_status.json 写进真实工作区
        System.setProperty("jartrans.dir", tmp.resolve("appdir").toString());
    }

    private Project openSample() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "sample.jar", false);
        Project p = new Project(tmp.resolve("dict.json"));
        p.openJar(jarPath);
        return p;
    }

    private static final ClassTreeModel.ViewOptions ALL =
            new ClassTreeModel.ViewOptions("", "all", false);

    @Test
    void buildsDirTreeWithKindAndTarget() throws Exception {
        Project p = openSample();
        ClassTreeModel m = new ClassTreeModel(p);
        assertEquals(List.of("demo/Test.class"), m.filteredClasses(ALL));

        TreeItem<String> content = m.rebuild(p.jarName(), ALL);
        assertEquals(ClassTreeModel.TYPE_ROOT, m.kindOf(content));
        assertTrue(content.getValue().contains("（1/1 类）"), "根含计数: " + content.getValue());

        TreeItem<String> dir = content.getChildren().get(0);
        assertEquals("demo", dir.getValue());
        assertEquals(ClassTreeModel.TYPE_DIR, m.kindOf(dir));
        assertEquals("demo", m.targetOf(dir));

        TreeItem<String> node = dir.getChildren().get(0);
        assertEquals(ClassTreeModel.TYPE_CLASS, m.kindOf(node));
        assertEquals("demo/Test.class", m.targetOf(node));
        assertEquals(node, m.nodeOf("demo/Test.class"));
        assertTrue(node.getValue().startsWith("Test.class  ("), "todo 计数文案: " + node.getValue());
    }

    @Test
    void nodeTextReflectsStateTransitions() throws Exception {
        Project p = openSample();
        ClassTreeModel m = new ClassTreeModel(p);
        m.rebuild(p.jarName(), ALL);

        // 全部译完 → 自动 done → ✓
        p.setTranslation("demo/Test.class", "Hello, 世界!", "你好");
        p.setTranslation("demo/Test.class", "Press \0 Start", "按下");
        m.refreshTexts();
        assertEquals("Test.class  ✓", m.nodeOf("demo/Test.class").getValue());

        // 手动 ignore → —
        p.setClassStatus("demo/Test.class", "ignore");
        m.refreshTexts();
        assertEquals("Test.class  —", m.nodeOf("demo/Test.class").getValue());
    }

    @Test
    void filtersByKeywordAndState() throws Exception {
        Project p = openSample();
        ClassTreeModel m = new ClassTreeModel(p);

        assertEquals(1, m.filteredClasses(ALL).size());
        assertEquals(1, m.filteredClasses(
                new ClassTreeModel.ViewOptions("demo", "all", false)).size());
        assertEquals(0, m.filteredClasses(
                new ClassTreeModel.ViewOptions("nomatch", "all", false)).size());
        // 初始两字符串未译 → todo；手动 ignore 后 ignore 过滤命中、todo 清空
        assertEquals(1, m.filteredClasses(
                new ClassTreeModel.ViewOptions("", "todo", false)).size());
        p.setClassStatus("demo/Test.class", "ignore");
        assertEquals(1, m.filteredClasses(
                new ClassTreeModel.ViewOptions("", "ignore", false)).size());
        assertEquals(0, m.filteredClasses(
                new ClassTreeModel.ViewOptions("", "todo", false)).size());
    }

    @Test
    void targetsAndExpansionSemantics() throws Exception {
        Project p = openSample();
        ClassTreeModel m = new ClassTreeModel(p);
        TreeItem<String> content = m.rebuild(p.jarName(), ALL);
        TreeItem<String> dir = content.getChildren().get(0);
        TreeItem<String> node = dir.getChildren().get(0);

        // root → 全部可见类；dir → 前缀下；class → 自身
        assertEquals(List.of("demo/Test.class"), m.targetsFor(content));
        assertEquals(List.of("demo/Test.class"), m.targetsFor(dir));
        assertEquals(List.of("demo/Test.class"), m.targetsFor(node));

        // 默认（不过滤）目录收起；expandPathTo 展开节点及全部祖先
        assertTrue(!dir.isExpanded(), "默认目录应收起");
        m.expandPathTo(node);
        assertTrue(node.isExpanded() && dir.isExpanded() && content.isExpanded());

        // 过滤中重建的目录默认展开
        TreeItem<String> filtered = m.rebuild(p.jarName(),
                new ClassTreeModel.ViewOptions("demo", "all", false));
        TreeItem<String> fdir = filtered.getChildren().get(0);
        assertTrue(fdir.isExpanded(), "过滤中目录应默认展开");
    }
}
