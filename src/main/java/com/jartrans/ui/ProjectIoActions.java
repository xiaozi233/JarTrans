package com.jartrans.ui;

import com.jartrans.core.LangPack;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javafx.stage.FileChooser;

/**
 * jar/语言包相关的文件 IO 动作（打开/导出 jar、导入/导出语言包、词典批量填充）。
 *
 * <p>从 MainApp 抽出的编排层：负责文件选择框、后台执行（经主窗的
 * {@link BackgroundOps}）与完成后的界面刷新回调；业务规则全部在 core。</p>
 */
final class ProjectIoActions {

    private final MainApp app;

    ProjectIoActions(MainApp app) {
        this.app = app;
    }

    void openJar() {
        Path path = askOpenFile(
                "选择 jar 文件",
                new FileChooser.ExtensionFilter("Jar 文件", "*.jar"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        if (path == null) {
            return;
        }
        app.bgOps()
                .run(
                        "打开 jar",
                        "正在解析 jar …",
                        () -> {
                            app.project().openJar(path);
                            return true;
                        },
                        ok -> {
                            app.rebuildTree();
                            app.editor().showClass(null);
                            // 新 jar：旧 jar 的反编译产物/索引全部失效
                            app.invalidateDecompilerState();
                            app.updateStats();
                            String msg = "已打开 " + app.project().jarName() + "：共 "
                                    + app.project().classOrder().size() + " 个类";
                            if (!app.project().failed().isEmpty()) {
                                msg += "（" + app.project().failed().size() + " 个类解析失败，已跳过）";
                            }
                            app.setStatus(msg);
                        });
    }

    void exportJar() {
        if (!app.requireJar()) {
            return;
        }
        Path out = askSaveFile(
                "导出汉化 Jar",
                "translated_" + app.project().jarName(),
                new FileChooser.ExtensionFilter("Jar 文件", "*.jar"));
        if (out == null) {
            return;
        }
        // 含全 jar SHA-256、逐类重写（内含写后校验）与整档重压缩，大 jar 耗时明显，必须后台执行
        app.bgOps().run("导出汉化 Jar", "正在导出汉化 jar …", () -> app.project().exportJar(out, true), count -> {
            Dialogs.info("完成", "已写入 " + count + " 个汉化的类：\n" + out);
            app.setStatus("已导出 " + count + " 个汉化的类");
        });
    }

    void importPack() {
        if (!app.requireJar()) {
            return;
        }
        Path file = askOpenFile(
                "选择语言包",
                new FileChooser.ExtensionFilter("JSON 文件", "*.json"),
                new FileChooser.ExtensionFilter("所有文件", "*.*"));
        if (file == null) {
            return;
        }
        Map<String, Object> pack;
        try {
            pack = LangPack.readPack(file);
        } catch (LangPack.LangPackException exc) {
            Dialogs.error("错误", exc.getMessage());
            return;
        }
        String expected = String.valueOf(pack.getOrDefault("target_jar_sha256", ""));
        app.bgOps()
                .run(
                        "应用语言包",
                        "正在校验并应用语言包 …",
                        () -> {
                            // SHA-256 需全量读 jar，与解析分开两段：先算摘要，确认后再应用
                            return expected.isEmpty()
                                    || expected.equals(app.project().jarSha256());
                        },
                        shaMatched -> {
                            if (!shaMatched) {
                                if (!Dialogs.confirm("警告", "语言包的 SHA-256 与当前 jar 不匹配（可能不是同一版本）。\n仍要继续应用吗？")) {
                                    app.setStatus("已取消应用语言包");
                                    return;
                                }
                            }
                            app.bgOps()
                                    .run(
                                            "应用语言包",
                                            "正在应用语言包 …",
                                            () -> app.project().applyLanguagePack(pack),
                                            applied -> {
                                                app.refreshAfterModelChange(true);
                                                if (!app.project().lastMissing().isEmpty()) {
                                                    showMissing();
                                                    app.setStatus("语言包已应用：成功 " + applied + " 条，失效 "
                                                            + app.project()
                                                                    .lastMissing()
                                                                    .size() + " 条");
                                                } else {
                                                    Dialogs.info("完成", "语言包已应用：" + applied + " 条翻译");
                                                }
                                            });
                        });
    }

    void exportPack() {
        if (!app.requireJar()) {
            return;
        }
        Path file = askSaveFile("导出语言包", "langpack.json", new FileChooser.ExtensionFilter("JSON 文件", "*.json"));
        if (file == null) {
            return;
        }
        // 作者取首选项默认值（settings.pack_author），不再每次弹窗询问
        String author = app.settings()
                .getString(com.jartrans.core.Settings.Keys.PACK_AUTHOR)
                .trim();
        app.bgOps()
                .run(
                        "导出语言包",
                        "正在导出语言包 …",
                        () -> {
                            app.project().exportPack(file, author);
                            return app.project().classStatus().size();
                        },
                        classStatusCount -> {
                            Dialogs.info(
                                    "完成",
                                    "语言包已导出到：\n" + file
                                            + "\n作者：" + (author.isEmpty() ? "（未设置，可在首选项中填写）" : author)
                                            + "\n（含 " + classStatusCount + " 个类的手动状态标记）");
                            app.setStatus("语言包已导出");
                        });
    }

    void fillFromDictionary() {
        if (!app.requireJar()) {
            return;
        }
        app.bgOps().run("词典批量填充", "正在按词典批量填充 …", () -> app.project().fillFromDictionary(), count -> {
            app.refreshAfterModelChange(true);
            Dialogs.info("完成", "词典自动填充了 " + count + " 条未翻译字符串（标记为\"自动\"）。");
        });
    }

    void fillClassFromDictionary() {
        if (!app.requireJar()) {
            return;
        }
        String cls = app.editor().currentClass();
        if (cls == null) {
            Dialogs.info("提示", "请先在左侧选择一个类。");
            return;
        }
        app.bgOps().run("词典填充", "正在按词典填充当前类 …", () -> app.project().fillFromDictionary(List.of(cls)), count -> {
            app.refreshAfterModelChange(true);
            app.setStatus("「" + cls + "」：词典填充了 " + count + " 条");
        });
    }

    /** 展示导入语言包时的失效条目列表（独立窗口）。 */
    void showMissing() {
        new MissingEntriesWindow(app).show();
    }

    /** 打开文件选择框（可多组扩展名过滤）；取消返回 null。 */
    private Path askOpenFile(String title, FileChooser.ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.getExtensionFilters().addAll(filters);
        java.io.File file = chooser.showOpenDialog(app.stage());
        return file == null ? null : file.toPath();
    }

    /** 保存文件选择框；取消返回 null。 */
    private Path askSaveFile(String title, String initialName, FileChooser.ExtensionFilter... filters) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (initialName != null) {
            chooser.setInitialFileName(initialName);
        }
        chooser.getExtensionFilters().addAll(filters);
        java.io.File file = chooser.showSaveDialog(app.stage());
        return file == null ? null : file.toPath();
    }
}
