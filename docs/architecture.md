# JarTrans 架构说明

面向开发者的模块/数据流/关键不变量速览；面向用户的功能说明见根目录 README。

## 模块依赖（无环，自底向上）

```
core/io          AtomicFiles（原子写）、DeferredSaver（写盘防抖）     ← 叶子
core/json        自研 JSON 读写（字节级兼容 Python json.dump）
core/jar         JarReader（jar 快照读取）、JarPacker（重打包写出）
core/java        DecompilerManager + 三反编译引擎适配（进程内库调用）
core             Project（会话门面）/ ProjectArchive（解析快照）
                 TranslationStore / DictionaryManager / Dictionary
                 LangPack / ProgressStore / Settings / AppDirs
core/log         Log（System.Logger 门面）+ LogConfig（JUL 轮转文件）
ui               JavaFX 界面（MainApp 装配 + 各面板/窗口/控制器）
```

依赖方向严格单向：`io ← json ← jar/java ← core ← ui`。core 不依赖 JavaFX，
可在无界面环境下完整测试（ProjectTest/JarTest 等均为纯 core 测试）。

## 关键数据流

1. **打开 jar**：`ProjectIoActions`（FX 回调）→ 后台线程 `Project.openJar`
   → `ProjectArchive.load`：JarReader 全量读入 → 每类 `ClassFile.parse`
   （常量池 + 尾部字节）→ `collect` 提取字面量计数与 ldc 方法引用 →
   整体构建完成后**原子安装**到 Project（失败的类进 failures，不阻断）。
2. **编辑译文**：EditorPane 自动保存（700ms 停顿/切行/退出）→
   `TranslationStore.set`（manual 层覆盖 auto 层，同时精确失效 effective 缓存）
   → UndoRedo 记录单条 Hist；「全部替换」走 `Project.replaceTranslations`
   → 变更合并为一条 Batch 撤销记录（一步撤销）。
3. **导出 jar**：后台线程 `Project.exportJar` → 逐类 `ClassFile.rewrite`
   （追加式常量重定向 + 写后自校验）→ `JarPacker.save` 原样回写其余条目。
4. **持久化**：settings.json / class_status.json / dictionaries/*.json
   全部经 `AtomicFiles`（同目录临时文件 + 原子 move）写出；
   高频小写（设置项、类状态）经 `DeferredSaver` 300ms 合并，退出前 flush。

## 关键不变量（改动前请先读懂这些）

- **追加式重写**：`ClassFile.rewrite` 绝不修改既有常量池条目——只把
  CONSTANT_String 的索引改指向新追加的 Utf8，尾部字节原样拷贝；
  `rewrite` 内部必然执行 `verify`（重解析 + 逐条比对 + 字面量多重集校验），
  失败即抛异常。这是「不破坏字节码」的基石，任何重构不得绕过。
- **effective 缓存失效封闭性**：翻译的全部变更入口在 `TranslationStore`
  （包级私有）；`effective(cls)` 返回共享只读快照，任何变更方法必须失效
  对应缓存（见 TranslationStoreTest）。需要修改快照时先复制
  （参考 `Project.effectiveForExport`）。
- **快照式状态安装**：`ProjectArchive` 先完整构建再整体替换，Project 对外
  的 Map 视图全部 unmodifiable；解析过程中的异常不得留下半初始化状态。
- **FX 线程纪律**：core 的慢操作（解析/导出/哈希/搜索）不得在 FX 线程执行，
  统一经 `BackgroundOps`（互斥单飞 + 回调回 FX）；系统主题探测在
  `jartrans-theme-watch` 守护线程，结果经 `runLater` 应用。
- **写盘原子性**：所有 JSON 落盘必须走 `AtomicFiles`，禁止直接
  `Files.writeString` 到目标路径。
- **JSON 字节级兼容**：语言包/词典的输出格式（两空格缩进、非 ASCII 原样、
  按码点排序）与历史版本（含 Python 版）字节级一致，JsonTest/DictionaryTest 守护。

## 测试体系

- 纯 core：ClassFile/Bytecode/ModifiedUtf8/Json/Jar/Project/SkipText/
  Dictionary/ReplaceTranslations/TranslationStore/DeferredSaver/EnginesVersion
  （测试包镜像 main：`com.jartrans.core` 等）。
- UI（需 FX 工具包，FxSupport 共享单例）：UiSmoke（FXML 装配）、
  UiSearchReplace（查找/替换/快捷键/全局搜索/首选项/自动保存）、
  DictWindowsSync（多词典窗口同步与监听器泄漏）、MinThumbScrollBarSkin（真实布局）。
- 守护测试：SettingsKeysTest（设置键登记）、FxmlWiringTest（fx:id ↔ 控制器）、
  StateKeyConsistencyTest（状态键贯穿 UI/Theme/CSS）、ThemeTokenTest（调色板与
  CSS 令牌同步）、EnginesVersionTest（引擎版本与 version catalog 一致）。

## 构建与静态检查

- Gradle 9.8.2 wrapper（JDK 25 运行验证）；Spotless（Palantir Java Format）
  负责格式，Checkstyle 负责命名/导入/控制台输出/制表符禁令，二者接入 `check`。
- **Checkstyle 配置必须带 DOCTYPE**（`<!DOCTYPE module PUBLIC "-//Checkstyle//DTD
  Checkstyle Configuration 1.3//EN" "...">`）。缺失时 Gradle 只报一句
  `Unable to create Root Module`（真实原因是被 Ant 任务吞掉的 DTD 校验错误），
  排查时不要先怀疑构建环境——用 `checkstyle-<ver>.jar` 直接跑 CLI 可看到完整堆栈。
- 代理：Gradle 下载按用户级 `~/.gradle/gradle.properties` 的 `systemProp.*` 配置
  （本机指向系统代理），仓库内的 `gradle.properties` 只放构建参数。

## 已知技术债（按优先级）

1. MainApp 仍约 1120 行（装配/类树/统计/快捷键/转发）；右键菜单与词典协调
   已抽出（TreeContextMenu / DictCoordinator / ProjectIoActions / BackgroundOps），
   继续拆分可从「统计/图例/首选项联动」入手。
2. `javafx` Gradle 插件（0.1.0，latest）在 Gradle 9.8 会触发
   `Configuration.setVisible` 弃用警告（Gradle 11 移除）；等待插件更新或
   改为手写 JavaFX 依赖/模块配置。
3. ClassFile/CpEntry 的字段已收为包级可见并经访问器对外（约定只读），
   完整的不可变只读视图（防御性拷贝）待做——当前热路径按约定保证只读。
4. DecompilerManager 为静态门面（缓存根/锁全局），不可注入，多实例场景受限。
5. 界面文案未资源化（中文硬编码，状态/筛选文案已集中于 ClassStates）。

## 主题结构

- `base.css`：全部结构规则（约 600 行），**禁止出现颜色字面量**；
  颜色只引用 `-jr-*` 令牌。
- `light.css` / `dark.css`：仅 `.root` 调色板令牌（各约 40 行），
  两套令牌集合必须完全一致（ThemeTokenTest 守护，含「base 引用令牌必须已定义」）。
- 结构差异（如按钮 hover 的 derive 方向）通过新增差异令牌吸收，
  不允许在两个主题文件里各写一份结构规则。
