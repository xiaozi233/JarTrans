plugins {
    // 允许 Gradle 在本机没有匹配 JDK 时自动下载工具链
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "jartrans"
