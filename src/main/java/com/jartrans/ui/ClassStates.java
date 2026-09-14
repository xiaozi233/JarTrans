package com.jartrans.ui;

import com.jartrans.core.Project;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 类状态（empty/todo/doing/done/ignore）界面文案与筛选定义的唯一来源。
 *
 * <p>状态键本身定义在 {@link com.jartrans.core.Project#CLASS_STATES}，这里只负责
 * 「键 ↔ 显示名」的双向映射；界面各处（类树图例、状态下拉、首选项、编辑器）都从这里取，
 * 避免同一份文案散落多处。</p>
 */
public final class ClassStates {

    /** 「自动（按翻译进度）」——不是真实状态键，仅用于类状态下拉的首项与回显。 */
    public static final String AUTO_LABEL = "自动";

    /** 状态键 → 显示名。插入顺序固定（不要用 Map.of，其迭代顺序不确定）。 */
    public static final Map<String, String> LABELS = labels();

    /** 类列表状态筛选下拉：{状态键, 显示名}；"all" 表示不筛选。 */
    public static final List<String[]> FILTERS = List.of(
            new String[]{"all", "全部"},
            new String[]{"todo", "未开始"},
            new String[]{"doing", "翻译中"},
            new String[]{"done", "已完成"},
            new String[]{"ignore", "已忽略"},
            new String[]{"empty", "无字符串"});

    /** 可手动标记的状态显示名（与 {@link Project#MANUAL_STATES} 同序，文案取自 LABELS）。 */
    public static final List<String> MANUAL_LABELS =
            Project.MANUAL_STATES.stream().map(LABELS::get).toList();

    /** 类状态下拉的显示项：「自动」+ 可手动选择的状态。 */
    public static final List<String> SELECTABLE_LABELS = selectableLabels();

    private ClassStates() {
    }

    /** 状态键 → 显示名；未知键返回 null。 */
    public static String labelOf(String key) {
        return LABELS.get(key);
    }

    /** 显示名 → 状态键；未知返回 null（「自动」等不可手动选择的项走此路径）。 */
    public static String keyOf(String label) {
        for (Map.Entry<String, String> e : LABELS.entrySet()) {
            if (e.getValue().equals(label)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** 筛选用：显示名 → 状态键；未知返回 "all"。 */
    public static String filterKeyOf(String label) {
        for (String[] f : FILTERS) {
            if (f[1].equals(label)) {
                return f[0];
            }
        }
        return "all";
    }

    /** 筛选用：状态键 → 显示名；未知返回 "全部"。 */
    public static String filterLabelOf(String key) {
        for (String[] f : FILTERS) {
            if (f[0].equals(key)) {
                return f[1];
            }
        }
        return "全部";
    }

    private static List<String> selectableLabels() {
        List<String> out = new ArrayList<>();
        out.add(AUTO_LABEL);
        out.addAll(MANUAL_LABELS);
        return List.copyOf(out);
    }

    private static Map<String, String> labels() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("empty", "无字符串");
        m.put("todo", "未开始");
        m.put("doing", "翻译中");
        m.put("done", "已完成");
        m.put("ignore", "已忽略");
        return Collections.unmodifiableMap(m);
    }
}
