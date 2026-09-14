# JarTrans — JAR 汉化工具

直接改写 jar 内 class 文件字符串常量的桌面汉化工具。JavaFX 界面，核心引擎仅用 JDK 标准库实现，不依赖任何字节码框架。

## 原理

不重编译、不改字节码结构：只把常量池中需要翻译的字符串引用**重定向**到新追加的常量条目，原有常量与字节码结构一字节不动。写回前自动做结构校验，确保 class 文件可被 JVM 正常加载。

## 功能

- **jar 直读直写**：打开 jar → 逐类列出可翻译字符串 → 编辑译文 → 导出汉化 jar
- **语言包**：导入/导出 JSON 语言包（含目标 jar 的 SHA-256 校验，不匹配会提示），失效条目可一键回收进词典
- **多词典**：词典管理（新建/重命名/删除/导入/导出/切换），保存译文可自动记入词典，支持词典一键填充未翻译条目
- **类状态管理**：未开始 / 翻译中 / 已完成 / 已忽略，按状态过滤、批量标记、进度统计；左栏图例可一键显示/隐藏
- **源码对照**：内置字节码反汇编视图；**Vineflower / CFR / Procyon 三种反编译器均随应用内置**，在应用进程内按需反编译单个类（秒级、全程离线，可在首选项切换引擎；进行中可取消），源码与翻译表格双向跳转
- **全局搜索（Ctrl+Alt+F）**：跨类搜索原字符串/译文；工具栏输入即反馈匹配数，回车打开搜索窗；支持批量替换选中/全部匹配译文
- **类内查找与替换（Ctrl+F / Ctrl+R）**：当前类内按原文/译文实时过滤，Enter/Shift+Enter 跳匹配；Ctrl+R 进入替换模式，可替换当前或当前过滤结果（写入撤销历史）
- **译文自动保存**：输入停顿约 0.7 秒、切换行/类或退出时自动写入，无需手动保存
- **双击跳源码**：默认双击翻译行直达源码页（可在首选项关闭）
- **主题**：浅色 / 深色 / 跟随系统
- **首选项（Ctrl+,）**：集中管理主题、图例显示开关、隐藏空类、状态筛选、只看未翻译、保存时记入词典、译文自动保存、双击跳源码、**反编译器引擎选择**，并提供 7 项可自定义快捷键

## 运行环境

- JDK 25（构建与运行；Vineflower / CFR / Procyon 反编译器已内置，随应用进程直接反编译，无需另装运行时或下载工具）

## 构建与运行

```bash
# 运行
./gradlew run

# 测试
./gradlew test

# 打包可执行 fat jar：build/libs/jartrans-1.0.0.jar（java -jar 直接运行）
./gradlew shadowJar
```

Windows 下也可直接双击 `run.bat`。

## 目录结构

```
src/main/java/com/jartrans/
├── Main.java            # 启动入口
├── core/                # 核心引擎（纯 JDK 标准库）
│   ├── ClassFile.java   #   class 解析与追加式常量重写
│   ├── Bytecode.java    #   字节码指令扫描（字符串引用定位）
│   ├── Project.java     #   工程状态：翻译/词典/类状态/语言包
│   ├── jar/             #   jar 读取与重打包
│   ├── java/            #   反编译器：内置释放/进程内执行/产物与缓存管理
│   └── json/            #   自研 JSON 读写
└── ui/                  # JavaFX 界面（FXML + CSS 双主题）
    ├── MainApp.java             # 主窗口：FXML 接线、菜单/快捷键、动作编排
    ├── EditorPane.java          # 翻译表格 + 译文编辑区（查找替换栏见 FindReplaceBar）
    ├── SourcePanel.java         # 源码/字节码视图
    ├── DecompileController.java # 按需单类反编译、产物缓存与取消
    ├── UndoRedo.java            # 撤销/重做（译文 · 不翻译 · 类状态）
    ├── ClassStates.java         # 类状态文案与筛选定义（唯一来源）
    ├── TableColumns.java        # 表格列构造（消除列样板）
    └── AppWindow.java           # 独立窗口基类（尺寸/模态/主题挂载统一）
```

运行时数据（`settings.json`、`class_status.json`、`dictionaries/`）默认落在程序所在目录；反编译缓存位于系统临时目录，退出时自动清理。

## 内置组件

- [Vineflower](https://github.com/Vineflower/Vineflower)（Apache-2.0）
- [CFR](https://github.com/leibnitz27/CFR)（MIT）
- [Procyon](https://github.com/mstrobel/procyon)（Apache-2.0）

三者均为反编译器，打包于应用资源中，首次使用自动释放到本地，无需联网下载。

## 协议

[MIT](LICENSE)
