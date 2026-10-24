package com.jartrans;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jartrans.core.Settings;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 守护约定：
 * 1）代码里仍以字面量访问 settings 的键必须登记在 Settings.defaults()
 *    （动态快捷键以 key_ 前缀豁免）——常量化之后正常不应再出现；
 * 2）Settings.Keys 的每个键常量（前缀常量除外）都必须有默认值，
 *    避免新设置键缺失默认值而退化为 getBool 的隐式 false。
 */
class SettingsKeysTest {

    private static final Pattern USAGE =
            Pattern.compile("(?:settings|app\\.settings\\(\\))\\.(?:set|setQuiet|setOrIgnore|get|getBool|getString)"
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
                        offenders.append('\n').append(key).append("  @ ").append(p.getFileName());
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(), "以下 settings 键以字面量访问且未登记（应改用 Settings.Keys 常量）：" + offenders);
    }

    @Test
    void everyKeysConstantIsRegisteredInDefaults() throws ReflectiveOperationException {
        Set<String> defaults = Settings.defaults().keySet();
        StringBuilder offenders = new StringBuilder();
        for (Field f : Settings.Keys.class.getDeclaredFields()) {
            if (!f.getType().equals(String.class) || f.getName().equals("SHORTCUT_PREFIX")) {
                continue;
            }
            String key = (String) f.get(null);
            if (!defaults.contains(key)) {
                offenders.append('\n').append(f.getName()).append(" = ").append(key);
            }
        }
        assertTrue(offenders.isEmpty(), "Settings.Keys 以下常量未在 defaults() 登记默认值：" + offenders);
    }
}
