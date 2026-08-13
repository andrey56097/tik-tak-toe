plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "Eureka Server — service discovery"

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
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-server")
    // Declared even though the Eureka server starter already pulls it in
    // transitively: CLAUDE.md requires /actuator/health of every service, and
    // the Milestone 9 compose healthcheck gates the other four containers on
    // it. A capability that load-bearing must not rest on another starter's
    // dependency graph, which a BOM upgrade is free to change.
    implementation("org.springframework.boot:spring-boot-starter-actuator")
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
