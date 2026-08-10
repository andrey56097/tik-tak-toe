plugins {
    java
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "Shared DTOs/contracts for the tik-tak-toe services"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.0")
    }
}

dependencies {
    implementation("jakarta.validation:jakarta.validation-api")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}
