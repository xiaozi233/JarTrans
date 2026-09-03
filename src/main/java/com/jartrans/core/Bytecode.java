package com.jartrans.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * class 文件结构解析扩展（只读）：方法表 / Code 属性 / ldc 引用。
 *
 * 直接解析 ClassFile.tail（常量池之后的字节），不修改任何现有解析与写回逻辑。
 * 两个用途：
 * 1. {@link #stringUsage(ClassFile)}：统计每个字符串字面量被哪些方法的 ldc/ldc_w 引用；
 * 2. 配合 {@link Disassembler} 生成 javap 风格的字节码反汇编文本（降级视图）。
 */
public final class Bytecode {

    private Bytecode() {
    }

    /** 字节码解析错误。 */
    public static class BytecodeException extends Exception {

        public BytecodeException(String message) {
            super(message);
        }
    }

    /** 一条指令：偏移、操作码、助记符、操作数原始字节。 */
    public record Instruction(int offset, int opcode, String name, byte[] operand) {
    }

    /** 方法信息：名称、描述符、Code 属性中的字节码（可能为 null）。 */
    public record MethodInfo(String name, String desc, byte[] code) {
    }

    /** 类结构：类名 + 字段 + 方法。 */
    public record Structure(String className, List<String[]> fields, List<MethodInfo> methods) {
    }

    // ---------- 操作码表 ----------

    private static final Map<Integer, String> OPCODES = new LinkedHashMap<>();

    static {
        Object[][] table = {
                {0x00, "nop"}, {0x01, "aconst_null"}, {0x02, "iconst_m1"}, {0x03, "iconst_0"},
                {0x04, "iconst_1"}, {0x05, "iconst_2"}, {0x06, "iconst_3"}, {0x07, "iconst_4"},
                {0x08, "iconst_5"}, {0x09, "lconst_0"}, {0x0a, "lconst_1"}, {0x0b, "fconst_0"},
                {0x0c, "fconst_1"}, {0x0d, "fconst_2"}, {0x0e, "dconst_0"}, {0x0f, "dconst_1"},
                {0x10, "bipush"}, {0x11, "sipush"}, {0x12, "ldc"}, {0x13, "ldc_w"}, {0x14, "ldc2_w"},
                {0x15, "iload"}, {0x16, "lload"}, {0x17, "fload"}, {0x18, "dload"}, {0x19, "aload"},
                {0x1a, "iload_0"}, {0x1b, "iload_1"}, {0x1c, "iload_2"}, {0x1d, "iload_3"},
                {0x1e, "lload_0"}, {0x1f, "lload_1"}, {0x20, "lload_2"}, {0x21, "lload_3"},
                {0x22, "fload_0"}, {0x23, "fload_1"}, {0x24, "fload_2"}, {0x25, "fload_3"},
                {0x26, "dload_0"}, {0x27, "dload_1"}, {0x28, "dload_2"}, {0x29, "dload_3"},
                {0x2a, "aload_0"}, {0x2b, "aload_1"}, {0x2c, "aload_2"}, {0x2d, "aload_3"},
                {0x2e, "iaload"}, {0x2f, "laload"}, {0x30, "faload"}, {0x31, "daload"},
                {0x32, "aaload"}, {0x33, "baload"}, {0x34, "caload"}, {0x35, "saload"},
                {0x36, "istore"}, {0x37, "lstore"}, {0x38, "fstore"}, {0x39, "dstore"}, {0x3a, "astore"},
                {0x3b, "istore_0"}, {0x3c, "istore_1"}, {0x3d, "istore_2"}, {0x3e, "istore_3"},
                {0x3f, "lstore_0"}, {0x40, "lstore_1"}, {0x41, "lstore_2"}, {0x42, "lstore_3"},
                {0x43, "fstore_0"}, {0x44, "fstore_1"}, {0x45, "fstore_2"}, {0x46, "fstore_3"},
                {0x47, "dstore_0"}, {0x48, "dstore_1"}, {0x49, "dstore_2"}, {0x4a, "dstore_3"},
                {0x4b, "astore_0"}, {0x4c, "astore_1"}, {0x4d, "astore_2"}, {0x4e, "astore_3"},
                {0x4f, "iastore"}, {0x50, "lastore"}, {0x51, "fastore"}, {0x52, "dastore"},
                {0x53, "aastore"}, {0x54, "bastore"}, {0x55, "castore"}, {0x56, "sastore"},
                {0x57, "pop"}, {0x58, "pop2"}, {0x59, "dup"}, {0x5a, "dup_x1"}, {0x5b, "dup_x2"},
                {0x5c, "dup2"}, {0x5d, "dup2_x1"}, {0x5e, "dup2_x2"}, {0x5f, "swap"},
                {0x60, "iadd"}, {0x61, "ladd"}, {0x62, "fadd"}, {0x63, "dadd"}, {0x64, "isub"},
                {0x65, "lsub"}, {0x66, "fsub"}, {0x67, "dsub"}, {0x68, "imul"}, {0x69, "lmul"},
                {0x6a, "fmul"}, {0x6b, "dmul"}, {0x6c, "idiv"}, {0x6d, "ldiv"}, {0x6e, "fdiv"},
                {0x6f, "ddiv"}, {0x70, "irem"}, {0x71, "lrem"}, {0x72, "frem"}, {0x73, "drem"},
                {0x74, "ineg"}, {0x75, "lneg"}, {0x76, "fneg"}, {0x77, "dneg"},
                {0x78, "ishl"}, {0x79, "ishr"}, {0x7a, "iushr"}, {0x7b, "lshl"}, {0x7c, "lshr"},
                {0x7d, "lushr"}, {0x7e, "iand"}, {0x7f, "land"}, {0x80, "ior"}, {0x81, "lor"},
                {0x82, "ixor"}, {0x83, "lxor"}, {0x84, "iinc"},
                {0x85, "i2l"}, {0x86, "i2f"}, {0x87, "i2d"}, {0x88, "l2i"}, {0x89, "l2f"},
                {0x8a, "l2d"}, {0x8b, "f2i"}, {0x8c, "f2l"}, {0x8d, "f2d"}, {0x8e, "d2i"},
                {0x8f, "d2l"}, {0x90, "d2f"}, {0x91, "i2b"}, {0x92, "i2c"}, {0x93, "i2s"},
                {0x94, "lcmp"}, {0x95, "fcmpl"}, {0x96, "fcmpg"}, {0x97, "dcmpl"}, {0x98, "dcmpg"},
                {0x99, "ifeq"}, {0x9a, "ifne"}, {0x9b, "iflt"}, {0x9c, "ifge"}, {0x9d, "ifgt"},
                {0x9e, "ifle"}, {0x9f, "if_icmpeq"}, {0xa0, "if_icmpne"}, {0xa1, "if_icmplt"},
                {0xa2, "if_icmpge"}, {0xa3, "if_icmpgt"}, {0xa4, "if_icmple"},
                {0xa5, "if_acmpeq"}, {0xa6, "if_acmpne"}, {0xa7, "goto"}, {0xa8, "jsr"}, {0xa9, "ret"},
                {0xaa, "tableswitch"}, {0xab, "lookupswitch"},
                {0xac, "ireturn"}, {0xad, "lreturn"}, {0xae, "freturn"}, {0xaf, "dreturn"},
                {0xb0, "areturn"}, {0xb1, "return"},
                {0xb2, "getstatic"}, {0xb3, "putstatic"}, {0xb4, "getfield"}, {0xb5, "putfield"},
                {0xb6, "invokevirtual"}, {0xb7, "invokespecial"}, {0xb8, "invokestatic"},
                {0xb9, "invokeinterface"}, {0xba, "invokedynamic"},
                {0xbb, "new"}, {0xbc, "newarray"}, {0xbd, "anewarray"}, {0xbe, "arraylength"},
                {0xbf, "athrow"}, {0xc0, "checkcast"}, {0xc1, "instanceof"},
                {0xc2, "monitorenter"}, {0xc3, "monitorexit"}, {0xc4, "wide"},
                {0xc5, "multianewarray"}, {0xc6, "ifnull"}, {0xc7, "ifnonnull"},
                {0xc8, "goto_w"}, {0xc9, "jsr_w"},
        };
        for (Object[] row : table) {
            OPCODES.put((Integer) row[0], (String) row[1]);
        }
    }

    /** 操作码 -&gt; 固定操作数字节数（未列出即为 0；tableswitch/lookupswitch/wide 变长单独处理）。 */
    private static int opSize(int op) {
        switch (op) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                 0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc:
                return 1;
            case 0x11, 0x13, 0x14, 0x84,
                 0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6,
                 0xa7, 0xa8,
                 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xbb, 0xbd,
                 0xc0, 0xc1, 0xc6, 0xc7:
                return 2;
            case 0xb9, 0xba, 0xc8, 0xc9:
                return 4;
            case 0xc5:
                return 3;
            default:
                return 0;
        }
    }

    /** 逐条产出指令。变长指令正确处理。 */
    public static List<Instruction> iterInstructions(byte[] code) throws BytecodeException {
        List<Instruction> out = new ArrayList<>();
        int i = 0;
        int n = code.length;
        while (i < n) {
            int op = code[i] & 0xFF;
            int off = i;
            if (op == 0xc4) { // wide
                if (i + 1 >= n) {
                    throw new BytecodeException("wide 指令被截断");
                }
                int mod = code[i + 1] & 0xFF;
                int length = (mod == 0x84) ? 6 : 4;
                out.add(new Instruction(off, op, "wide",
                        Arrays.copyOfRange(code, i, Math.min(i + length, n))));
                i += length;
                continue;
            }
            if (op == 0xaa || op == 0xab) { // tableswitch / lookupswitch
                // 对齐 Python 的 (-(i + 1)) % 4（恒为非负），即补齐到 4 字节对齐
                int pad = (4 - ((i + 1) % 4)) % 4;
                int p = i + 1 + pad;
                int length;
                if (op == 0xaa) {
                    if (p + 12 > n) {
                        throw new BytecodeException("tableswitch 被截断");
                    }
                    int lo = (int) ClassFile.u4(code, p + 4);
                    int hi = (int) ClassFile.u4(code, p + 8);
                    if (hi < lo) {
                        throw new BytecodeException("tableswitch 范围非法");
                    }
                    length = 1 + pad + 12 + 4 * (hi - lo + 1);
                } else {
                    if (p + 8 > n) {
                        throw new BytecodeException("lookupswitch 被截断");
                    }
                    long npairs = Integer.toUnsignedLong((int) ClassFile.u4(code, p + 4));
                    if (npairs > Integer.MAX_VALUE) {
                        throw new BytecodeException("lookupswitch npairs 非法");
                    }
                    length = 1 + pad + 8 + 8 * (int) npairs;
                }
                out.add(new Instruction(off, op, op == 0xaa ? "tableswitch" : "lookupswitch",
                        Arrays.copyOfRange(code, i, Math.min(i + length, n))));
                i += length;
                continue;
            }
            String name = OPCODES.get(op);
            if (name == null) {
                throw new BytecodeException(String.format("未知/保留操作码 0x%02x（偏移 %d）", op, off));
            }
            int size = opSize(op);
            if (i + 1 + size > n) {
                throw new BytecodeException(String.format("指令 0x%02x 被截断（偏移 %d）", op, off));
            }
            out.add(new Instruction(off, op, name, Arrays.copyOfRange(code, i + 1, i + 1 + size)));
            i += 1 + size;
        }
        return out;
    }

    // ---------- 类结构解析 ----------

    private static final class Reader {
        final byte[] data;
        int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int u2() throws BytecodeException {
            if (pos + 2 > data.length) {
                throw new BytecodeException("类结构解析失败（数据不完整）");
            }
            int v = ClassFile.u2(data, pos);
            pos += 2;
            return v;
        }

        int u4() throws BytecodeException {
            if (pos + 4 > data.length) {
                throw new BytecodeException("类结构解析失败（数据不完整）");
            }
            int v = (int) ClassFile.u4(data, pos);
            pos += 4;
            return v;
        }
    }

    private record RawAttr(int nameIndex, byte[] payload) {
    }

    private static List<RawAttr> readAttrs(Reader r, int end) throws BytecodeException {
        List<RawAttr> attrs = new ArrayList<>();
        int count = r.u2();
        for (int i = 0; i < count; i++) {
            int nameIdx = r.u2();
            int length = r.u4();
            if (r.pos + length > end) {
                throw new BytecodeException("属性区越界");
            }
            attrs.add(new RawAttr(nameIdx,
                    Arrays.copyOfRange(r.data, r.pos, r.pos + length)));
            r.pos += length;
        }
        return attrs;
    }

    private static String text(ClassFile cf, int idx) {
        CpEntry entry = cf.entries.get(idx);
        if (entry == null) {
            return "#" + idx;
        }
        if (entry.tag == ClassFile.CONSTANT_Utf8) {
            return entry.text();
        }
        if (entry.tag == ClassFile.CONSTANT_Class) { // 类引用 -> 再解析其 Utf8 名字
            return text(cf, ClassFile.u2(entry.raw, 0));
        }
        return "#" + idx;
    }

    /** 解析字段/方法/类属性。 */
    public static Structure parseStructure(ClassFile cf) throws BytecodeException {
        byte[] data = cf.tail;
        Reader r = new Reader(data);
        try {
            r.u2(); // access
            int thisClass = r.u2();
            r.u2(); // super
            int icount = r.u2();
            r.pos += 2 * icount;
            int end = data.length;

            List<String[]> fields = members(cf, r, end);
            List<MethodInfo> methods = methodsOf(cf, r, end);

            String className = text(cf, thisClass);
            return new Structure(className, fields, methods);
        } catch (BytecodeException e) {
            throw new BytecodeException("类结构解析失败（数据不完整）");
        }
    }

    private static List<String[]> members(ClassFile cf, Reader r, int end) throws BytecodeException {
        int count = r.u2();
        List<String[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            r.u2(); // access
            int nameIdx = r.u2();
            int descIdx = r.u2();
            readAttrs(r, end);
            out.add(new String[]{text(cf, nameIdx), text(cf, descIdx)});
        }
        return out;
    }

    private static List<MethodInfo> methodsOf(ClassFile cf, Reader r, int end) throws BytecodeException {
        int count = r.u2();
        List<MethodInfo> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            r.u2(); // access
            int nameIdx = r.u2();
            int descIdx = r.u2();
            List<RawAttr> attrs = readAttrs(r, end);
            out.add(new MethodInfo(text(cf, nameIdx), text(cf, descIdx), codeOf(cf, attrs)));
        }
        return out;
    }

    private static byte[] codeOf(ClassFile cf, List<RawAttr> attrs) {
        for (RawAttr attr : attrs) {
            if (text(cf, attr.nameIndex()).equals("Code") && attr.payload().length >= 8) {
                // Code: max_stack u2 + max_locals u2 + code_length u4 + code
                int clen = (int) ClassFile.u4(attr.payload(), 4);
                if (8 + clen <= attr.payload().length) {
                    return Arrays.copyOfRange(attr.payload(), 8, 8 + clen);
                }
            }
        }
        return null;
    }

    // ---------- ldc 引用统计 ----------

    /** {字符串文本: [方法名, ...]}，方法按出现顺序去重。解析失败时返回空表。 */
    public static Map<String, List<String>> stringUsage(ClassFile cf) {
        Map<String, List<String>> usage = new LinkedHashMap<>();
        Structure structure;
        try {
            structure = parseStructure(cf);
        } catch (BytecodeException e) {
            return usage;
        }
        for (MethodInfo method : structure.methods()) {
            byte[] code = method.code();
            if (code == null || code.length == 0) {
                continue;
            }
            try {
                for (Instruction ins : iterInstructions(code)) {
                    int idx = ldcIndex(ins);
                    if (idx < 0) {
                        continue;
                    }
                    CpEntry entry = cf.entries.get(idx);
                    if (entry == null || entry.tag != ClassFile.CONSTANT_String) {
                        continue;
                    }
                    int si = ClassFile.u2(entry.raw, 0);
                    CpEntry target = cf.entries.get(si);
                    if (target == null || target.tag != ClassFile.CONSTANT_Utf8) {
                        continue;
                    }
                    List<String> names = usage.computeIfAbsent(target.text(), k -> new ArrayList<>());
                    if (!names.contains(method.name())) {
                        names.add(method.name());
                    }
                }
            } catch (BytecodeException e) {
                // 该方法解析失败则跳过，不影响其它方法
            }
        }
        return usage;
    }

    /** ldc(0x12) 返回 1 字节操作数索引；ldc_w(0x13) 返回 2 字节索引；其它返回 -1。 */
    static int ldcIndex(Instruction ins) {
        if (ins.opcode() == 0x12 && ins.operand().length >= 1) {
            return ins.operand()[0] & 0xFF;
        }
        if (ins.opcode() == 0x13 && ins.operand().length >= 2) {
            return ClassFile.u2(ins.operand(), 0);
        }
        return -1;
    }
}
