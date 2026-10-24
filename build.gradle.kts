plugins {
    java
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    // 产出可直接 java -jar 运行的可执行 fat jar
    id("com.gradleup.shadow") version "9.4.0"
    // 统一代码风格：gradlew spotlessApply 全量格式化，gradlew spotlessCheck 校验
    id("com.diffplug.spotless") version "7.0.4"
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
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
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

// main 源码卫生门禁：禁止控制台诊断输出（日志走 core/log/Log）与制表符缩进。
// 命名/导入等风格规则由 Spotless（Palantir Java Format）在格式化时统一处理；
// checkstyle 的 Gradle Ant 执行器与当前 Gradle 9.1 + JDK 25 组合不兼容，暂缓接入。
val enforceMainHygiene =
    tasks.register("enforceMainHygiene") {
        doLast {
            val offenders = mutableListOf<String>()
            fileTree("src/main/java") {
                include("**/*.java")
            }.forEach { f ->
                val text = f.readText()
                if (Regex("System\\.(out|err)\\.print").containsMatchIn(text)) {
                    offenders.add("${f.name}: System.out/err 输出（用 core/log/Log）")
                }
                if (Regex("printStackTrace\\s*\\(").containsMatchIn(text)) {
                    offenders.add("${f.name}: printStackTrace（用 core/log/Log）")
                }
                if (text.contains('\t')) {
                    offenders.add("${f.name}: 制表符缩进（用 4 空格）")
                }
            }
            require(offenders.isEmpty()) {
                "main 源码卫生检查未通过：\n" + offenders.joinToString("\n")
            }
        }
    }

tasks.check {
    dependsOn(enforceMainHygiene)
}
