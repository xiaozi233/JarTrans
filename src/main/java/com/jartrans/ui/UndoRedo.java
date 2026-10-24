package com.jartrans.ui;

import com.jartrans.core.Project;
import java.util.ArrayList;
import java.util.List;

/**
 * 撤销/重做控制器：把译文修改、「不翻译」标记、类状态手动标记三类操作记入
 * {@link EditHistory}，并负责回放（回放一律走 Project 的写入接口，成功后刷新界面）。
 *
 * <p>批量操作（全部替换等）合并为一条 {@link EditHistory.Batch}，一次撤销整体回滚。</p>
 */
final class UndoRedo {

    private final MainApp app;
    private final EditHistory history = new EditHistory();

    UndoRedo(MainApp app) {
        this.app = app;
    }

    /** 记录一次译文变更（EditorPane 保存/清空后按生效值调用）。 */
    void recordTranslation(String cls, String orig, String before, String after) {
        if (before.equals(after)) {
            return;
        }
        history.push(new EditHistory.Hist("trans", cls, orig, before, after));
    }

    /** 把一组替换变更合并为单条撤销记录（「全部替换」一步回滚）。 */
    void recordBatchTranslation(List<Project.ReplaceChange> changes, String after) {
        if (changes.isEmpty()) {
            return;
        }
        List<EditHistory.Hist> items = new ArrayList<>(changes.size());
        for (Project.ReplaceChange c : changes) {
            items.add(new EditHistory.Hist("trans", c.cls(), c.orig(), c.before(), after));
        }
        history.push(new EditHistory.Batch("trans", items));
    }

    /** 记录「不翻译」切换。 */
    void recordSkip(String orig, boolean before, boolean after) {
        if (before == after) {
            return;
        }
        history.push(new EditHistory.Hist("skip", null, orig, before ? "1" : "0", after ? "1" : "0"));
    }

    /** 记录类状态手动标记（null 视为自动）。 */
    void recordClassMark(String cls, String beforeManual, String afterManual) {
        String b = beforeManual == null ? EditHistory.AUTO : beforeManual;
        String a = afterManual == null ? EditHistory.AUTO : afterManual;
        if (b.equals(a)) {
            return;
        }
        history.push(new EditHistory.Hist("mark", cls, null, b, a));
    }

    void undo() {
        if (!history.canUndo()) {
            app.setStatus("没有可撤销的操作");
            return;
        }
        EditHistory.Op op = history.takeUndo();
        applyOp(op, true);
        app.setStatus("已撤销：" + labelOf(op));
    }

    void redo() {
        if (!history.canRedo()) {
            app.setStatus("没有可重做的操作");
            return;
        }
        EditHistory.Op op = history.takeRedo();
        applyOp(op, false);
        app.setStatus("已重做：" + labelOf(op));
    }

    private static String labelOf(EditHistory.Op op) {
        if (op instanceof EditHistory.Batch b) {
            return label(b.kind()) + "（" + b.items().size() + " 条）";
        }
        return label(((EditHistory.Hist) op).kind());
    }

    private static String label(String kind) {
        return switch (kind) {
            case "trans" -> "译文修改";
            case "skip" -> "「不翻译」标记";
            case "mark" -> "类状态标记";
            default -> kind;
        };
    }

    /** 回放单条或整批操作；undoing 决定写回 before（撤销）还是 after（重做）。 */
    private void applyOp(EditHistory.Op op, boolean undoing) {
        if (op instanceof EditHistory.Hist h) {
            apply(h, undoing ? h.before() : h.after());
            return;
        }
        EditHistory.Batch b = (EditHistory.Batch) op;
        List<EditHistory.Hist> items = b.items();
        for (int i = undoing ? items.size() - 1 : 0; undoing ? i >= 0 : i < items.size(); i += undoing ? -1 : 1) {
            EditHistory.Hist h = items.get(i);
            apply(h, undoing ? h.before() : h.after());
        }
    }

    /** 把某条历史操作的目标值写回工程并刷新界面。 */
    private void apply(EditHistory.Hist h, String target) {
        try {
            switch (h.kind()) {
                case "trans" -> app.project().setTranslation(h.cls(), h.orig(), target);
                case "skip" -> app.project().setTextSkipped(h.orig(), "1".equals(target));
                case "mark" -> app.project().setClassStatus(h.cls(), EditHistory.AUTO.equals(target) ? null : target);
                default -> {
                    return;
                }
            }
        } catch (Exception exc) {
            return; // 写盘失败等不回滚界面
        }
        app.refreshClassNodes();
        app.updateStats();
        if (app.project().hasJar()) {
            app.editor().refreshRows();
            if (h.orig() != null) {
                app.editor().selectRow(h.orig());
            }
        }
    }
}
