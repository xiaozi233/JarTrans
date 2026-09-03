package com.jartrans.ui;

import com.jartrans.core.Bytecode;
import com.jartrans.core.ClassFile;
import com.jartrans.core.Disassembler;
import com.jartrans.core.java.DecompilerManager;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 源码页签：只读源码/字节码视图、语法着色、面板内搜索、双向跳转。
 * （对应 gui/source_panel.py；着色 span 在渲染时一次性预计算）
 */
public class SourcePanel extends BorderPane {

    private static final Pattern KW_RE = Pattern.compile(
            "\\b(abstract|assert|boolean|break|byte|case|catch|char|class|const|continue|"
                    + "default|do|double|else|enum|extends|final|finally|float|for|goto|if|"
                    + "implements|import|instanceof|int|interface|long|native|new|package|"
                    + "private|protected|public|record|return|short|static|strictfp|super|"
                    + "switch|synchronized|this|throw|throws|transient|try|var|void|volatile|"
                    + "while|true|false|null)\\b");
    private static final Pattern STR_RE =
            Pattern.compile("\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'");
    private static final Pattern NUM_RE =
            Pattern.compile("\\b(0[xX][0-9a-fA-F_]+|\\d[\\d_]*(?:\\.[\\d_]+)?[fFdDlL]?)\\b");

    /** 渲染模式。 */
    private enum Mode {JAVA, BYTECODE, LOADING}

    /** 预计算的着色片段：kind 0=注释 1=字符串 2=关键字 3=数字 4=横幅整行。 */
    private record Span(int kind, int start, int end) {
    }

    private MainApp app;
    private Mode mode = Mode.BYTECODE;
    private String currentClass;
    private final List<String> lines = new ArrayList<>();
    private final Map<Integer, List<Span>> spans = new HashMap<>();
    private final List<Map.Entry<String, Path>> files = new ArrayList<>();
    private final List<Integer> hitLines = new ArrayList<>();
    private int currentHit = -1;
    private String pendingJump;
    private long loadingStartedAt;
    private javafx.animation.Timeline loadingTimer;

    private final ListView<String> listView = new ListView<>();
    private final ComboBox<String> fileBox = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final Label info = new Label("");
    private final Button setupBtn = new Button("配置反编译器");
    private final Label loadingLabel = new Label("");
    private final Button cancelBtn = new Button("取消反编译");
    private final HBox loadingBar = new HBox(8);

    public SourcePanel() {
        buildUi();
    }

    public void setApp(MainApp app) {
        this.app = app;
    }

