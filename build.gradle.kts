// Groovy-DSL 兼容写法说明：本文件使用 Kotlin DSL
// 影子打包仅用于把未来可能加入的运行时依赖打进 jar
plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

group = "dev.eyf"
version = "0.1.1"

java {
    // Paper 26.3 API 要求 JVM 25；toolchain 由 settings.gradle.kts 的 foojay 自动供给
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.3 API（与服务器版本对应）
    compileOnly("io.papermc.paper:paper-api:26.3.build.8-alpha")
    // WebSocket 客户端（Java 11+ 自带 HttpClient WS，无需额外依赖；这里仅用 JavaHttpClient）
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
    // MIT 要求「在软件的所有副本中包含许可声明」，故把本仓库根目录的 LICENSE
    // 打进 jar。这是 Gradle 内置行为：默认从 src/main/resources 取资源，
    // 这里额外把项目根目录（仓库根）的 LICENSE 也纳入。
    from("LICENSE")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    archiveClassifier.set("")
    // 当前无第三方运行时依赖，shade 仅作保险；引入 Java-WebSocket 后自动打入
}
