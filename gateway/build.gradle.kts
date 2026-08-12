plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "API Gateway — Spring Cloud Gateway, single entry point"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    // Resolves the lb:// service ids the routes target. Also the only source of
    // spring-cloud-loadbalancer, without which lb:// is never rewritten at all.
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Required of every service by CLAUDE.md; Eureka reads /actuator/health.
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
