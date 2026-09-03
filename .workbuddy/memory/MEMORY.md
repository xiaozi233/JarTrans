# JarTrans 项目长期笔记

## 项目身份
- Java 版 JarTrans（jar 汉化工具）重构工程，源自 `D:\GAME\Minecraft\programming\idk` 下同名 Python 项目；任务书见该目录 `JAVA_REWRITE_PROMPT.md`。
- 构建：`JAVA_HOME="C:/Program Files/Zulu/zulu-25" ./gradlew test|run`（本机默认 java 是 21，需显式指到 zulu-25）。
- 关键等价性约束（改动前务必核对）：language pack JSON schema（format_version/target_jar/_sha256/language/author/entries/class_status，可新增可选字段 skip_texts 原字符串列表）、settings.json 字段、词典 JSON、类状态指纹键 `jar名|大小|类数`（per-jar 块可含 `__skip_texts` List）、class 重写字节一致。
- 「不翻译」约定：文本级名单=skipTexts 集合（存 class_status.json per-jar 块 `__skip_texts`，随语言包 skip_texts 字段携带）；类级=classStatus 手动 ignore（已忽略=整类不翻译）。exportJar/exportPack/fillFromDictionary/applyLanguagePack 四处都必须尊重这两者。

## 约定/事实
- 核心引擎只用 JDK 标准库，不得引入 ASM/第三方；JSON 用自研 `core/json/Json.java`（Python json.dump 风格：indent=2、ensure_ascii=False）。
- 反编译器类型键：vineflower/cfr/procyon（settings.decompiler_type），repo：Vineflower/Vineflower、leibnitz27/CFR、mstrobel/procyon。
- 缓存目录：%TEMP%/jartrans_decomp/<sha16>/.ok；退出时 UI 清理。
- AppDirs.baseDir：优先 -Djartrans.dir，否则 user.dir（Python 版是程序目录）。
- 本机网络：存在 127.0.0.1:2529 HTTPS 代理 + TLS 中间人；curl 加 `--ssl-no-revoke`；gradlew 首次下载在沙箱会 PKIX 失败（已用本地预置分发包规避，勿删 ~/.gradle/wrapper/dists 下对应目录）。
- UI 主题：令牌在 light.css/dark.css 的 `.root`（非 .root-pane，弹窗同源生效）；**改主题色必须同步 Theme.java 调色板**；深色主题必须在 .root 重定义 `-fx-base/-fx-text-base-color` 等 Modena 基础色，否则未覆写控件（表格单元格、CheckBox 文字）会按浅色基色推导成深色文字不可读。
- UI 类状态颜色：**不要用代码 setTextFill/setFill 上色**（CSS 选中/重建脉冲会覆盖成默认色，造成点字不同步、点击变色）；改为单元格挂 `cell-state-*` 状态类 + `.state-dot`(Circle) 走 CSS，选中态需显式写 `:filled:selected` 规则保持同色。状态色令牌 `-jr-state-*` 在 light/dark.css 与 Theme.java `state_*` 共 5 处需同步。
- UI 视觉验证法：临时 JUnit 截图/颜色探针测试（Platform.startup runnable 即 FX 线程；截图用 Scene.snapshot+javafx.swing 存 PNG；颜色断言用 lookupAll+TreeCell.getTextFill；数据用 TestClasses.writeSampleJar），跑完删除并还原 build.gradle.kts 的 javafx.swing。VBox/面板内程序化 select() 会误触监听，需要 syncing 守卫。
