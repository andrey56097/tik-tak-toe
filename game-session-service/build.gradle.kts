plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "Game Session Service — orchestrator / auto-play"

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
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation(project(":common"))
    // @Retryable on RestGameEngineClient.makeMove. spring-retry is not covered by the
    // Spring Boot BOM, so its version is pinned explicitly. @EnableRetry (used by this
    // part's retry test, and by AsyncConfig in Part C) is meta-annotated with
    // @EnableAspectJAutoProxy, which registers AnnotationAwareAspectJAutoProxyCreator;
    // that class's static init touches org.aspectj.weaver.Advice whether or not any
    // AspectJ pointcut is used, so aspectjweaver must be on the runtime classpath or
    // the context fails to refresh with NoClassDefFoundError. Runtime-only is enough —
    // nothing is woven here. Note `spring-boot-starter-aop` does not exist for Boot 4.x
    // (last published at 4.0.0-M2); aspectjweaver's version IS BOM-managed.
    implementation("org.springframework.retry:spring-retry:2.0.13")
    runtimeOnly("org.aspectj:aspectjweaver")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // MockWebServer (okhttp 4.x) — backs RestGameEngineClientRetryTest's real HTTP endpoint.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn(tasks.named("pitest"))
}

pitest {
    junit5PluginVersion.set("1.2.1")
    pitestVersion.set("1.22.1")
    targetClasses.set(setOf("com.flamingo.tiktaktoe.session.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(true)
    mutationThreshold.set(80)
}
