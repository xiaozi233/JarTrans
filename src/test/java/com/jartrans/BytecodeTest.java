package com.jartrans;

import com.jartrans.core.Bytecode;
import com.jartrans.core.ClassFile;
import com.jartrans.core.Disassembler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 对应 selftest.py 的 test_bytecode。 */
class BytecodeTest {

    @Test
    void stringUsageMapsLdcToMethods() throws Exception {
        ClassFile cf = ClassFile.parse(TestClasses.buildBytecodeClass());
        Map<String, List<String>> usage = Bytecode.stringUsage(cf);
        assertEquals(1, usage.size());
        assertEquals(List.of("foo"), usage.get("Hello, 世界!"));
    }

    @Test
    void disassembleContainsStructure() throws Exception {
        ClassFile cf = ClassFile.parse(TestClasses.buildBytecodeClass());
        String text = Disassembler.disassemble(cf);
        assertTrue(text.contains("demo/Test"), text);
        assertTrue(text.contains("foo"), text);
        assertTrue(text.contains("bar"), text);
        assertTrue(text.contains("ldc"), text);
        assertTrue(text.contains("tableswitch"), text);
    }

    @Test
    void tableswitchInstructionSpan() throws Exception {
        ClassFile cf = ClassFile.parse(TestClasses.buildBytecodeClass());
        byte[] barCode = null;
        for (Bytecode.MethodInfo m : Bytecode.parseStructure(cf).methods()) {
            if (m.code() != null && m.code().length > 0 && (m.code()[0] & 0xFF) == 0xaa) {
                barCode = m.code();
            }
        }
        List<Bytecode.Instruction> steps = Bytecode.iterInstructions(barCode);
        assertEquals(2, steps.size());
        assertEquals("tableswitch", steps.get(0).name());
    }

    @Test
    void unknownOpcodeThrows() {
        assertThrows(Bytecode.BytecodeException.class,
                () -> Bytecode.iterInstructions(new byte[]{(byte) 0xFF, (byte) 0xFF}));
    }
}
