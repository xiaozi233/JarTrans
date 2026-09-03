package com.jartrans;

import com.jartrans.core.Settings;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守护约定：代码里通过 settings.get/getBool/getString/set/setQuiet 访问的
 * 字面量键都必须登记在 Settings.defaults()（动态快捷键键以 key_ 为前缀豁免），
 * 避免新设置键缺失默认值而退化为 getBool 的隐式 false。
 */
class SettingsKeysTest {

    private static final Pattern USAGE = Pattern.compile(
            "(?:settings|app\\.settings\\(\\))\\.(?:set|setQuiet|get|getBool|getString)"
                    + "\\(\"([A-Za-z_][A-Za-z0-9_]*)\"");

    @Test
    void everyLiteralSettingsKeyIsRegisteredInDefaults() throws IOException {
        Set<String> defaults = Settings.defaults().keySet();
        StringBuilder offenders = new StringBuilder();
        try (Stream<Path> walk = Files.walk(Path.of("src/main/java"))) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                Matcher m = USAGE.matcher(src);
                while (m.find()) {
                    String key = m.group(1);
                    if (!defaults.contains(key) && !key.startsWith("key_")) {
                        offenders.append('\n').append(key)
                                .append("  @ ").append(p.getFileName());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "以下 settings 键未在 Settings.defaults() 登记（动态键应以 key_ 开头）："
                        + offenders);
    }
}