    private void buildUi() {
        setPadding(new Insets(4));
        HBox bar = new HBox(8);
        bar.setPadding(new Insets(0, 0, 4, 0));
        bar.setAlignment(Pos.CENTER_LEFT);
        fileBox.setPrefWidth(380);
        fileBox.valueProperty().addListener((o, ov, nv) -> onPickFile());
        searchField.setPromptText("搜索");
        searchField.setPrefWidth(200);
        searchField.setOnAction(e -> findNext());
        Button nextBtn = new Button("下一个");
        nextBtn.setOnAction(e -> findNext());
        info.setStyle("-fx-text-fill: -jr-muted;");
        HBox.setHgrow(info, javafx.scene.layout.Priority.ALWAYS);
        bar.getChildren().addAll(fileBox, new Label("搜索:"), searchField, nextBtn, info);
        setTop(bar);

        listView.setCellFactory(v -> new javafx.scene.control.ListCell<>() {
            private final TextFlow flow = new TextFlow();

            {
                setContentDisplay(javafx.scene.control.ContentDisplay.GRAPHIC_ONLY);
                flow.setStyle("-fx-font-family: Consolas, monospace; -fx-font-size: 13px;");
            }

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || app == null) {
                    setGraphic(null);
                    return;
                }
                int idx = getIndex();
                boolean hit = hitLines.contains(idx);
                boolean cur = idx == currentHit && hit;
                flow.getChildren().setAll(renderStyled(item, idx));
                String bg = cur ? app.theme().color("syntax_cur")
                        : hit ? app.theme().color("syntax_hl") : null;
                setStyle(bg != null ? "-fx-background-color: " + bg + ";" : "");
                setGraphic(flow);
            }
        });
        setCenter(listView);
        listView.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                onDoubleClick();
            }
        });

        loadingBar.setPadding(new Insets(4, 0, 0, 0));
        loadingBar.setAlignment(Pos.CENTER_LEFT);
        cancelBtn.setOnAction(e -> app.cancelDecompile());
        loadingBar.getChildren().addAll(loadingLabel, cancelBtn);
        loadingBar.setVisible(false);
        loadingBar.setManaged(false);
        setBottom(loadingBar);
    }

    /** 主题切换后整体重绘。 */
    public void refreshTheme() {
        listView.refresh();
    }

    // ---------- 入口 ----------

    public void display(String cls, String jumpOrig) {
        currentClass = cls;
        pendingJump = jumpOrig;
        app.ensureSourceReady(cls, (modeFlag, payload) -> Platform.runLater(() -> {
            switch (modeFlag) {
                case "java" -> showJava((Path) payload);
                case "loading" -> showLoading();
                default -> showBytecode();
            }
        }));
    }

    // ---------- 渲染 ----------

    private void resetText() {
        lines.clear();
        spans.clear();
        hitLines.clear();
        currentHit = -1;
        listView.getItems().clear();
    }

    private void render(String content, String banner) {
        resetText();
        if (banner != null) {
            lines.add(banner);
        }
        for (String line : content.split("\n", -1)) {
            lines.add(line);
        }
        computeSpans();
        listView.getItems().setAll(lines);
    }

    /** 一次性预计算所有行的着色片段。 */
    private void computeSpans() {
        spans.clear();
        boolean inBlock = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            List<Span> out = new ArrayList<>();
            if (i == 0 && mode == Mode.BYTECODE) {
                out.add(new Span(4, 0, line.length()));
                spans.put(i, out);
                continue;
            }
            List<int[]> cmt = new ArrayList<>();
            List<int[]> str = new ArrayList<>();
            if (inBlock) {
                int end = line.indexOf("*/");
                cmt.add(new int[]{0, end < 0 ? line.length() : end + 2});
                inBlock = end < 0;
            } else {
                Matcher sm = STR_RE.matcher(line);
                while (sm.find()) {
                    str.add(new int[]{sm.start(), sm.end()});
                }
                // 行注释（跳过字符串内的 //）
                int idx = 0;
                while ((idx = line.indexOf("//", idx)) >= 0) {
                    final int p = idx;
                    boolean insideStr = str.stream().anyMatch(s -> s[0] <= p && p < s[1]);
                    if (!insideStr) {
                        cmt.add(new int[]{idx, line.length()});
                        break;
                    }
                    idx += 2;
                }
                // 块注释开/闭
                Matcher bm = Pattern.compile("/\\*|\\*/").matcher(line);
                while (bm.find()) {
                    final int p = bm.start();
                    boolean insideStr = str.stream().anyMatch(s -> s[0] <= p && p < s[1]);
                    if (insideStr) {
                        continue;
                    }
                    if (line.startsWith("/*", p)) {
                        int close = line.indexOf("*/", p + 2);
                        if (close < 0) {
                            cmt.add(new int[]{p, line.length()});
                            inBlock = true;
                        } else {
                            cmt.add(new int[]{p, close + 2});
                        }
                    }
                }
            }
            java.util.function.IntFunction<String> commentAt = p -> {
                for (int[] s : cmt) {
                    if (s[0] <= p && p < s[1]) {
                        return "c";
                    }
                }
                return null;
            };
            for (int[] s : cmt) {
                out.add(new Span(0, s[0], s[1]));
            }
            for (int[] s : str) {
                if (commentAt.apply(s[0]) == null) {
                    out.add(new Span(1, s[0], s[1]));
                }
            }
            for (Matcher m = KW_RE.matcher(line); m.find(); ) {
                final int p = m.start();
                if (commentAt.apply(p) == null
                        && str.stream().noneMatch(s -> s[0] <= p && p < s[1])) {
                    out.add(new Span(2, m.start(), m.end()));
                }
            }
            for (Matcher m = NUM_RE.matcher(line); m.find(); ) {
                final int p = m.start();
                if (commentAt.apply(p) == null
                        && str.stream().noneMatch(s -> s[0] <= p && p < s[1])) {
                    out.add(new Span(3, m.start(), m.end()));
                }
            }
            spans.put(i, out);
        }
    }

    /** 按预计算 span 生成一行富文本。 */
    private List<Text> renderStyled(String line, int lineIdx) {
        List<Span> list = spans.getOrDefault(lineIdx, List.of());
        List<Text> out = new ArrayList<>();
        String fg = app.theme().color("fg");
        int pos = 0;
        for (Span s : list) {
            if (s.start() < pos || s.start() > line.length()) {
                continue;
            }
            if (s.start() > pos) {
                Text plain = new Text(line.substring(pos, s.start()));
                plain.setFill(javafx.scene.paint.Color.web(fg));
                out.add(plain);
            }
            String color = switch (s.kind()) {
                case 0 -> app.theme().color("syntax_cmt");
                case 1 -> app.theme().color("syntax_str");
                case 2 -> app.theme().color("syntax_kw");
                case 3 -> app.theme().color("syntax_num");
                default -> app.theme().color("syntax_banner");
            };
            Text t = new Text(line.substring(s.start(), Math.min(s.end(), line.length())));
            t.setFill(javafx.scene.paint.Color.web(color));
            if (s.kind() == 4) {
                t.setStyle("-fx-font-weight: bold;");
            }
            out.add(t);
            pos = Math.min(s.end(), line.length());
        }
        if (pos < line.length()) {
            Text plain = new Text(line.substring(pos));
            plain.setFill(javafx.scene.paint.Color.web(fg));
            out.add(plain);
        }
        if (out.isEmpty()) {
            out.add(new Text(line.isEmpty() ? " " : line));
        }
        return out;
    }

    // ---------- 反编译模式 ----------

    private void showJava(Path cacheDir) {
        mode = Mode.JAVA;
        loadingBar.setVisible(false);
        loadingBar.setManaged(false);
        Map<String, Path> index = app.decompiledIndex();
        DecompilerManager.SourceFiles sf =
                DecompilerManager.sourceFilesFor(index, currentClass);
        files.clear();
        if (sf.external() != null) {
            files.add(Map.entry(sf.external(), cacheDir.resolve(sf.external())));
        }
        for (String rel : sf.inners()) {
            files.add(Map.entry(rel, cacheDir.resolve(rel)));
        }
        if (files.isEmpty()) {
            fileBox.getItems().setAll("");
            fileBox.getSelectionModel().selectFirst();
            render("// 未在反编译产物中找到 " + currentClass + " 对应的 .java 文件", null);
            return;
        }
        List<String> display = new ArrayList<>();
        for (var e : files) {
            display.add(e.getValue().getFileName() + "   (" + e.getKey() + ")");
        }
        fileBox.getItems().setAll(display);
        fileBox.getSelectionModel().selectFirst();
        loadFile(files.get(0).getValue());
        afterLoad();
    }

    private void onPickFile() {
        int sel = fileBox.getSelectionModel().getSelectedIndex();
        if (sel >= 0 && sel < files.size()) {
            loadFile(files.get(sel).getValue());
            afterLoad();
        }
    }

    private void loadFile(Path path) {
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            render(content, null);
            info.setText(path.toString());
        } catch (Exception exc) {
            render("// 无法读取源码文件：" + exc.getMessage(), null);
        }
    }

    private void afterLoad() {
        if (pendingJump != null) {
            String jump = pendingJump;
            pendingJump = null;
            highlightLiteral(jump);
        }
    }

    // ---------- 字节码降级模式 ----------

    private void showBytecode() {
        mode = Mode.BYTECODE;
        loadingBar.setVisible(false);
        loadingBar.setManaged(false);
        fileBox.getItems().clear();
        ClassFile cf = app.project().classes().get(currentClass);
        if (cf == null) {
            resetText();
            return;
        }
        String content;
        try {
            content = Disassembler.disassemble(cf);
        } catch (Bytecode.BytecodeException exc) {
            content = "// 字节码解析失败：" + exc.getMessage();
        }
        boolean hasDec = DecompilerManager.currentDecompiler(app.settings()) != null;
        String banner = hasDec
                ? "反编译器已就绪但尚未完成反编译，以下为字节码视图。"
                : "未配置反编译器，当前为字节码视图（仅供对照，不影响翻译功能）";
        render(content, banner);
        info.setText("");
    }

    private void showLoading() {
        mode = Mode.LOADING;
        resetText();
        fileBox.getItems().clear();
        loadingStartedAt = System.currentTimeMillis();
        loadingBar.setVisible(true);
        loadingBar.setManaged(true);
        if (loadingTimer != null) {
            loadingTimer.stop();
        }
        loadingTimer = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(500), e -> tickLoading()));
        loadingTimer.setCycleCount(javafx.animation.Timeline.INDEFINITE);
        loadingTimer.play();
        tickLoading();
    }

    private void tickLoading() {
        if (mode != Mode.LOADING) {
            return;
        }
        long elapsed = (System.currentTimeMillis() - loadingStartedAt) / 1000;
        loadingLabel.setText("正在反编译整个 jar（已用时 " + elapsed + " 秒），完成后自动显示源码…");
    }

    // ---------- 跳转与搜索 ----------

    /** 在当前类及内部类源码中查找字符串字面量，全部高亮并跳到第一处。 */
    public boolean highlightLiteral(String literal) {
        List<String> variants = new ArrayList<>();
        variants.add(literal);
        variants.add(Texts.javaEscape(literal));
        StringBuilder sb = new StringBuilder();
        for (char ch : Texts.javaEscape(literal).toCharArray()) {
            sb.append(ch < 128 ? String.valueOf(ch) : String.format("\\u%04x", (int) ch));
        }
        variants.add(sb.toString());
        List<Map.Entry<String, Path>> ordered = new ArrayList<>(files);
        int cur = fileBox.getSelectionModel().getSelectedIndex();
        if (cur > 0 && cur < ordered.size()) {
            Map.Entry<String, Path> first = ordered.remove(cur);
            ordered.add(0, first);
        }
        for (var entry : ordered) {
            String content;
            try {
                content = Files.readString(entry.getValue(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                continue;
            }
            for (String variant : variants) {
                if (!content.contains(variant)) {
                    continue;
                }
                if (!entry.getKey().equals(currentFileKey())) {
                    for (int k = 0; k < files.size(); k++) {
                        if (files.get(k).getKey().equals(entry.getKey())) {
                            fileBox.getSelectionModel().select(k);
                            loadFile(files.get(k).getValue());
                            break;
                        }
                    }
                }
                tagHits(variant);
                info.setText(entry.getValue().getFileName() + "：找到 "
                        + countMatches(content, variant) + " 处（" + entry.getKey() + "）");
                return true;
            }
        }
        info.setText("源码中未找到该字符串（可能被反编译器改写或位于其它类）");
        return false;
    }

    private String currentFileKey() {
        int sel = fileBox.getSelectionModel().getSelectedIndex();
        return sel >= 0 && sel < files.size() ? files.get(sel).getKey() : "";
    }

    private static int countMatches(String content, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(needle, idx)) >= 0) {
            count++;
            idx += 1;
        }
        return count;
    }

    private void tagHits(String needle) {
        hitLines.clear();
        currentHit = -1;
        int firstHit = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) {
                if (firstHit < 0) {
                    firstHit = i;
                }
                hitLines.add(i);
            }
        }
        if (firstHit >= 0) {
            currentHit = firstHit;
            listView.getSelectionModel().clearAndSelect(firstHit);
            listView.scrollTo(firstHit);
        }
        listView.refresh();
    }

    public void findNext() {
        String query = searchField.getText().trim();
        if (query.isEmpty() || lines.isEmpty()) {
            return;
        }
        String q = query.toLowerCase();
        int start = listView.getSelectionModel().getSelectedIndex();
        for (int step = 1; step <= lines.size(); step++) {
            int idx = (Math.max(start, 0) + step) % lines.size();
            if (lines.get(idx).toLowerCase().contains(q)) {
                listView.getSelectionModel().clearAndSelect(idx);
                listView.scrollTo(idx);
                info.setText("第 " + (idx + 1) + " 行");
                return;
            }
        }
        info.setText("未找到");
    }

    // ---------- 双击取字面量 ----------

    private void onDoubleClick() {
        String literal = literalUnderSelection();
        if (literal != null) {
            app.jumpSourceToTable(literal);
        }
    }

    /** 从当前选中行的文本中提取第一个字符串字面量，反转义后返回。 */
    private String literalUnderSelection() {
        int idx = listView.getSelectionModel().getSelectedIndex();
        if (idx < 0 || idx >= lines.size()) {
            return null;
        }
        String line = lines.get(idx);
        Matcher m = STR_RE.matcher(line);
        if (!m.find()) {
            return null;
        }
        String best = m.group(0);
        return best.length() >= 2
                ? Texts.javaUnescape(best.substring(1, best.length() - 1))
                : null;
    }

    public String currentClass() {
        return currentClass;
    }
}
