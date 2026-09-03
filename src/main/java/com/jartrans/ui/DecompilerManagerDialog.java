package com.jartrans.ui;

import com.jartrans.core.AppMeta;
import com.jartrans.core.Settings;
import com.jartrans.core.java.DecompilerDownloader;
import com.jartrans.core.java.DecompilerManager;
import com.jartrans.core.java.DecompilerType;
import com.jartrans.core.java.JavaEnv;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 反编译管理器（对应并扩展 setup_dialog.py）。
 * 顶层「反编译管理器」分类 → 「实现」子节点 → CFR / Procyon / Vineflower，
 * 每项可独立「下载 / 校验 / 设为默认 / 移除」；另有 Java 环境与自定义 URL 下载。
 */
public class DecompilerManagerDialog extends Stage {

    private final MainApp app;
    private final TreeView<String> tree = new TreeView<>();
    private final TreeItem<String> rootItem = new TreeItem<>("反编译管理器");
    private final TreeItem<String> implItem = new TreeItem<>("实现");
    private final TreeItem<String> vineItem = new TreeItem<>("Vineflower（推荐）");
    private final TreeItem<String> cfrItem = new TreeItem<>("CFR（备选）");
    private final TreeItem<String> procyonItem = new TreeItem<>("Procyon（备选）");
    private final TreeItem<String> javaItem = new TreeItem<>("Java 运行环境");

    private final Label javaInfo = new Label("检测中…");
    private final Label detailTitle = new Label("");
    private final Label detailStatus = new Label("");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label dlLabel = new Label("空闲");
    private final TextField urlField = new TextField();
    private final Label statusLabel = new Label("");
    private final Button downloadBtn = new Button("下载");
    private final Button verifyBtn = new Button("校验");
    private final Button defaultBtn = new Button("设为默认");
    private final Button removeBtn = new Button("移除");
    private final Button autoDetectBtn = new Button("自动检测");
    private final Button pickJavaBtn = new Button("手动指定 java.exe…");

    private DecompilerType selectedType;
    private volatile boolean dlCancelled;

    public DecompilerManagerDialog(MainApp app) {
        this.app = app;
        setTitle("反编译管理器");
        setWidth(860);
        setHeight(560);
        initModality(Modality.NONE);
        initOwner(app.stage());
        setResizable(true);
        setMinWidth(720);
        setMinHeight(460);

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(10));
        root.getStyleClass().add("root-pane");

        // ---- 左侧分类树 ----
        rootItem.getChildren().addAll(implItem, javaItem);
        implItem.getChildren().addAll(vineItem, cfrItem, procyonItem);
        rootItem.setExpanded(true);
        implItem.setExpanded(true);
        tree.setRoot(rootItem);
        tree.setPrefWidth(220);
        tree.getSelectionModel().selectedItemProperty().addListener((o, ov, nv) -> onTreeSelect());
        tree.getSelectionModel().select(implItem);
        root.setLeft(tree);
        BorderPane.setMargin(tree, new Insets(0, 10, 0, 0));

        // ---- 右侧详情 ----
        VBox detail = new VBox(8);
        detailTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        detailStatus.setWrapText(true);
        HBox btnRow = new HBox(8, downloadBtn, verifyBtn, defaultBtn, removeBtn);
        btnRow.setPadding(new Insets(4, 0, 4, 0));

        VBox downloadBox = new VBox(6,
                new Label("下载进度："), dlLabel, progress,
                customUrlRow(), hintLabel());
        downloadBox.setPadding(new Insets(8, 0, 8, 0));

        TitledPane javaPane = new TitledPane("Java 运行环境", new VBox(6,
                javaInfo, new HBox(8, autoDetectBtn, pickJavaBtn)));
        javaPane.setCollapsible(false);

        detail.getChildren().addAll(detailTitle, detailStatus, btnRow, downloadBox,
                javaPane, statusLabel);
        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: -jr-accent;");
        VBox.setVgrow(detailStatus, Priority.NEVER);
        root.setCenter(detail);

