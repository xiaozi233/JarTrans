# 更新日志

本项目的所有显著变更记录于此。格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [未发布] — 2026-10-24 UI 扁平化与操作引导

### 变更（仅视觉与引导，功能不变）

- 扁平化设计系统：去弹出层投影、统一 4px（控件）/6px（容器）圆角、
  去掉表格斑马纹；新增语义色（危险操作）与排版工具类
  （`.section-title/.hint/.empty-title/.empty-hint`）。
- 工具栏：次要动作改为「幽灵」按钮（无边框、hover 出底色），仅「打开 Jar」
  保留主色填充；每个动作补结果说明 tooltip；未打开 jar 时导出/导入按钮置灰。
- 空状态：类树、译文表格、源码列表、搜索窗口、词典窗口、失效条目统一
  显示「标题 + 下一步提示」，替代空白区域。
- 反馈：后台导出/导入期间状态栏进度条显示不确定态动画；搜索菜单项的
  快捷键提示随用户自定义实时更新；危险操作（删除词典/词条）使用危险样式。
- 可用性：过滤框、词典搜索、源码搜索、译文编辑区补 placeholder；
  主窗最小宽度 980 → 1100（工具栏改为样式化 HBox，失去溢出菜单）。
- 修复：深色主题下工具栏按钮文字为黑色不可读（JavaFX ToolBar 皮肤容器的
  自定义属性查找边界，改用 HBox 承载工具栏解决）。
- 文档：新增 `docs/ui-design.md`（配色/留白/字体层级/组件/操作改进说明）。

## [未发布] — 2026-10-24 工程级重构

### 新增

- 日志系统：`core/log`（System.Logger 门面，零第三方依赖）+ 程序目录
  `logs/jartrans.log` 轮转文件（5×1MB）；后台静默失败全部留痕，不再依赖用户截图排障。
- 依赖版本唯一来源 `gradle/libs.versions.toml`（version catalog）；
  `EnginesVersionTest` 守护反编译器展示版本与实际依赖一致。
- GitHub Actions CI（windows-latest + JDK 25）：推送即跑
  `gradlew check`（测试 + 格式校验 + 卫生门禁）与 `shadowJar` 打包。
- 开发者文档：`docs/architecture.md`（模块依赖/数据流/关键不变量/技术债）。
- 代码风格门禁：Spotless + Palantir Java Format（`gradlew spotlessApply`）、
  `.editorconfig`、Checkstyle 保守规则集（main 源码：命名/导入/禁控制台输出/
  禁制表符，接入 `gradlew check`）。
- 构建工具链升级：Gradle wrapper 9.1.0 → 9.8.2（JDK 25 下验证），
  shadow 插件 9.4.0 → 9.6.1。
- 测试补盲：TranslationStore 缓存失效、DeferredSaver、replaceTranslations
  规则、Json 畸形输入契约、词典损坏备份、EditHistory 批量撤销。

### 变更（行为改进，用户可感知）

- 大 jar 的导出/导入/词典批量填充改为后台执行并带状态提示，不再冻结界面；
  进行中的重操作互斥（单飞）。
- 「全部替换」（类内与全局搜索）现在可一步撤销（此前需按 N 次 Ctrl+Z）。
- 全局搜索的批量替换真正跳过「不翻译」行（此前提示与实现不符）。
- 「跟随系统」主题探测移至后台线程，不再周期性卡顿（最坏 5 秒冻结）。
- 设置项/翻译进度的写盘合并（300ms 防抖），连续标记不再逐次读改写。
- 词典文件损坏时保留 `.corrupt` 备份并记录日志，不再被空词典静默覆盖。
- settings.json 保存时剔除 4 个外部进程反编译器时代的遗留死键。

### 性能

- `effective(cls)` 惰性缓存 + 精确失效：类树刷新/统计/搜索从「每次全类
  重算合并」降为快照查询，万级类的 jar 编辑译文后的刷新由秒级到毫秒级。
- 类字节加载链路削减两次整块拷贝；ClassFile 序列化复用已编码字节。
- 词典搜索预计算小写索引；字节码扫描的 O(n²) 方法名去重改集合；
  表格批量刷新 setAll；状态色解析按调色板缓存；查找栏过滤防抖。

### 修复

- 自研 JSON 解析器契约：畸形数字（`1e`、`1.2.3`）与 `\uZZZZ` 转义现在抛
  IOException 而非让 NumberFormatException 逃逸。
- DeferredSaver 唤醒丢失导致的潜在死锁（flush 与后台写盘并发）。
- run.bat 不再与 build.gradle.kts 双重维护版本号（按通配定位最新 jar）。

### 内部

- 异常体系统一：全部自定义异常补 cause 构造器与 serialVersionUID；
  openJar 保留 JarFileException 类型；词典操作统一受检 DictionaryException。
- 词典切换协议单点化（MainApp.switchDictionary），三处复制实现收敛；
  替换逻辑下沉 core（Project.replaceTranslations）。
- 设置键常量化（Settings.Keys）；状态文案/顺序唯一来源（ClassStates）。
- MainApp 拆出 ProjectIoActions、BackgroundOps、TreeContextMenu、DictCoordinator
  （1321 → 约 1120 行）。
- CSS 双主题去重：结构规则收敛到共用 base.css（禁止颜色字面量），
  light/dark.css 仅保留调色板令牌（各约 40 行，合计 1265 → 688 行）；
  ThemeTokenTest 升级为令牌集合一致性 + base 引用完整性守护。
- ClassFile / CpEntry / EntryInfo 字段收为包级可见并补公共访问器，
  对外不再暴露可变字段；测试同步改用访问器。
- 死代码清理：DecompilerManagerDialog（已被首选项取代）。

## [1.0.0]

首个正式版本：jar 直读直写汉化、语言包、多词典、类状态、内置三反编译器、
全局/类内搜索、主题、可自定义快捷键。
