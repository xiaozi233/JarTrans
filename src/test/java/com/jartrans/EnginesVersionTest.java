package com.jartrans;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.jartrans.core.java.DecompilerManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 守护：DecompilerManager.engines() 展示的引擎版本必须与
 * gradle/libs.versions.toml（依赖版本唯一来源）一致，防止升级依赖后展示假版本号。
 */
class EnginesVersionTest {

    @Test
    void displayedEngineVersionsMatchVersionCatalog() throws IOException {
        Path catalog = Path.of("gradle", "libs.versions.toml");
        assumeTrue(Files.exists(catalog), "非源码树运行（无 version catalog）时跳过");

        Map<String, String> versions = parseVersionsSection(catalog);
        for (DecompilerManager.EngineInfo info : DecompilerManager.engines()) {
            String expected = versions.get(info.type().key);
            assertNotNull(expected, "catalog [versions] 缺少 " + info.type().key);
            assertEquals(expected, info.version(), info.type().displayName() + " 展示版本与 version catalog 不一致");
        }
    }

    /** 只解析 [versions] 段的 name = "value" 行。 */
    private static Map<String, String> parseVersionsSection(Path catalog) throws IOException {
        Map<String, String> out = new HashMap<>();
        boolean inVersions = false;
        for (String line : Files.readAllLines(catalog)) {
            String t = line.trim();
            if (t.startsWith("[")) {
                inVersions = t.equals("[versions]");
            } else if (inVersions) {
                int eq = t.indexOf('=');
                if (eq > 0) {
                    out.put(
                            t.substring(0, eq).trim(),
                            t.substring(eq + 1).trim().replace("\"", ""));
                }
            }
        }
        return out;
    }
}
