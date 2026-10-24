package com.jartrans.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 多词典管理：CRUD/激活切换/导入导出与损坏场景（此前零直接覆盖）。 */
class DictionaryManagerTest {

    @TempDir
    Path tmp;

    private DictionaryManager newManager(String subdir) throws Exception {
        return new DictionaryManager(tmp.resolve(subdir));
    }

    @Test
    void createsDefaultDictionaryOnFirstRun() throws Exception {
        DictionaryManager mgr = newManager("a");
        assertEquals(1, mgr.names().size());
        assertEquals(DictionaryManager.DEFAULT_NAME, mgr.activeName());
        assertTrue(Files.exists(mgr.dir().resolve("index.json")), "应生成索引文件");
    }

    @Test
    void createRenameRemoveLifecycle() throws Exception {
        DictionaryManager mgr = newManager("b");
        mgr.create("工作词典");
        assertEquals(2, mgr.names().size());
        assertTrue(mgr.exists("工作词典"));

        mgr.rename("工作词典", "归档词典");
        assertFalse(mgr.exists("工作词典"));
        assertTrue(mgr.exists("归档词典"));
        // rename 只改显示名，底层文件保持原名（索引仍指向同一 JSON）
        assertEquals("工作词典.json", mgr.pathOf("归档词典").getFileName().toString(), "重命名保留原文件");

        mgr.remove("归档词典");
        assertFalse(mgr.exists("归档词典"));
        assertThrows(DictionaryException.class, () -> mgr.remove(mgr.names().get(0)), "最后一个词典不可删除");
    }

    @Test
    void duplicateAndEmptyNamesRejected() throws Exception {
        DictionaryManager mgr = newManager("c");
        assertThrows(DictionaryException.class, () -> mgr.create(""));
        assertThrows(DictionaryException.class, () -> mgr.create(" "));
        mgr.create("唯一");
        assertThrows(DictionaryException.class, () -> mgr.create("唯一"));
        assertThrows(DictionaryException.class, () -> mgr.rename("不存在", "任意"));
    }

    @Test
    void setActivePersistsAcrossInstances() throws Exception {
        Path base = tmp.resolve("d");
        DictionaryManager mgr = new DictionaryManager(base);
        mgr.create("第二词典");
        mgr.setActive("第二词典");
        assertEquals("第二词典", mgr.activeName());

        // 新实例从 index.json 恢复激活状态
        DictionaryManager reloaded = new DictionaryManager(base);
        assertEquals("第二词典", reloaded.activeName());
    }

    @Test
    void importMergesIntoExistingDictionary() throws Exception {
        DictionaryManager mgr = newManager("e");
        Path src = tmp.resolve("import.json");
        Files.writeString(src, "{\"Hello\":\"你好\",\"World\":\"世界\"}");

        DictionaryManager.ImportResult merged = mgr.importFile(src, null, false);
        assertFalse(merged.merged());
        assertEquals(2, mgr.count(merged.name()));

        Files.writeString(src, "{\"Hello\":\"你好改\"}");
        DictionaryManager.ImportResult again = mgr.importFile(src, merged.name(), true);
        assertTrue(again.merged(), "同名词典应合并而非新建");
        assertEquals("你好改", mgr.get(merged.name()).get("Hello"));

        Path bad = tmp.resolve("bad.json");
        Files.writeString(bad, "[1, 2]");
        assertThrows(DictionaryException.class, () -> mgr.importFile(bad, null, false));
    }

    @Test
    void exportWritesSortedEntries() throws Exception {
        DictionaryManager mgr = newManager("f");
        Dictionary dic = mgr.activeDictionary();
        dic.add("z", "1");
        dic.add("a", "2");
        dic.saveQuietly();
        Path dest = tmp.resolve("export.json");
        int count = mgr.exportFile(mgr.activeName(), dest);

        assertEquals(2, count);
        var parsed = com.jartrans.core.json.Json.object(com.jartrans.core.json.Json.readFile(dest));
        assertEquals(java.util.List.of("a", "z"), java.util.List.copyOf(parsed.keySet()), "导出按码点排序");
    }

    @Test
    void corruptDictionaryIsBackedUpAsEmpty() throws Exception {
        DictionaryManager mgr = newManager("g");
        Path dictFile = mgr.pathOf(mgr.activeName());
        Dictionary clean = mgr.activeDictionary();
        clean.add("k", "v");
        clean.saveQuietly();

        // 损坏词典文件后重新加载：备份保留，按空词典继续，不再静默丢数据
        Files.writeString(dictFile, "{ broken !!");
        Dictionary reloaded = new Dictionary(dictFile);
        assertEquals(0, reloaded.size());
        assertTrue(Files.exists(dictFile.resolveSibling(dictFile.getFileName() + ".corrupt")));
    }

    @Test
    void getFallsBackToActiveForUnknownName() throws Exception {
        DictionaryManager mgr = newManager("h");
        Dictionary dic = mgr.get("不存在的名字");
        assertEquals(mgr.activeDictionary(), dic, "未知名称回退到当前激活词典");
        assertNull(mgr.pathOf("不存在的名字"));
    }

    @Test
    void startupDoesNotRewriteUnchangedIndex() throws Exception {
        Path base = tmp.resolve("i");
        newManager(base.toString()).create("另一个");
        Path index = base.resolve("dictionaries").resolve("index.json");
        long stamp = Files.getLastModifiedTime(index).toMillis();

        // 未变更内容时重新加载不得重写 index.json
        new DictionaryManager(base);
        assertEquals(stamp, Files.getLastModifiedTime(index).toMillis(), "内容未变时不应重写 index.json");
    }
}