        // ---- 事件 ----
        downloadBtn.setOnAction(e -> downloadSelected());
        verifyBtn.setOnAction(e -> verifySelected());
        defaultBtn.setOnAction(e -> setDefault());
        removeBtn.setOnAction(e -> removeSelected());
        autoDetectBtn.setOnAction(e -> refreshJava(true));
        pickJavaBtn.setOnAction(e -> pickJava());
        tree.setOnMouseClicked(e -> {
            // 展开交互交给默认行为
        });

        setScene(new Scene(root));
        app.theme().attach(getScene());
        refreshJava(false);
        refreshDetail();
    }

    private HBox customUrlRow() {
        HBox row = new HBox(6);
        row.setAlignment(Pos.CENTER_LEFT);
        urlField.setPromptText("自定义直链 URL（镜像/离线包）");
        HBox.setHgrow(urlField, Priority.ALWAYS);
        Button downloadCustomBtn = new Button("从此地址下载");
        downloadCustomBtn.setOnAction(e -> downloadCustom());
        row.getChildren().addAll(new Label("自定义 URL："), urlField, downloadCustomBtn);
        return row;
    }

    private Label hintLabel() {
        Label hint = new Label("也可手动下载 jar（GitHub Releases 页）后放入：\n"
                + DecompilerManager.toolsDir()
                + "\n文件名含 vineflower / procyon / cfr 即可被自动识别。");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: -jr-muted;");
        return hint;
    }

    // ---------- 树选择 ----------

    private void onTreeSelect() {
        TreeItem<String> item = tree.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        if (item == javaItem) {
            selectedType = null;
            detailTitle.setText("Java 运行环境");
            detailStatus.setText(javaInfo.getText());
            setImplButtonsEnabled(false);
            return;
        }
        setImplButtonsEnabled(true);
        if (item == vineItem) {
            selectedType = DecompilerType.VINEFLOWER;
        } else if (item == cfrItem) {
            selectedType = DecompilerType.CFR;
        } else if (item == procyonItem) {
            selectedType = DecompilerType.PROCYON;
        } else {
            selectedType = null;
            detailTitle.setText("反编译管理器");
            detailStatus.setText("管理 " + AppMeta.APP_NAME + " 使用的反编译器实现：\n"
                    + "· 每个「实现」可独立下载 / 校验 / 设为默认 / 移除\n"
                    + "· 缺哪个就在对应节点下载；下载来源与 GitHub Releases 对齐并校验");
            return;
        }
        refreshDetail();
    }

    private void setImplButtonsEnabled(boolean enabled) {
        downloadBtn.setDisable(!enabled);
        verifyBtn.setDisable(!enabled);
        defaultBtn.setDisable(!enabled);
        removeBtn.setDisable(!enabled);
    }

    private void refreshDetail() {
        if (selectedType == null) {
            return;
        }
        detailTitle.setText(selectedType.displayName() + "（" + selectedType.key + "）");
        String status = statusOf(selectedType);
        detailStatus.setText(status);
        String defaultKey = app.settings().getString("decompiler_type");
        defaultBtn.setText(selectedType.key.equals(defaultKey) ? "当前默认 ✓" : "设为默认");
        defaultBtn.setDisable(selectedType.key.equals(defaultKey));
    }

    private String statusOf(DecompilerType type) {
        StringBuilder sb = new StringBuilder();
        for (DecompilerManager.ToolEntry entry : DecompilerManager.scanTools()) {
            if (entry.type() == type) {
                sb.append("已安装：").append(entry.path())
                        .append(entry.version().isEmpty() ? "" : "（版本 " + entry.version() + "）")
                        .append('\n');
            }
        }
        String configured = app.settings().getString("decompiler_path");
        if (!configured.isEmpty() && Files.isRegularFile(Path.of(configured))
                && app.settings().getString("decompiler_type").equals(type.key)) {
            sb.append("当前默认：").append(configured);
        } else if (sb.isEmpty()) {
            sb.append("未安装。tools/ 目录下未发现文件名含 \"").append(type.key)
                    .append("\" 的 jar，可点击「下载」自动获取最新版。");
        }
        return sb.toString();
    }

    // ---------- 下载 ----------

    private void downloadSelected() {
        if (selectedType == null) {
            return;
        }
        DecompilerType type = selectedType;
        setBusy("正在查询 " + type.repo + " 最新版本…", -1);
        Task<DecompilerDownloader.ReleaseInfo> realTask =
                new Task<>() {
                    @Override
                    protected DecompilerDownloader.ReleaseInfo call()
                            throws DecompilerDownloader.DownloadException {
                        return DecompilerDownloader.fetchLatestRelease(type.repo, 15);
                    }
                };
        realTask.setOnSucceeded(e -> {
            DecompilerDownloader.ReleaseInfo release = realTask.getValue();
            DecompilerDownloader.Asset asset = pickAsset(release, type);
            if (asset == null) {
                setBusy("查询失败", 0);
                setStatus("最新版本中没有匹配的反编译器 jar，请用自定义 URL。");
                return;
            }
            boolean go = Dialogs.confirm("确认下载", release.tag() + "\n" + asset.name()
                    + "\n大小：" + DecompilerDownloader.humanSize(asset.size()) + "\n\n开始下载？");
            if (!go) {
                setBusy("已取消选择", 0);
                return;
            }
            downloadAsset(asset.url(), asset.name(), asset.size());
        });
        realTask.setOnFailed(e -> {
            setBusy("查询失败", 0);
            setStatus(realTask.getException().getMessage()
                    + "\n常见于无法访问 GitHub：可改用自定义 URL（镜像直链）或手动下载放入 tools/。");
        });
        new Thread(realTask, "query-release").start();
    }

    private DecompilerDownloader.Asset pickAsset(DecompilerDownloader.ReleaseInfo release,
                                                 DecompilerType type) {
        for (DecompilerDownloader.Asset asset : release.assets()) {
            if (asset.name().toLowerCase().startsWith(type.assetPrefix)) {
                return asset;
            }
        }
        return null;
    }

    private void downloadCustom() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) {
            setStatus("请先粘贴直链 URL。");
            return;
        }
        String name = url.substring(url.lastIndexOf('/') + 1);
        name = name.split("\\?")[0];
        if (name.isEmpty()) {
            name = "decompiler.jar";
        }
        if (!name.toLowerCase().endsWith(".jar")) {
            name += ".jar";
        }
        downloadAsset(url, name, null);
    }

    private void downloadAsset(String url, String filename, Long expectedSize) {
        try {
            Files.createDirectories(DecompilerManager.toolsDir());
        } catch (Exception exc) {
            Dialogs.error("错误", "无法创建 tools 目录：" + exc.getMessage());
            return;
        }
        Path finalPath = DecompilerManager.toolsDir().resolve(filename);
        Path partPath = finalPath.resolveSibling(filename + ".part");
        dlCancelled = false;
        setBusy("下载中：" + filename, expectedSize == null ? -2 : 0);

        Task<Path> task = new Task<>() {
            @Override
            protected Path call() throws DecompilerDownloader.DownloadException {
                DecompilerDownloader.download(url, partPath,
                        (done, total) -> {
                            if (total != null && total > 0) {
                                updateProgress(done, total);
                                updateMessage("下载中：" + DecompilerDownloader.humanSize(done)
                                        + " / " + DecompilerDownloader.humanSize(total));
                            } else {
                                updateProgress(-1, -1);
                                updateMessage("下载中：" + DecompilerDownloader.humanSize(done));
                            }
                        },
                        () -> dlCancelled, 30, expectedSize);
                return partPath;
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        dlLabel.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> {
            unbindProgress();
            verifyAndRegister(partPath, finalPath);
        });
        task.setOnFailed(e -> {
            unbindProgress();
            try {
                Files.deleteIfExists(partPath);
            } catch (Exception ignored) {
                // 清理失败忽略
            }
            String msg = String.valueOf(task.getException().getMessage());
            if (msg.contains("已取消")) {
                setBusy("已取消", 0);
            } else {
                setBusy("下载失败", 0);
                setStatus(msg + "\n可重试、改用自定义 URL，或手动下载后放入 tools/ 目录。");
            }
        });
        new Thread(task, "download-tool").start();
    }

    private void unbindProgress() {
        progress.progressProperty().unbind();
        dlLabel.textProperty().unbind();
    }

    /** 校验（可执行性）并登记到 tools/。 */
    private void verifyAndRegister(Path part, Path finalPath) {
        setBusy("校验中…", -1);
        String javaExe = app.getJavaQuick();
        Task<int[]> task = new Task<>() {
            @Override
            protected int[] call() throws Exception {
                if (javaExe == null) {
                    return new int[]{-1};
                }
                Process proc = new ProcessBuilder(javaExe, "-jar", part.toString(), "--version")
                        .redirectErrorStream(true)
                        .start();
                boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!done) {
                    proc.destroyForcibly();
                    return new int[]{-2};
                }
                return new int[]{proc.exitValue()};
            }
        };
        task.setOnSucceeded(e -> {
            int code = task.getValue()[0];
            boolean proceed = true;
            if (code > 0) {
                // 进程能启动就说明 jar 可执行（如 Vineflower 对 --version 回答
                // "error: no sources given"，属正常）
                proceed = true;
            } else if (code == -2) {
                proceed = false;
            }
            if (!proceed) {
                setBusy("校验失败", 0);
                setStatus("可执行性验证出错（java -jar 超时），请检查下载内容。");
                try {
                    Files.deleteIfExists(part);
                } catch (Exception ignored) {
                    // 清理失败忽略
                }
                return;
            }
            try {
                DecompilerDownloader.promote(part, finalPath);
            } catch (Exception exc) {
                setBusy("校验失败", 0);
                setStatus("无法保存到 tools/：" + exc.getMessage());
                return;
            }
            registerAsTool(finalPath);
            setBusy("完成", 1);
            setStatus("反编译器已就绪：" + finalPath);
            refreshDetail();
        });
        task.setOnFailed(e -> {
            unbindProgress();
            setBusy("校验失败", 0);
            setStatus("可执行性验证出错：" + task.getException().getMessage());
        });
        new Thread(task, "verify-tool").start();
    }

    private void registerAsTool(Path finalPath) {
        String lower = finalPath.getFileName().toString().toLowerCase();
        DecompilerType type = DecompilerType.fromFileName(lower);
        if (type == null) {
            type = DecompilerType.VINEFLOWER;
        }
        Settings settings = app.settings();
        try {
            settings.set("decompiler_path", finalPath.toString());
            settings.set("decompiler_type", type.key);
            settings.set("decompiler_version", "");
        } catch (Exception ignored) {
            // 设置写盘失败不阻断
        }
        // 已下载三种之一后立即生效
        app.invalidateDecompilerState();
    }

    // ---------- 校验 / 默认 / 移除 ----------

    private void verifySelected() {
        if (selectedType == null) {
            return;
        }
        DecompilerManager.ToolEntry found = findInstalled(selectedType);
        if (found == null) {
            Dialogs.warn("提示", "尚未安装 " + selectedType.displayName() + "，请先下载。");
            return;
        }
        setBusy("校验中…", -1);
        String path = found.path().toString();
        String javaExe = app.getJavaQuick();
        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                if (javaExe == null) {
                    return "（未检测到 java，跳过可执行性验证；文件存在且为 zip/jar 格式）";
                }
                Process proc = new ProcessBuilder(javaExe, "-jar", path, "--version")
                        .redirectErrorStream(true).start();
                boolean done = proc.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                String out = new String(proc.getInputStream().readAllBytes()).trim();
                if (!done) {
                    proc.destroyForcibly();
                    return "验证超时（java -jar --version 30 秒未返回）";
                }
                return "退出码 " + proc.exitValue()
                        + (out.isEmpty() ? "" : "，输出：\n" + out.lines().findFirst().orElse(""));
            }
        };
        task.setOnSucceeded(e -> {
            setBusy("校验完成", 1);
            setStatus(selectedType.displayName() + " 校验结果：\n" + task.getValue());
            refreshDetail();
        });
        task.setOnFailed(e -> {
            setBusy("校验失败", 0);
            setStatus("校验出错：" + task.getException().getMessage());
        });
        new Thread(task, "verify-selected").start();
    }

    private DecompilerManager.ToolEntry findInstalled(DecompilerType type) {
        List<DecompilerManager.ToolEntry> scan = DecompilerManager.scanTools();
        for (DecompilerManager.ToolEntry entry : scan) {
            if (entry.type() == type) {
                return entry;
            }
        }
        return null;
    }

    private void setDefault() {
        if (selectedType == null) {
            return;
        }
        DecompilerManager.ToolEntry found = findInstalled(selectedType);
        if (found == null) {
            Dialogs.warn("提示", "尚未安装 " + selectedType.displayName() + "，请先下载。");
            return;
        }
        Settings settings = app.settings();
        try {
            settings.set("decompiler_path", found.path().toString());
            settings.set("decompiler_type", selectedType.key);
        } catch (Exception ignored) {
            // 设置写盘失败不阻断
        }
        app.invalidateDecompilerState();
        setStatus("已将 " + selectedType.displayName() + " 设为默认反编译器。");
        refreshDetail();
    }

    private void removeSelected() {
        if (selectedType == null) {
            return;
        }
        DecompilerManager.ToolEntry found = findInstalled(selectedType);
        if (found == null) {
            Dialogs.warn("提示", "尚未安装 " + selectedType.displayName() + "。");
            return;
        }
        if (!Dialogs.confirm("确认移除",
                "从 tools/ 目录删除 " + selectedType.displayName() + " 的 jar？\n"
                        + found.path())) {
            return;
        }
        try {
            Files.deleteIfExists(found.path());
        } catch (Exception exc) {
            Dialogs.error("错误", "删除失败：" + exc.getMessage());
        }
        // 若默认反编译器被移除则清空配置
        Settings settings = app.settings();
        if (settings.getString("decompiler_path").equals(found.path().toString())) {
            try {
                settings.set("decompiler_path", "");
                settings.set("decompiler_type", "");
                settings.set("decompiler_version", "");
            } catch (Exception ignored) {
                // 写盘失败不阻断
            }
        }
        app.invalidateDecompilerState();
        setStatus(selectedType.displayName() + " 已移除。");
        refreshDetail();
    }

    // ---------- Java 环境 ----------

    private void refreshJava(boolean interactive) {
        javaInfo.setText("检测中…（正在尝试 PATH / JAVA_HOME / 启动器 runtime）");
        Task<JavaEnv.JavaResult> task = new Task<>() {
            @Override
            protected JavaEnv.JavaResult call() {
                return JavaEnv.findJava(app.settings());
            }
        };
        task.setOnSucceeded(e -> {
            JavaEnv.JavaResult result = task.getValue();
            if (result.path() == null) {
                StringBuilder msg = new StringBuilder("未找到可用的 java。");
                if (!result.tried().isEmpty()) {
                    msg.append("\n已尝试：");
                    for (String t : result.tried()) {
                        msg.append("\n  ").append(t);
                    }
                }
                javaInfo.setText(msg.toString());
                setStatus("请手动指定 java.exe 路径（Minecraft 自带运行时也可以）。");
                return;
            }
            javaInfo.setText("java：" + result.path() + "\n版本：" + result.version());
            app.setJavaInfo(result.path(), result.version());
            if (interactive) {
                setStatus("java 检测完成。");
            }
        });
        new Thread(task, "probe-java").start();
    }

    private void pickJava() {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("选择 java 可执行文件");
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("java.exe", "java.exe"),
                new javafx.stage.FileChooser.ExtensionFilter("所有文件", "*.*"));
        java.io.File file = chooser.showOpenDialog(this);
        if (file == null) {
            return;
        }
        String version = JavaEnv.probeVersion(file.getAbsolutePath());
        if (version == null) {
            Dialogs.error("错误", "该文件无法执行 java -version，请确认选择的是 java 可执行文件。");
            return;
        }
        try {
            app.settings().set("java_path", file.getAbsolutePath());
        } catch (Exception ignored) {
            // 写盘失败不阻断
        }
        javaInfo.setText("java：" + file.getAbsolutePath() + "\n版本：" + version);
        app.setJavaInfo(file.getAbsolutePath(), version);
        setStatus("java 路径已保存。");
    }

    // ---------- 状态辅助 ----------

    private void setBusy(String text, double value) {
        dlLabel.setText(text);
        if (value < -1) {
            progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        } else if (value < 0) {
            progress.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        } else {
            progress.setProgress(value);
        }
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }
}
