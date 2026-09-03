package com.jartrans.core;

import java.util.List;

/**
 * javap 风格的字节码反汇编文本（降级视图）。
 * 输出格式与 Python 版 gui 源码页签保持一致。
 */
public final class Disassembler {

    private Disassembler() {
    }

    /** ldc 操作数指向的常量的可读文本（仅字符串字面量返回带引号文本）。 */
    private static String cpConstantText(ClassFile cf, int idx) {
        CpEntry entry = cf.entries.get(idx);
        if (entry == null) {
            return null;
        }
        if (entry.tag == ClassFile.CONSTANT_String) {
            int si = ClassFile.u2(entry.raw, 0);
            CpEntry target = cf.entries.get(si);
            if (target != null && target.tag == ClassFile.CONSTANT_Utf8) {
                return pythonRepr(target.text());
            }
            return "<损坏的字符串引用>";
        }
        if (entry.tag == ClassFile.CONSTANT_Class) {
            return "class " + utf8Text(cf, ClassFile.u2(entry.raw, 0));
        }
        return null; // 简化：引用类不展开
    }

    private static String utf8Text(ClassFile cf, int idx) {
        CpEntry entry = cf.entries.get(idx);
        return (entry != null && entry.tag == ClassFile.CONSTANT_Utf8)
                ? entry.text() : "#" + idx;
    }

    /** 模拟 Python repr(str) 的显示效果（带单引号与常用转义）。 */
    static String pythonRepr(String s) {
        StringBuilder sb = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\'' -> sb.append("\\'");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == 0x7F) {
                        sb.append(String.format("\\x%02x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append("'");
        return sb.toString();
    }

    /** javap 风格反汇编文本（降级视图）。 */
    public static String disassemble(ClassFile cf) throws Bytecode.BytecodeException {
        Bytecode.Structure structure = Bytecode.parseStructure(cf);
        StringBuilder lines = new StringBuilder();
        lines.append("类: ").append(structure.className()).append('\n');
        if (!structure.fields().isEmpty()) {
            lines.append("字段:\n");
            for (String[] f : structure.fields()) {
                lines.append("  ").append(f[0]).append("  ").append(f[1]).append('\n');
            }
        }
        lines.append("方法:\n");
        for (Bytecode.MethodInfo method : structure.methods()) {
            lines.append("  ").append(method.name()).append(method.desc()).append('\n');
            byte[] code = method.code();
            if (code == null || code.length == 0) {
                lines.append("    （无 Code 属性）\n");
                continue;
            }
            lines.append("    Code:\n");
            try {
                List<Bytecode.Instruction> instructions = Bytecode.iterInstructions(code);
                for (Bytecode.Instruction ins : instructions) {
                    String extra = "";
                    int idx = Bytecode.ldcIndex(ins);
                    if (idx >= 0) {
                        String text = cpConstantText(cf, idx);
                        if (text != null) {
                            extra = "  // " + text;
                        }
                    }
                    StringBuilder hex = new StringBuilder();
                    for (byte b : ins.operand()) {
                        if (hex.length() > 0) {
                            hex.append(' ');
                        }
                        hex.append(String.format("%02x", b));
                    }
                    String line = String.format("      %5d: %s %s%s",
                            ins.offset(), ins.name(), hex, extra);
                    lines.append(stripTrailing(line)).append('\n');
                }
            } catch (Bytecode.BytecodeException exc) {
                lines.append("    （反汇编中断：").append(exc.getMessage()).append("）\n");
            }
        }
        lines.append('\n');
        lines.append("// 提示：配置反编译器后可查看可读的 Java 源码（源码 → 配置反编译器）");
        return lines.toString();
    }

    private static String stripTrailing(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }
}
