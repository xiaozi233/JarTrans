package com.jartrans.ui;

import java.util.ArrayDeque;

/**
 * 撤销/重做历史栈：译文修改、不翻译标记、类状态手动标记三类操作的记录容器。
 * 只做纯状态管理（压栈/上限/取出时在撤销与重做栈之间搬运），
 * 具体如何回放由使用者（MainApp）决定。
 */
public final class EditHistory {

    /** 类状态为「自动判定」时的存储哨兵值（null 无法进栈区分）。 */
    public static final String AUTO = "*auto";

    private static final int LIMIT = 200;

    /** 一条可撤销操作。kind: trans(译文) / skip(不翻译) / mark(类状态)。 */
    public record Hist(String kind, String cls, String orig, String before, String after) {
    }

    private final ArrayDeque<Hist> undoStack = new ArrayDeque<>();
    private final ArrayDeque<Hist> redoStack = new ArrayDeque<>();

    /** 压入一条新操作：清空重做栈；撤销栈超过容量时丢弃最旧的。 */
    public void push(Hist h) {
        undoStack.addFirst(h);
        if (undoStack.size() > LIMIT) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    public boolean canUndo() {
        return !undoStack.isEmpty();
    }

    public boolean canRedo() {
        return !redoStack.isEmpty();
    }

    /** 取出最近一条待撤销操作（同时移入重做栈）。 */
    public Hist takeUndo() {
        Hist h = undoStack.removeFirst();
        redoStack.addFirst(h);
        return h;
    }

    /** 取出最近一条待重做操作（同时移回撤销栈）。 */
    public Hist takeRedo() {
        Hist h = redoStack.removeFirst();
        undoStack.addFirst(h);
        return h;
    }
}
