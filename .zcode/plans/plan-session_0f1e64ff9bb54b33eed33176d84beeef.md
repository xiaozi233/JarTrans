# JarTrans 工程级重构方案

## 总原则
- **行为保持**（除明确列出的改进点），settings.json / class_status.json / 词典 / 语言包的数据格式与字节级兼容语义**不变**（现有测试继续守护）。
- 分 6 个阶段推进，每阶段 `./gradlew test` 绿灯后才进下一阶段，按 conventional commits 分阶段提交。
- 保留已验证的优秀设计：ClassFile 追加式常量重写+写后自校验、ProjectArchive 快照式安装、AtomicFiles 原子写、AppWindow/ClassTreeModel/TableColumns/EditHistory、DecompileController generation 守卫。

---

## 阶段 0：基线
跑 `./gradlew test` 与 `shadowJar` 确认当前全绿，作为回归基线。

## 阶段 1：基础设施 —— 日志、异常体系、依赖管理（P0）
1. **日志**：新增 `core/log/Log.java`（`System.Logger` 门面，零新依赖，core 保持纯 JDK）；Main 启动装配 JUL 轮转文件日志（`logs/jartrans.log`，5×1MB）；替换 core 12 处、ui 7 处静默 `ignored` 为 warn/error 日志；`Dialogs.error` 调用点同步记日志 —— 现场排障不再依赖用户截图。
2. **异常体系**：5 个自定义异常补 cause 构造器与 serialVersionUID；Project/ProjectArchive 不再压扁为 IOException（保留类型+链）；`Bytecode.parseStructure` 保留原始消息；`DictionaryManager` 名称校验从 IllegalArgumentException 统一为受检域异常。
3. **Json 契约修复**：`parseNumber("1e")`/`parseString("\uZZZZ")` 现抛 NumberFormatException 逃逸，违反类注释契约 → 改抛 IOException，补畸形输入测试。
4. **词典损坏保护**：`Dictionary.load` 解析失败目前返回空词典，后续 save 会**静默覆盖损坏文件** → 改为保留 `.corrupt` 备份 + error 日志 + 词典标记加载失败，UI 提示。
5. **依赖/版本管理**：建 `gradle/libs.versions.toml` version catalog；run.bat 改通配 `jartrans-*.jar`（消除与 build.gradle.kts 的版本双硬编码）；`DecompilerManager.engines()` 版本串加与 catalog 的一致性守护测试。
6. **清理**：删除死代码 DecompilerManagerDialog；settings.json 4 个遗留死键（java_path 等）在保存时迁移剔除。

## 阶段 2：core 性能（P0，最大算法收益）
1. **TranslationStore.effective 缓存**（最严重问题）：每次调用合并分配两个 Map，被 classState/stats/classStats/search/UI 树刷新按「每类」反复调用 → 惰性缓存 + 精确失效（单类变更失效单类，clear/applyPack 全失效）。万级类 jar 的刷新从秒级到毫秒级。补失效正确性测试。
2. **Project.search / jumpSourceToTable** 复用缓存，消除每键全量重算。
3. **Settings**：DEFAULTS 改静态不可变常量（现缺键时每次重建 12 键 Map）；设置键常量化，替换散布 6 个文件的裸字符串（SettingsKeysTest 守护改为对常量校验）。
4. **写盘防抖**：Settings.set / Project.saveProgress 现在每次点击同步读改写整个 JSON → 脏标记 + 短延迟合并写 + 退出 flush（原子写不变）；DictionaryManager 启动不再无条件重写 index.json。
5. **加载链路拷贝削减**：类字节现被复制约 4-5 次（JarReader 全解压常驻 + readEntry clone + ClassFile.parse 再拷贝）→ 内部路径免 clone、parse 原地解析；对外 API 仍防御拷贝。
6. **微观优化**：Bytecode.stringUsage 的 `List.contains` O(n²) → LinkedHashSet、装箱 OPCODES 表 → String[256]；Dictionary.search 预计算小写索引；ClassFile.serialize 消除重复 ModifiedUTF8 编码。
7. DecompilerManager：cleanupOnExit 时清理 OUTPUT_LOCKS；ModifiedUTF8.encode 删除不可达的代理对分支并补真实增补字符测试。

## 阶段 3：UI 响应性（P0）
1. **Theme 探测移出 FX 线程**（UI 最严重问题）：system 模式下每 1.5s 在 FX 线程 spawn `reg query` 并最长阻塞 5s → daemon ScheduledExecutor 后台探测，变化经 runLater 应用。
2. **新增 ui/BackgroundOps**：泛化 openJar 已有的 Task 模式（进度提示/忙碌状态/进行中禁用相关动作/错误统一到 Dialogs+Log）。
3. **后台化重操作**：exportJar（含全 jar SHA-256+全类 rewrite+重压缩）、exportPack、applyLanguagePack、fillFromDictionary、SearchWindow.doSearch —— 大 jar 导出不再冻结 UI。
4. **表格/渲染**：逐条 add → setAll；状态色每行 `Color.web` 重解析 → 按 status 缓存；hitLines contains O(n) → Set；rowFactory 不在 updateItem 重挂 handler。
5. FindReplaceBar 每字符全量过滤 → 加 120ms 防抖。

