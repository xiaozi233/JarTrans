package com.jartrans;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 回归测试：按需只反编译单个类（含内部类），不做整 jar 反编译。 */
class SingleClassDecompileTest {

    @TempDir
    Path tmp;

    private String previousAppDir;

    @BeforeEach
    void isolateAppDirectory() {
        previousAppDir = System.getProperty("jartrans.dir");
        System.setProperty("jartrans.dir", tmp.toString());
    }

    @AfterEach
    void restoreAppDirectory() {
        if (previousAppDir == null) {
            System.clearProperty("jartrans.dir");
        } else {
            System.setProperty("jartrans.dir", previousAppDir);
        }
    }

    private static String sha16(Path jar) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(jar));
        return HexFormat.of().formatHex(digest).substring(0, 16);
    }

    /** 现场编译 p/Alpha 与 p/Beta（含内部类）并打成 pair.jar。 */
    private Path compilePairJar() throws Exception {
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
        Process javac = new ProcessBuilder(
                        Path.of(home, "bin", "javac.exe").toString(),
                        "--release",
                        "17",
                        "-d",
                        tmp.resolve("classes").toString(),
                        srcDir.resolve("Alpha.java").toString(),
                        srcDir.resolve("Beta.java").toString())
                .redirectErrorStream(true)
                .start();
        javac.waitFor();
        assertTrue(javac.exitValue() == 0, "javac 失败");
        Path jar = tmp.resolve("pair.jar");
        Process jarTool = new ProcessBuilder(
                        Path.of(home, "bin", "jar.exe").toString(),
                        "cf",
                        jar.toString(),
                        "-C",
                        tmp.resolve("classes").toString(),
                        ".")
                .start();
        jarTool.waitFor();
        return jar;
    }

    @Test
    void decompilesOnlyRequestedClass() throws Exception {
        Path jar = compilePairJar();
        String sha = sha16(jar);

        long t0 = System.nanoTime();
        List<Path> betaFiles = DecompilerManager.decompileClasses(
                jar.toString(), sha, List.of("p/Beta", "p/Beta$Inner"), DecompilerType.VINEFLOWER);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[single] Beta decompile took " + ms + " ms -> "
                + betaFiles.stream().map(p -> p.getFileName().toString()).toList());
        assertFalse(betaFiles.isEmpty());
        assertTrue(Files.readString(betaFiles.getFirst()).contains("Inner"));
        // 产物只应包含 Beta（Alpha 不该被反编译出来）
        boolean hasAlpha =
                betaFiles.stream().anyMatch(p -> p.getFileName().toString().contains("Alpha"));
        assertFalse(hasAlpha, "不应反编译无关类 Alpha");

        // 同一缓存目录再反编译 Alpha → 增量且两批互不干扰
        List<Path> alphaFiles =
                DecompilerManager.decompileClasses(jar.toString(), sha, List.of("p/Alpha"), DecompilerType.VINEFLOWER);
        System.out.println("[single] Alpha files="
                + alphaFiles.stream().map(p -> p.getFileName().toString()).toList());
        assertFalse(alphaFiles.isEmpty());
        assertTrue(Files.readString(alphaFiles.getFirst()).contains("\"alpha\""));
        // 无整 jar .ok 标记（不能误导“整 jar 已完成”判断）
        assertFalse(Files.exists(DecompilerManager.cacheDir(sha).resolve(".ok")));

        // CFR 与 Procyon 同样通过库 API 读取原始类字节，缓存按引擎隔离。
        for (DecompilerType engine : List.of(DecompilerType.CFR, DecompilerType.PROCYON)) {
            List<Path> files = DecompilerManager.decompileClasses(jar.toString(), sha, List.of("p/Alpha"), engine);
            System.out.println("[single] " + engine + " files="
                    + files.stream().map(p -> p.getFileName().toString()).toList());
            assertFalse(files.isEmpty(), engine + " 应能反编译单类");
            assertTrue(Files.readString(files.getFirst()).contains("\"alpha\""));
            assertTrue(files.getFirst().startsWith(DecompilerManager.outputDir(sha, engine)));
            List<Path> beta =
                    DecompilerManager.decompileClasses(jar.toString(), sha, List.of("p/Beta", "p/Beta$Inner"), engine);
            assertTrue(beta.stream().anyMatch(p -> p.getFileName().toString().equals("Beta.java")));
            assertTrue(
                    Files.readString(beta.stream()
                                    .filter(p -> p.getFileName().toString().equals("Beta.java"))
                                    .findFirst()
                                    .orElseThrow())
                            .contains("Inner"),
                    engine + " 应保留内部类");
        }
        assertFalse(Files.exists(tmp.resolve("tools")), "反编译不得释放本地工具");
        assertFalse(Files.exists(tmp.resolve("p")), "源码只能写入缓存目录");
    }

    @Test
    void rejectsMissingClassesAndUnsafeNames() throws Exception {
        Path jar = compilePairJar();
        String sha = sha16(jar);
        for (DecompilerType engine : DecompilerType.values()) {
            assertThrows(
                    DecompilerManager.DecompileException.class,
                    () -> DecompilerManager.decompileClasses(jar.toString(), sha, List.of("p/Absent"), engine));
            assertThrows(
                    DecompilerManager.DecompileException.class,
                    () -> DecompilerManager.decompileClasses(jar.toString(), sha, List.of("../Escape"), engine));
        }
    }

    /**
     * 回归：同一 jar 两个线程并发反编译（模拟「取消后旧任务仍在后台收尾、又立刻反编译」）。
     * 曾因无互斥并发写同一 classes 目录产生半截结果包/删除占用（ZipException/FileSystemException）。
     * 现在按 sha 串行，两个任务都应成功且缓存目录不残留 *.jar。
     */
    @Test
    void concurrentSameShaDecompilesDoNotCorruptCache() throws Exception {
        Path jar = compilePairJar();
        String sha = sha16(jar);
        Path out = DecompilerManager.outputDir(sha, DecompilerType.VINEFLOWER);

        CountDownLatch gate = new CountDownLatch(1);
        List<Path> alphaResult = new ArrayList<>();
        List<Path> betaResult = new ArrayList<>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread a = new Thread(
                () -> {
                    try {
                        gate.await();
                        alphaResult.addAll(DecompilerManager.decompileClasses(
                                jar.toString(), sha, List.of("p/Alpha"), DecompilerType.VINEFLOWER));
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                },
                "conc-alpha");
        Thread b = new Thread(
                () -> {
                    try {
                        gate.await();
                        betaResult.addAll(DecompilerManager.decompileClasses(
                                jar.toString(), sha, List.of("p/Beta", "p/Beta$Inner"), DecompilerType.VINEFLOWER));
                    } catch (Throwable t) {
                        failures.add(t);
                    }
                },
                "conc-beta");
        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();
        gate.countDown();
        a.join(90_000);
        b.join(90_000);
        assertFalse(a.isAlive() || b.isAlive(), "并发反编译未在时限内结束");
        assertTrue(failures.isEmpty(), "并发反编译失败：" + failures);
        assertFalse(alphaResult.isEmpty(), "Alpha 产物缺失");
        assertFalse(betaResult.isEmpty(), "Beta 产物缺失");
        assertTrue(betaResult.stream().anyMatch(p -> p.getFileName().toString().contains("Beta")), "Beta 产物应含目标类");

        // 库 API 不生成中间 JAR。
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(out, "*.jar")) {
            assertFalse(ds.iterator().hasNext(), "classes/ 下不应残留未解包的 *.jar");
        }
    }
}
