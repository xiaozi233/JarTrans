package com.jartrans;

import com.jartrans.core.Settings;
import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 反编译器类型识别与单类产物定位（纯逻辑，不触文件系统/反编译器）。 */
class DecompilerTypeTest {

    @Test
    void fromFileNameIdentifiesEngines() {
        assertEquals(DecompilerType.VINEFLOWER, DecompilerType.fromFileName("vineflower-1.9.1.jar"));
        assertEquals(DecompilerType.CFR, DecompilerType.fromFileName("cfr-0.152.jar"));
        assertEquals(DecompilerType.PROCYON, DecompilerType.fromFileName("procyon-0.6.0.jar"));
        // 文件名含关键词即可识别（scanTools 用全小写文件名探测）
        assertEquals(DecompilerType.VINEFLOWER, DecompilerType.fromFileName("myvineflowercustom.jar"));
        assertNull(DecompilerType.fromFileName("unknown-tool.jar"));
    }

    @Test
    void fromKeyMapsSettingsKeys() {
        assertEquals(DecompilerType.VINEFLOWER, DecompilerType.fromKey("vineflower"));
        assertEquals(DecompilerType.CFR, DecompilerType.fromKey("cfr"));
        assertEquals(DecompilerType.PROCYON, DecompilerType.fromKey("procyon"));
        assertNull(DecompilerType.fromKey(""));
        assertNull(DecompilerType.fromKey(null));
        assertNull(DecompilerType.fromKey("bogus"));
    }

    @Test
    void enumKeysAreUniqueAndFieldsConsistent() {
        assertTrue(DecompilerType.values().length >= 3, "至少三种内置引擎");
        for (DecompilerType t : DecompilerType.values()) {
            assertEquals(t.key, t.assetPrefix);
            assertTrue(!t.key.isEmpty());
            assertTrue(!t.repo.isEmpty());
            assertTrue(t.displayName().length() > 0);
        }
        long distinct = java.util.Arrays.stream(DecompilerType.values())
                .map(t -> t.key).distinct().count();
        assertEquals(DecompilerType.values().length, distinct, "引擎 key 不得重复");
    }

    @Test
    void sourceFilesForLocatesExternalAndInnerClasses() {
        Map<String, Path> index = new LinkedHashMap<>();
        index.put("pkg/Outer.java", Path.of("classes/pkg/Outer.java"));
        index.put("pkg/Outer$Inner.java", Path.of("classes/pkg/Outer$Inner.java"));
        index.put("pkg/Other.java", Path.of("classes/pkg/Other.java"));
        index.put("pkg/Outerish.java", Path.of("classes/pkg/Outerish.java"));

        DecompilerManager.SourceFiles sf = DecompilerManager.sourceFilesFor(index, "pkg/Outer.class");
        assertEquals("pkg/Outer.java", sf.external());
        assertEquals(java.util.List.of("pkg/Outer$Inner.java"), sf.inners());

        // 没有外部类（只有内部类被反编译）时 external 为 null
        Map<String, Path> onlyInner = new LinkedHashMap<>();
        onlyInner.put("pkg/Only$1.java", Path.of("c/pkg/Only$1.java"));
        DecompilerManager.SourceFiles sf2 = DecompilerManager.sourceFilesFor(onlyInner, "pkg/Only.class");
        assertNull(sf2.external());
        assertEquals(List.of("pkg/Only$1.java"), sf2.inners());
    }

    @Test
    void buildFileIndexWalksJavaFiles(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes/pkg"));
        Files.writeString(classes.resolve("Outer.java"), "class Outer {}");
        Files.writeString(classes.resolve("Outer$Inner.java"), "class Inner {}");
        Files.writeString(classes.resolve("Note.txt"), "not java"); // 非 .java 忽略
        Files.writeString(Files.createDirectories(tmp.resolve("classes/deep/nested"))
                .resolve("X.java"), "class X {}");

        Map<String, Path> index = DecompilerManager.buildFileIndex(tmp.resolve("classes"));
        assertEquals(List.of("deep/nested/X.java", "pkg/Outer$Inner.java", "pkg/Outer.java"),
                new ArrayList<>(index.keySet()), "键为相对路径(正斜杠)且按路径升序");
        assertEquals(classes.resolve("Outer.java").toAbsolutePath(),
                index.get("pkg/Outer.java").toAbsolutePath());
        assertTrue(index.get("pkg/Outer.java").isAbsolute(), "值为绝对路径");

        // 目录不存在 / null 一律返回空索引（调用方按无缓存处理）
        assertTrue(DecompilerManager.buildFileIndex(tmp.resolve("nope")).isEmpty());
        assertTrue(DecompilerManager.buildFileIndex(null).isEmpty());
    }

    @Test
    void currentDecompilerRespectsExplicitChoice() {
        DecompilerManager.ensureBundledAll(); // 保证 tools/ 三种引擎都在（setQuiet 不落盘）
        Settings settings = new Settings();

        settings.setQuiet("decompiler_type", "cfr");
        DecompilerManager.Selection sel = DecompilerManager.currentDecompiler(settings);
        assertNotNull(sel, "tools/ 有内置引擎时应总能选到");
        assertEquals(DecompilerType.CFR, sel.type(), "首选项显式选择的引擎应优先");

        settings.setQuiet("decompiler_type", "");
        assertEquals(DecompilerType.VINEFLOWER,
                DecompilerManager.currentDecompiler(settings).type(), "未选择时回退默认优先级");
    }
}
