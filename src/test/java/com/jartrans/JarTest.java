package com.jartrans;

import com.jartrans.core.ClassFile;
import com.jartrans.core.Literal;
import com.jartrans.core.jar.JarPacker;
import com.jartrans.core.jar.JarReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对应 selftest.py 的 test_jar。 */
class JarTest {

    @TempDir
    Path tmp;

    @Test
    void readReplaceRepack() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "sample.jar", true);

        JarReader jf = new JarReader(jarPath);
        assertEquals(List.of("demo/Test.class"), jf.classNames());

        ClassFile cf = ClassFile.parse(jf.readClass("demo/Test.class"));
        Path out = tmp.resolve("out.jar");
        JarPacker.save(jf, out,
                Map.of("demo/Test.class", cf.rewrite(Map.of("Hello, 世界!", "你好"))),
                true);

        JarReader jf2 = new JarReader(out);
        List<String> names = new ArrayList<>();
        jf2.infos().forEach(i -> names.add(i.name));
        assertTrue(names.contains("META-INF/MANIFEST.MF"));
        assertFalse(names.contains("META-INF/sample.sf"));
        ClassFile cf2 = ClassFile.parse(jf2.readClass("demo/Test.class"));
        assertEquals("你好", cf2.literals().get(0).text);
        assertEquals("hello", new String(jf2.readEntry("assets/lang.txt"))); // 资源原样保留
    }

    @Test
    void keepSignatureWhenNotStripped() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "sample.jar", true);
        JarReader jf = new JarReader(jarPath);
        Path out = tmp.resolve("signed.jar");
        JarPacker.save(jf, out, Map.of(), false);
        JarReader jf2 = new JarReader(out);
        assertTrue(jf2.infos().stream().anyMatch(i -> i.name.equals("META-INF/sample.sf")));
    }

    @Test
    void isZipFileDetection() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "a.jar", false);
        assertTrue(JarReader.isZipFile(jarPath));
        Path txt = tmp.resolve("b.txt");
        Files.writeString(txt, "plain text");
        assertFalse(JarReader.isZipFile(txt));
    }

    @Test
    void literalOrderPreserved() throws Exception {
        Path jarPath = TestClasses.writeSampleJar(tmp, "a.jar", false);
        JarReader jf = new JarReader(jarPath);
        ClassFile cf = ClassFile.parse(jf.readClass("demo/Test.class"));
        List<String> texts = new ArrayList<>();
        for (Literal l : cf.literals()) {
            texts.add(l.text);
        }
        assertEquals(List.of("Hello, 世界!", "Press \0 Start"), texts);
    }
}
