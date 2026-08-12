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
    // Serves src/main/resources/static. This module has no controllers: the page
    // talks to the session service directly from the browser.
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Registers as UI-SERVICE so the Gateway (Milestone 6) can route lb://UI-SERVICE.
    // Nothing here resolves anything through Eureka.
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
