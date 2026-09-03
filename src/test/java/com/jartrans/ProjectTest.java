package com.jartrans;

import com.jartrans.core.AppDirs;
import com.jartrans.core.ClassFile;
import com.jartrans.core.Dictionary;
import com.jartrans.core.LangPack;
import com.jartrans.core.Project;
import com.jartrans.core.jar.JarReader;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对应 selftest.py 的 test_project：Project 端到端（语言包/词典/导出）。 */
class ProjectTest {

    @TempDir
    static Path tmp;

    @BeforeAll
    static void isolateRuntimeFiles() {
        // 让 class_status.json / dictionaries 等运行时文件落到测试临时目录
        System.setProperty("jartrans.dir", tmp.resolve("appdir").toString());
    }

    @Test
    void endToEnd() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "sample.jar", false);
        Path dictPath = tmp.resolve("dict.json");

        Project p = new Project(dictPath);
        p.openJar(jarPath);
        assertEquals(List.of("demo/Test.class"), p.classOrder());
        p.setTranslation("demo/Test.class", "Hello, 世界!", "你好，世界！");
        Dictionary dict = p.dictionary();
        dict.add("Press \0 Start", "按下 开始");
        dict.save();

        Path packPath = tmp.resolve("pack.json");
        p.exportPack(packPath, "tester");
        Path zhJar = tmp.resolve("zh.jar");
        assertEquals(1, p.exportJar(zhJar, true));

        // 新会话：从语言包还原（对应"新版本 jar 加载旧语言包"的场景），复用同一词典
        Project p2 = new Project(dictPath);
        p2.openJar(jarPath);
        long total = p2.applyLanguagePack(LangPack.readPack(packPath));
        assertEquals(1, total);
        assertTrue(p2.lastMissing().isEmpty());
        Map<String, String> eff = p2.effective("demo/Test.class");
        assertEquals("你好，世界！", eff.get("Hello, 世界!"));
        assertEquals(Project.Status.AUTO, p2.status("demo/Test.class", "Hello, 世界!"));

        // 词典批量填充
        int filled = p2.fillFromDictionary();
        assertEquals(1, filled);
        assertEquals("按下 开始", p2.effective("demo/Test.class").get("Press \0 Start"));

        // 导出的 jar 确实是汉化后的
        JarReader reader = new JarReader(zhJar);
        ClassFile cf = ClassFile.parse(reader.readClass("demo/Test.class"));
        List<String> texts = cf.literals().stream().map(l -> l.text).toList();
        assertTrue(texts.contains("你好，世界！"));
        assertFalse(texts.contains("Hello, 世界!"));

        // 搜索
        assertTrue(p2.search("世界", "both").stream()
                .anyMatch(r -> r.orig().contains("世界")));

        // 类状态：手动标记并持久化
        p2.setClassStatus("demo/Test.class", "ignore");
        assertEquals("ignore", p2.classState("demo/Test.class"));
        assertTrue(Files.exists(AppDirs.progressFile()));
    }

    @Test
    void packSha256MismatchIsDetectable() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "a.jar", false);
        Project p = new Project(tmp.resolve("d1.json"));
        p.openJar(jarPath);
        Path packPath = tmp.resolve("pack2.json");
        p.exportPack(packPath, "tester");

        Map<String, Object> pack = LangPack.readPack(packPath);
        assertEquals(p.jarSha256(), pack.get("target_jar_sha256"));
        // 换一个内容不同的 jar 后 sha256 必然不同
        Project q = new Project(tmp.resolve("d2.json"));
        q.openJar(TestClasses.writeSampleJar(tmp.resolve("sub"), "a.jar", true));
        assertFalse(p.jarSha256().equals(q.jarSha256()));
    }

    @Test
    void classStateAutoProgression() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "c.jar", false);
        Project p = new Project(tmp.resolve("d3.json"));
        p.openJar(jarPath);
        String cls = "demo/Test.class";
        assertEquals("todo", p.classState(cls));
        p.setTranslation(cls, "Hello, 世界!", "你好");
        assertEquals("doing", p.classState(cls));
        p.setTranslation(cls, "Press \0 Start", "按下");
        assertEquals("done", p.classState(cls));
    }

    @Test
    void statsAndManualStateAccessors() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "d.jar", false);
        Project p = new Project(tmp.resolve("d4.json"));
        p.openJar(jarPath);
        String cls = "demo/Test.class";
        assertTrue(p.hasTranslatable(cls));

        long[] stats = p.stats();
        assertEquals(2, stats[0]); // 样本类两个字符串
        assertEquals(0, stats[1]);
        assertEquals(2, p.untranslatedCount(cls));

        Map<String, Integer> cs = p.classStats();
        assertEquals(1, cs.get("todo"));
        assertEquals(0, cs.get("done"));
        assertTrue(!p.isManualState(cls));

        p.setClassStatus(cls, "ignore");
        assertTrue(p.isManualState(cls));
        assertEquals("ignore", p.classState(cls));
        cs = p.classStats();
        assertEquals(1, cs.get("ignore"));
        assertEquals(0, cs.get("todo"));
        assertTrue(p.dictionaryLabel().endsWith("d4"));
    }
}
