package com.jartrans.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Project.replaceTranslations 的统一替换规则：
 * 跳过「不翻译」与译文已等于目标的条目，返回可撤销的变更明细。
 */
class ReplaceTranslationsTest {

    @TempDir
    Path tmp;

    private Project newProject() {
        System.setProperty("jartrans.dir", tmp.toString());
        return new Project(tmp.resolve("dict-" + System.nanoTime() + ".json"));
    }

    @Test
    void skipsSameTranslationAndSkippedTexts() throws Exception {
        Project p = newProject();
        p.setTranslation("a/A.class", "already-target", "新译文");
        p.setTranslation("a/A.class", "skipped-one", "旧译文");
        p.setTextSkipped("skipped-one", true);

        List<Project.ReplaceChange> changes = p.replaceTranslations(
                List.of(
                        new Project.ReplaceTarget("a/A.class", "already-target"),
                        new Project.ReplaceTarget("a/A.class", "skipped-one"),
                        new Project.ReplaceTarget("a/A.class", "fresh")),
                "新译文");

        assertEquals(1, changes.size(), "只有未跳过且译文不同的条目被替换");
        assertEquals("fresh", changes.get(0).orig());
        assertEquals("", changes.get(0).before());
        assertEquals("新译文", p.effective("a/A.class").get("fresh"));
        assertEquals("新译文", p.effective("a/A.class").get("already-target"), "译文相同不重写");
        assertEquals("旧译文", p.effective("a/A.class").get("skipped-one"), "「不翻译」条目不被替换");
    }

    @Test
    void batchAcrossClassesKeepsChangeOrder() {
        Project p = newProject();
        p.setTranslation("b/B.class", "k", "before-b");
        List<Project.ReplaceChange> changes = p.replaceTranslations(
                List.of(new Project.ReplaceTarget("a/A.class", "x"), new Project.ReplaceTarget("b/B.class", "k")), "R");

        assertEquals(2, changes.size());
        assertEquals("before-b", changes.get(1).before(), "变更携带替换前原值（撤销用）");
        assertEquals("R", p.effective("b/B.class").get("k"));
        assertTrue(p.effective("a/A.class").containsKey("x"));
    }
}
