plugins {
    java
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

tasks.named<Test>("test") {
    useJUnitPlatform()
}
