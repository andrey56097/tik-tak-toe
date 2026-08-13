plugins {
    java
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("info.solidsoft.pitest") version "1.19.0"
}

group = "com.flamingo"
version = "0.0.1-SNAPSHOT"
description = "Shared DTOs and the error contract (plus the advice base that produces it)"

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

    // For AbstractRestExceptionHandler only. compileOnly keeps this from becoming a
    // web library: both consumers already have Spring MVC, and nothing is dragged
    // along to a future non-web one.
    compileOnly("org.springframework:spring-web")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.slf4j:slf4j-api")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testImplementation("org.springframework:spring-web")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework:spring-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.slf4j:slf4j-api")
    // Without a binding SLF4J falls back to NOPLogger, named "NOP" — which makes
    // the base class's per-subclass logger naming untestable.
    testRuntimeOnly("org.slf4j:slf4j-simple")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

// `common` predates the mutation-testing rule, so the types every service shares
// were gated by nothing — which is exactly where the audit found a silently wrong
// return value.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named<Test>("test"))
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named<JacocoReport>("jacocoTestReport"))
    violationRules {
        rule {
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.named("check") {
    dependsOn(tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("pitest"))
}

pitest {
    junit5PluginVersion.set("1.2.1")
    pitestVersion.set("1.22.1")
    targetClasses.set(setOf("com.flamingo.tiktaktoe.common.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(true)
    mutationThreshold.set(80)
}