## 阶段 4：架构去重与封装（P1，可读性/可维护性）
1. **替换逻辑下沉 core**：`Project.replaceTranslations`（skipped 不可改、before==after 跳过规则单点化）+ 全部替换合并为**单条撤销记录**（修 replace-all 要按 N 次 Ctrl+Z 的问题）；EditorPane/SearchWindow/MainApp 三处实现归一。
2. **词典切换归一**：`MainApp.switchDictionary(name)` 单点实现，三处复制的四步切换逻辑收敛；散落的 FileChooser 统一走 askOpenFile/askSaveFile。
3. **状态清单单一来源**：图例/统计顺序全部经 ClassStates，消除 `List.of("todo",...)` 散布；条目状态文案并入。
4. **MainApp 拆分**（1321 行 → 目标 <800）：抽 ProjectIoController（jar/语言包 IO 动作+后台任务）、TreeContextMenu（11 项右键菜单构建）、DictCoordinator（词典同步与监听分发）；getter 服务定位器随拆分收敛。
5. **EditorPane 增量刷新**：单行编辑/批量操作后 `updateRow(orig)` 局部更新（现任何单行变更触发全类树+统计+表格全量重建 O(全类+全行)）；5 个布尔守卫整理为显式命名状态机（行为不变）。
6. **封装**：ClassFile.data/entries/tail、CpEntry.raw、EntryInfo.data、DictionaryManager 路径字段全部 private + 访问器（core 与测试同步更新，编译期暴露）。
7. **CSS 主题去重**（阶段内时间允许则做）：结构规则抽 base.css，light/dark.css 收敛为 .root 调色板令牌（JavaFX looked-up colors），ThemeTokenTest 扩展为双调色板全令牌校验 —— 消除 1260 行复制 + 第三份 Java 调色板。

## 阶段 5：工程化（P2）
1. **Spotless + Palantir Java Format** 全量格式化：**独立提交** + `.git-blame-ignore-revs` + `.editorconfig`（用户已确认）。
2. **Checkstyle** 保守规则集（命名/导入/禁 System.out/printStackTrace 于 main/类级 javadoc）接入 `check`。
3. **CI**：`.github/workflows/ci.yml`，windows-latest + JDK 25（foojay），`gradlew test` + `shadowJar`，gradle 缓存（用户已确认；Windows runner 自带显示环境，UI 测试无需 monocle）。
4. **文档**：README 增开发者章节（构建/测试/风格/提交规范）；`docs/architecture.md`（模块依赖图、数据流、关键不变量：追加式重定向+写后校验、快照安装、原子写）；`CHANGELOG.md`（Keep a Changelog 格式）。
5. **补测试盲区**：DictionaryManager（CRUD/损坏保护）、TranslationStore（缓存失效）、ProgressStore（防抖 flush）、Json 畸形输入、Project.replaceTranslations；测试包结构镜像 main（建 core 测试包）。

## 明确不做（控制风险/范围）
- 不引入 slf4j 等外部日志框架（core 保持纯 JDK）。
- 不改运行时数据目录位置（保持「程序所在目录」既有语义，兼容老用户数据）。
- 不做 jpackage 原生打包、不做 i18n 资源化（项目语言为中文，仅做常量集中）—— 在 README 标注为后续方向。

---

## 预期收益
- **性能**：刷新/统计/搜索从每次全量重算降为缓存查询（万级类 jar 编辑后刷新秒级→毫秒级）；大 jar 导出/导入不冻结 UI；主题轮询最坏 5s 卡顿归零；加载期瞬时内存约减半；写盘次数大幅下降。
- **正确性**：Json 契约修复、词典损坏不再静默覆盖、异常链完整。
- **可维护性**：MainApp 瘦身约 40%、三处重复归一 ×2、键常量化、日志可排障、CI 回归网、格式/静态检查、版本单源、CHANGELOG+架构文档。

## 潜在风险与缓解
- **缓存失效遗漏** → 所有翻译变更入口已在 TranslationStore（包级私有）内，失效点封闭；补专门测试。
- **后台化并发竞态** → BackgroundOps 单飞守卫（进行中禁用相关动作）+ 回调统一回 FX 线程。
- **全量 reformat 淹没 git 历史** → 独立提交 + .git-blame-ignore-revs。
- **ClassFile 封装改动波及面** → 编译期暴露，随改随更测试。
- **CSS 重构视觉回归** → ThemeTokenTest 扩展 + 浅/深/系统三模式手工冒烟；此项若时间不足可整项后延，不影响其他收益。

## 兼容性影响
- **用户数据**：全部格式不变；settings.json 保存时剔除 4 个死键（旧版本读回无害）。
- **产物**：fat jar 名称与 Main-Class 不变；run.bat 通配后与任意版本号兼容。
- **行为改进（可感知）**：导出/导入有进度不冻结；全部替换一步撤销；损坏词典保留备份不被覆盖；其余交互不变。
- **开发者**：JDK 25 不变；新增 `gradlew check`；推送自动跑 CI。

## 验证
每阶段 `./gradlew test`；阶段 5 后 `./gradlew check`（含 spotless+checkstyle）+ `shadowJar` + 启动冒烟（开 jar→编辑→导出→三主题切换）。