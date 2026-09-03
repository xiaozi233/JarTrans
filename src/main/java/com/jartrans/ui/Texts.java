package com.jartrans.ui;

/** 文本显示工具：转义/反转义（与 Python 版 editor.py、source_panel.py 一致）。 */
public final class Texts {

    private Texts() {
    }

    /** 把换行/制表符转成可见形式，便于在表格单行里展示。 */
    public static String displayText(String s) {
        return s.replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\t", "\\t");
    }

    /** Java 源码字面量转义。 */
    public static String javaEscape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /** Java 源码字面量反转义。 */
    public static String javaUnescape(String s) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    case 'r' -> out.append('\r');
                    case 'u' -> {
                        if (i + 5 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                out.append(next);
                            }
                        } else {
                            out.append(next);
                        }
                    }
                    default -> out.append(next);
                }
                i += 2;
            } else {
                out.append(ch);
                i += 1;
            }
        }
        return out.toString();
    }
}
