package com.jartrans.ui;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * 可自定义快捷键的定义与文本/组合键双向解析（单一来源）。
 * 存储于 settings 的 "key_&lt;id&gt;"；"Ctrl+Alt+F" 这类文本 ↔ KeyCodeCombination 的转换都在这里。
 */
public final class Shortcuts {

    /** 可自定义快捷键：id / 显示名 / 默认组合键。 */
    static final List<String[]> DEFS = List.of(
            new String[]{"undo", "撤销", "Ctrl+Z"},
            new String[]{"redo", "重做", "Ctrl+Y"},
            new String[]{"save", "保存译文", "Ctrl+S"},
            new String[]{"find", "类内搜索（当前类）", "Ctrl+F"},
            new String[]{"replace", "替换译文（当前类）", "Ctrl+R"},
            new String[]{"gsearch", "全包搜索", "Ctrl+Alt+F"},
            new String[]{"prefs", "打开首选项", "Ctrl+,"});

    private Shortcuts() {
    }

    /** 规范化存储文本：修饰符统一大写、按键名统一。 */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String part : text.split("\\+")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('+');
            }
            String low = p.toLowerCase();
            if (low.length() == 1 && Character.isLetter(low.charAt(0))) {
                sb.append(low.toUpperCase()); // 字母键统一大写存储
            } else {
                sb.append(switch (low) {
                    case "ctrl", "control" -> "Ctrl";
                    case "alt" -> "Alt";
                    case "shift" -> "Shift";
                    case "meta", "cmd", "command" -> "Meta";
                    default -> low.length() == 1 ? low : p.toUpperCase();
                });
            }
        }
        return sb.toString();
    }

    /** 某动作的默认组合键文本；未知 id 返回空串。 */
    static String defaultFor(String id) {
        for (String[] d : DEFS) {
            if (d[0].equals(id)) {
                return d[2];
            }
        }
        return "";
    }

    private static KeyCode parseKeyToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        if (token.length() == 1) {
            char c = token.charAt(0);
            if (c >= 'a' && c <= 'z') {
                return KeyCode.valueOf(token.toUpperCase());
            }
            if (c >= 'A' && c <= 'Z') {
                return KeyCode.valueOf(token);
            }
            if (c >= '0' && c <= '9') {
                return KeyCode.valueOf("DIGIT" + c);
            }
            return switch (c) {
                case ',' -> KeyCode.COMMA;
                case '.' -> KeyCode.PERIOD;
                case ';' -> KeyCode.SEMICOLON;
                case '\'' -> KeyCode.QUOTE;
                case '`' -> KeyCode.BACK_QUOTE;
                case '-' -> KeyCode.MINUS;
                case '=' -> KeyCode.EQUALS;
                case '/' -> KeyCode.SLASH;
                case '\\' -> KeyCode.BACK_SLASH;
                case '[' -> KeyCode.OPEN_BRACKET;
                case ']' -> KeyCode.CLOSE_BRACKET;
                case ' ' -> KeyCode.SPACE;
                default -> null;
            };
        }
        return switch (token) {
            case "Space" -> KeyCode.SPACE;
            case "Enter" -> KeyCode.ENTER;
            case "Tab" -> KeyCode.TAB;
            case "Escape", "Esc" -> KeyCode.ESCAPE;
            case "Backspace", "BackSpace" -> KeyCode.BACK_SPACE;
            case "Delete" -> KeyCode.DELETE;
            case "Home" -> KeyCode.HOME;
            case "End" -> KeyCode.END;
            case "PageUp" -> KeyCode.PAGE_UP;
            case "PageDown" -> KeyCode.PAGE_DOWN;
            case "Up", "ArrowUp" -> KeyCode.UP;
            case "Down", "ArrowDown" -> KeyCode.DOWN;
            case "Left", "ArrowLeft" -> KeyCode.LEFT;
            case "Right", "ArrowRight" -> KeyCode.RIGHT;
            default -> {
                try {
                    yield KeyCode.valueOf(token);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }

    /** 按键 → 可逆的文本记号（与 parseKeyToken 互为逆）。 */
    private static String keyToken(KeyCode code) {
        if (code == null) {
            return null;
        }
        String n = code.name();
        if (n.length() == 1 && Character.isLetter(n.charAt(0))) {
            return n; // 字母 A-Z
        }
        if (n.startsWith("DIGIT") && n.length() == 6 && Character.isDigit(n.charAt(5))) {
            return n.substring(5); // 数字 0-9
        }
        if (n.length() >= 2 && n.charAt(0) == 'F'
                && Character.isDigit(n.charAt(1))) {
            return n; // 功能键 F1-F24
        }
        return switch (code) {
            case COMMA -> ",";
            case PERIOD -> ".";
            case SEMICOLON -> ";";
            case QUOTE -> "'";
            case BACK_QUOTE -> "`";
            case MINUS -> "-";
            case EQUALS -> "=";
            case SLASH -> "/";
            case BACK_SLASH -> "\\";
            case OPEN_BRACKET -> "[";
            case CLOSE_BRACKET -> "]";
            case SPACE -> "Space";
            case ENTER -> "Enter";
            case TAB -> "Tab";
            case ESCAPE -> "Esc";
            case BACK_SPACE -> "Backspace";
            case DELETE -> "Delete";
            case HOME -> "Home";
            case END -> "End";
            case PAGE_UP -> "PageUp";
            case PAGE_DOWN -> "PageDown";
            case UP -> "Up";
            case DOWN -> "Down";
            case LEFT -> "Left";
            case RIGHT -> "Right";
            default -> null; // 不支持随意重绑的按键
        };
    }

    /** 由按键事件构造组合键文本；修饰键单独按下或按键不可重绑时返回 null。 */
    static String comboText(KeyEvent e) {
        KeyCode code = e.getCode();
        if (code == null || code.isModifierKey() || code == KeyCode.UNDEFINED) {
            return null;
        }
        boolean fn = code.name().matches("F\\d+");
        if (!e.isControlDown() && !e.isAltDown() && !e.isShiftDown()
                && !e.isMetaDown() && !fn) {
            return null; // 无修饰键且非 F 键，容易与正常输入冲突，不采用
        }
        String token = keyToken(code);
        if (token == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        if (e.isControlDown()) {
            sb.append("Ctrl+");
        }
        if (e.isAltDown()) {
            sb.append("Alt+");
        }
        if (e.isShiftDown()) {
            sb.append("Shift+");
        }
        if (e.isMetaDown()) {
            sb.append("Meta+");
        }
        sb.append(token);
        return sb.toString();
    }

    /** 把 "Ctrl+Alt+F" 样式的文本解析为组合键；非法返回 null。 */
    static KeyCodeCombination parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        boolean ctrl = false, alt = false, shift = false, meta = false;
        String[] parts = text.split("\\+");
        KeyCode key = null;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim();
            if (i == parts.length - 1) {
                key = parseKeyToken(p);
                break;
            }
            String low = p.toLowerCase();
            if (low.equals("ctrl") || low.equals("control")) {
                ctrl = true;
            } else if (low.equals("alt")) {
                alt = true;
            } else if (low.equals("shift")) {
                shift = true;
            } else if (low.equals("meta") || low.equals("cmd") || low.equals("command")) {
                meta = true;
            } else {
                return null;
            }
        }
        if (key == null) {
            return null;
        }
        List<KeyCombination.Modifier> mods = new ArrayList<>();
        if (ctrl) {
            mods.add(KeyCombination.CONTROL_DOWN);
        }
        if (alt) {
            mods.add(KeyCombination.ALT_DOWN);
        }
        if (shift) {
            mods.add(KeyCombination.SHIFT_DOWN);
        }
        if (meta) {
            mods.add(KeyCombination.META_DOWN);
        }
        return new KeyCodeCombination(key,
                mods.toArray(new KeyCombination.Modifier[0]));
    }
}
