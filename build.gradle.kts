plugins {
    id("java")
    id("application")
    id("org.openjfx.javafxplugin") version "0.1.0"
    // 产出可直接 java -jar 运行的可执行 fat jar
    id("com.gradleup.shadow") version "9.4.0"
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
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
