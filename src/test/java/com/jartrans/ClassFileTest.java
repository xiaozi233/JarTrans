package com.jartrans;

import com.jartrans.core.ClassFile;
import com.jartrans.core.ClassFileException;
import com.jartrans.core.CpEntry;
import com.jartrans.core.Literal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对应 selftest.py 的 test_classfile。 */
class ClassFileTest {

    @Test
    void parseLiteralsAndRewrite() throws Exception {
        byte[] data = TestClasses.buildSampleClass();
        ClassFile cf = ClassFile.parse(data);
        List<Literal> lits = cf.literals();
        assertEquals(2, lits.size());
        assertEquals("Hello, 世界!", lits.get(0).text);
        assertEquals("Press \0 Start", lits.get(1).text);

        byte[] newBytes = cf.rewrite(Map.of(
                "Hello, 世界!", "你好，世界！",
                "Press \0 Start", "Press \0 开始"));
        ClassFile cf2 = ClassFile.parse(newBytes);
        List<Literal> lits2 = cf2.literals();
        assertEquals("你好，世界！", lits2.get(0).text);
        assertEquals("Press \0 开始", lits2.get(1).text);
        // 结构条目原样保留：Class 名索引、Long 数据、尾部字节
        assertEquals(1, ClassFile.u2(cf2.entries.get(2).raw, 0));
        assertArrayEquals(cf.entries.get(6).raw, cf2.entries.get(6).raw);
        assertArrayEquals(cf.tail, cf2.tail);
    }

    @Test
    void reuseExistingUtf8Entry() throws Exception {
        byte[] data = TestClasses.buildSampleClass();
        ClassFile cf = ClassFile.parse(data);
        // 译成已有 Utf8 内容时应复用条目 5（"Code"），不追加
        ClassFile cf3 = ClassFile.parse(cf.rewrite(Map.of("Hello, 世界!", "Code")));
        assertEquals(5, ClassFile.u2(cf3.entries.get(4).raw, 0));
        assertEquals(cf.count, cf3.count);
    }

    @Test
    void noChangeReturnsOriginalBytes() throws Exception {
        byte[] data = TestClasses.buildSampleClass();
        ClassFile cf = ClassFile.parse(data);
        assertArrayEquals(data, cf.rewrite(Map.of()));
        assertArrayEquals(data, cf.rewrite(Map.of("Hello, 世界!", "Hello, 世界!")));
    }

    @Test
    void badFileThrows() {
        assertThrows(ClassFileException.class,
                () -> ClassFile.parse("not a class file at all".getBytes()));
    }

    @Test
    void verifyDetectsProblems() throws Exception {
        byte[] data = TestClasses.buildSampleClass();
        ClassFile cf = ClassFile.parse(data);
        // 直接篡改常量池之后的字节应在校验中暴露
        byte[] tampered = data.clone();
        tampered[tampered.length - 1] ^= 0xFF;
        List<String> problems = cf.verify(tampered, Map.of("Hello, 世界!", "你好"));
        assertTrue(problems.stream().anyMatch(p -> p.contains("常量池之后的字节")));
    }

    @Test
    void twoSlotEntriesOccupyOneKey() throws Exception {
        ClassFile cf = ClassFile.parse(TestClasses.buildSampleClass());
        CpEntry longEntry = cf.entries.get(6);
        assertEquals(ClassFile.CONSTANT_Long, longEntry.tag);
        assertEquals(9, cf.count - 1); // count=10，索引 1..9
    }

    @Test
    void internalTextsExcludeLiteralTexts() throws Exception {
        ClassFile cf = ClassFile.parse(TestClasses.buildSampleClass());
        // 样本字面量: "Hello, 世界!"、"Press \0 Start"；其余 Utf8 为类名/属性名
        List<String> internal = cf.internalTexts(Set.of("Hello, 世界!", "Press \0 Start"));
        assertEquals(List.of("demo/Test", "Code"), internal,
                "内部 Utf8 应按常量池顺序返回且不含字符串字面量");
    }
}
