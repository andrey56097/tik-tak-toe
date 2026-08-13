plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "UI Service — serves the static board page"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.2")
    }
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// Gradle daemons may not inherit npm's shell PATH.
fun npmExecutable(): String {
    val fromPath = (System.getenv("PATH") ?: "").split(File.pathSeparator)
    val commonPrefixes = listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
    return (fromPath + commonPrefixes).asSequence()
        .filter { it.isNotBlank() }
        .map { File(it, "npm") }
        .firstOrNull { it.canExecute() }
        ?.absolutePath
        ?: "npm"
}

val npmCi = tasks.register<Exec>("npmCi") {
    description = "Installs the test-only JS toolchain from the committed lockfile."
    workingDir = projectDir
    commandLine(npmExecutable(), "ci", "--no-audit", "--no-fund")
    inputs.file("package.json")
    inputs.file("package-lock.json")
    outputs.dir("node_modules")
}

val npmTest = tasks.register<Exec>("npmTest") {
    description = "Runs the Vitest suite for the static page."
    group = "verification"
    dependsOn(npmCi)
    workingDir = projectDir
    commandLine(npmExecutable(), "test", "--silent")
    inputs.dir("src/main/resources/static")
    inputs.dir("src/test/javascript")
    inputs.file("vitest.config.js")
    outputs.upToDateWhen { false }
}

tasks.named("check") {
    dependsOn(npmTest)
}
