package com.jartrans;

import com.jartrans.core.ClassFile;
import com.jartrans.core.LangPack;
import com.jartrans.core.Project;
import com.jartrans.core.jar.JarReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 不翻译名单：导出 jar 保留原文、语言包携带并导入尊重、词典填充跳过。 */
class SkipTextTest {

    @TempDir
    Path tmp;

    private static final String CLS = "demo/Test.class";
    private static final String A = "Hello, 世界!";
    private static final String B = "Press \0 Start";

    @Test
    void skippedTextNotRewrittenOnExport() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        Path jar = TestClasses.writeSampleJar(tmp, "a.jar", false);
        Project p = new Project();
        p.openJar(jar);
        p.setTranslation(CLS, A, "你好世界");
        p.setTranslation(CLS, B, "按开始");
        p.setTextSkipped(B, true);

        Path out = tmp.resolve("out.jar");
        int modified = p.exportJar(out, false);
        assertEquals(1, modified, "只应重写未跳过的译文所在类");

        JarReader jr = new JarReader(out);
        ClassFile cf = ClassFile.parse(jr.readClass(CLS));
        List<String> texts = cf.literals().stream().map(l -> l.text).toList();
        assertTrue(texts.contains("你好世界"), "未跳过的文本应被翻译");
        assertTrue(texts.contains(B), "跳过的文本应保留原文");
        assertFalse(texts.contains("按开始"), "跳过的文本不得写入译文");
    }

    @Test
    void ignoredClassKeepsOriginalOnExport() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        Path jar = TestClasses.writeSampleJar(tmp, "b.jar", false);
        Project p = new Project();
        p.openJar(jar);
        p.setTranslation(CLS, A, "你好世界");
        p.setClassStatus(CLS, "ignore");
        Path out = tmp.resolve("out2.jar");
        int modified = p.exportJar(out, false);
        assertEquals(0, modified, "已忽略类整类不重写");
        JarReader jr = new JarReader(out);
        ClassFile cf = ClassFile.parse(jr.readClass(CLS));
        List<String> texts = cf.literals().stream().map(l -> l.text).toList();
        assertFalse(texts.contains("你好世界"));
    }

    @Test
    void packCarriesAndAppliesSkips() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        Path jar = TestClasses.writeSampleJar(tmp, "c.jar", false);
        Project p = new Project();
        p.openJar(jar);
        p.setTranslation(CLS, A, "你好世界");
        p.setTranslation(CLS, B, "按开始");
        p.setTextSkipped(B, true);
        Path packFile = tmp.resolve("lang.json");
        p.exportPack(packFile, "zh_CN", "tester");

        Map<String, Object> pack = LangPack.readPack(packFile);
        assertTrue(pack.containsKey("skip_texts"), "语言包应携带 skip_texts");
        assertEquals(List.of(B), pack.get("skip_texts"));

        Project p2 = new Project();
        p2.openJar(jar);
        p2.applyLanguagePack(pack);
        assertTrue(p2.isSkipped(B), "导入后应记录不翻译名单");
        Map<String, String> eff = p2.effectiveForExport(CLS);
        assertEquals("你好世界", eff.get(A), "未跳过的应套用");
        assertFalse(eff.containsKey(B), "跳过的不得套用译文");
    }

    @Test
    void dictionaryFillSkipsMarkedTexts() throws Exception {
        System.setProperty("jartrans.dir", tmp.toString());
        Path jar = TestClasses.writeSampleJar(tmp, "d.jar", false);
        Project p = new Project();
        p.openJar(jar);
        p.setTextSkipped(B, true);
        p.dictionary().add(A, "你好世界");
        p.dictionary().add(B, "按开始");
        int count = p.fillFromDictionary();
        assertEquals(1, count, "只应填充未跳过的文本");
        assertFalse(p.effective(CLS).containsKey(B), "跳过的文本不得被词典填充");
    }
}
