package com.jartrans;

import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回归测试：按需只反编译单个类（含内部类），不做整 jar 反编译。 */
class SingleClassDecompileTest {

    @TempDir
    Path tmp;

    private static String sha16(Path jar) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(jar));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    @Test
    void decompilesOnlyRequestedClass() throws Exception {
        Path srcDir = Files.createDirectories(tmp.resolve("src"));
        Files.writeString(srcDir.resolve("Alpha.java"), """
                package p;
                public class Alpha {
                    public static String tag() { return "alpha"; }
                }
                """);
        Files.writeString(srcDir.resolve("Beta.java"), """
                package p;
                public class Beta {
                    public String run() { return Alpha.tag(); }
                    static class Inner { int x = 1; }
                }
                """);
        String home = System.getProperty("java.home");
        Process javac = new ProcessBuilder(Path.of(home, "bin", "javac.exe").toString(),
                "--release", "17", "-d", tmp.resolve("classes").toString(),
                srcDir.resolve("Alpha.java").toString(), srcDir.resolve("Beta.java").toString())
                .redirectErrorStream(true).start();
        javac.waitFor();
        assertTrue(javac.exitValue() == 0, "javac 失败");
        Path jar = tmp.resolve("pair.jar");
        Process jarTool = new ProcessBuilder(Path.of(home, "bin", "jar.exe").toString(), "cf",
                jar.toString(), "-C", tmp.resolve("classes").toString(), ".").start();
        jarTool.waitFor();

        DecompilerManager.ensureBundledAll(); // 三种反编译器都应内置可用
        String sha = sha16(jar);

        long t0 = System.nanoTime();
        List<Path> betaFiles = DecompilerManager.decompileClasses(jar.toString(), sha,
                List.of("p/Beta", "p/Beta$Inner"), DecompilerType.VINEFLOWER);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[single] Beta decompile took " + ms + " ms -> "
                + betaFiles.stream().map(p -> p.getFileName().toString()).toList());
        assertFalse(betaFiles.isEmpty());
        // 产物只应包含 Beta（Alpha 不该被反编译出来）
        boolean hasAlpha = betaFiles.stream()
                .anyMatch(p -> p.getFileName().toString().contains("Alpha"));
        assertFalse(hasAlpha, "不应反编译无关类 Alpha");

        // 同一缓存目录再反编译 Alpha → 增量且两批互不干扰
        List<Path> alphaFiles = DecompilerManager.decompileClasses(jar.toString(), sha,
                List.of("p/Alpha"), DecompilerType.VINEFLOWER);
        System.out.println("[single] Alpha files="
                + alphaFiles.stream().map(p -> p.getFileName().toString()).toList());
        assertFalse(alphaFiles.isEmpty());
        // 无整 jar .ok 标记（不能误导“整 jar 已完成”判断）
        assertFalse(Files.exists(DecompilerManager.cacheDir(sha).resolve(".ok")));

        // CFR 与 Procyon 同样内置可用（临时 jar 输入通吃三种引擎）
        for (DecompilerType engine : List.of(DecompilerType.CFR, DecompilerType.PROCYON)) {
            assertTrue(DecompilerManager.ensureBundled(engine), engine + " 应内置");
            Path tool = DecompilerManager.toolsDir().resolve(engine.key + ".jar");
            assertTrue(Files.isRegularFile(tool), engine + " jar 应存在");
            List<Path> files = DecompilerManager.decompileClasses(jar.toString(), sha,
                    List.of("p/Alpha"), engine);
            System.out.println("[single] " + engine + " files="
                    + files.stream().map(p -> p.getFileName().toString()).toList());
            assertFalse(files.isEmpty(), engine + " 应能反编译单类");
        }
    }
}
