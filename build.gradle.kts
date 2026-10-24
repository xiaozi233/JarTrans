plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    // 产出可直接 java -jar 运行的可执行 fat jar
    id("com.gradleup.shadow") version "9.6.1"
    // 统一代码风格：gradlew spotlessApply 全量格式化，gradlew spotlessCheck 校验
    id("com.diffplug.spotless") version "7.0.4"
    // 静态检查（保守规则集；风格细节由 Spotless 负责）
    checkstyle
}

group = "com.jartrans"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

javafx {
    version = "21.0.8"
    modules = listOf("javafx.controls", "javafx.fxml")
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    // Recaf-style: decompilers are regular runtime libraries and are invoked in-process.
    // 版本号唯一来源为 gradle/libs.versions.toml；EnginesVersionTest 守护其与
    // DecompilerManager.engines() 展示版本一致。
    implementation(libs.vineflower)
    implementation(libs.cfr)
    implementation(libs.procyon)
}

application {
    mainClass = "com.jartrans.Main"
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    // JDK 25 对 JavaFX 原生库访问的提示（与 run.bat 的启动参数一致）
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        // CI 上 UI 测试失败时输出完整栈（含 JavaFX 内部帧），便于定位
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// 可执行 fat jar：build/libs/jartrans-<version>.jar
// （内置 JavaFX 控件/FXML 与本平台原生库，Main-Class = com.jartrans.Main）
tasks.jar {
    // 普通 jar（无依赖、无主类）不产生文件，避免与可执行包混淆
    enabled = false
}

// 运行与打包都走 fat jar / gradlew run，application 插件的
// 启动脚本与 tar/zip 分发产物不需要，禁用以免与 shadowJar 产物冲突
listOf("startScripts", "distTar", "distZip", "installDist").forEach { name ->
    tasks.named(name) {
        enabled = false
    }
}

tasks.shadowJar {
    archiveClassifier.set("")          // 直接命名为 jartrans-<version>.jar
    mergeServiceFiles()
    exclude("module-info.class")
    exclude("META-INF/INDEX.LIST", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    manifest {
        attributes["Main-Class"] = "com.jartrans.Main"
        attributes["Implementation-Title"] = "JarTrans"
        attributes["Implementation-Version"] = project.version
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        // Palantir Java Format：唯一风格决策来源（4 空格缩进、120 列、确定性 import 排序）
        palantirJavaFormat("2.102.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target(".gitignore", "*.md", "config/**/*.xml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

checkstyle {
    toolVersion = "14.3.0"
    configFile = file("config/checkstyle/checkstyle.xml")
}

// 测试代码允许诊断输出与既有宽松写法，静态检查只约束 main
tasks.checkstyleTest {
    enabled = false
}

// main 的命名/导入/禁控制台输出等规则由 checkstyle（checkstyleMain）统一执行；
// 风格细节（缩进/换行/import 排序）由 Spotless 负责，两者职责不重叠。
