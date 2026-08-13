plugins {
    java
    id("org.springframework.boot") version "4.1.0"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
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
    // starter-webmvc, matching the engine. Note there is no starter-websocket
    // here: Milestone 5 chose SSE, which is plain HTTP, and the dependency sat
    // unused until Milestone 10 removed it — the same one Milestone 4 had already
    // removed from ui-service for the same reason.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation(project(":common"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    // Versions omitted deliberately: all three are managed by the Spring Boot BOM
    // (verified 2026-08-13 — prometheus 1.17.0, tracing-bridge-otel 1.7.0, otlp 1.62.0).
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.1.0")
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")
    // Eureka's HTTP request factory (defaultEurekaClientHttpRequestFactorySupplier) needs
    // Apache HttpClient 5 on the runtime classpath, but eureka-client declares httpclient5
    // <optional>, so Gradle does not reliably propagate it (the engine module only gets it
    // by accident of a different transitive path). Declare it explicitly so Eureka boots.
    runtimeOnly("org.apache.httpcomponents.client5:httpclient5")
    // Retry is Spring Framework 7's own @Retryable (org.springframework.resilience),
    // enabled by @EnableResilientMethods in AsyncConfig. It replaced a hand-pinned
    // spring-retry plus a runtimeOnly aspectjweaver that existed only to keep
    // @EnableRetry's AspectJ proxy creator from failing at class-init — CLAUDE.md
    // asks for the framework's built-in, and this is it.
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    // MockWebServer (okhttp 4.x) — backs RestGameEngineClientRetryTest's real HTTP endpoint.
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2025.1.2")
    }
}

// --- integrationTest source set -------------------------------------------------
// Session↔Engine integration tests need game-engine-service on the classpath, and
// the engine's src/main/resources/application.yml sits at exactly the same
// classpath location as this service's own (classpath:/application.yml). Spring
// Boot resolves that location to ONE resource, so putting the engine module on the
// ordinary `test` classpath would make the winner depend on classpath ordering —
// and would newly apply JPA/H2 auto-configuration to all existing session tests.
// A separate source set leaves the `test` classpath byte-for-byte unchanged.
sourceSets {
    create("integrationTest")
}

val integrationTestSourceSet = sourceSets["integrationTest"]
integrationTestSourceSet.compileClasspath += sourceSets["main"].output

// The runtime classpath is assigned in full rather than appended to, because its
// ORDER decides which application.yml the Session context reads and Gradle's
// default order gets it wrong. The default is
// `output + integrationTestRuntimeClasspath`, with this service's own main output
// appended last — behind the engine module. Spring Boot then resolves
// classpath:/application.yml to the ENGINE's file, and the Session context boots
// as "game-engine-service" with no engine.client.* properties at all (verified:
// PlaceholderResolutionException on ${engine.client.base-url}). Putting this
// service's own output ahead of its dependencies makes the Session configuration
// win deterministically instead of by luck.
integrationTestSourceSet.runtimeClasspath = integrationTestSourceSet.output +
        sourceSets["main"].output +
        configurations["integrationTestRuntimeClasspath"]

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    // Integration-test scope ONLY. The production dependency graph must not change:
    // no service may start depending on another service's code.
    "integrationTestImplementation"(project(":game-engine-service"))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<Test>("integrationTest") {
    description = "Runs the Session↔Engine integration tests, with both services really running."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    // Two Spring Boot contexts and several HTTP servers per class — never race the
    // unit suite for ports.
    shouldRunAfter(tasks.named("test"))
}

// Coverage is measured over BOTH suites: the integration tests exercise the
// production code too, so excluding them would understate real coverage.
// Neither `test` nor `integrationTest` is wired as a finalizer of the report:
// the coverage tasks are reached through `check`, which runs both suites first,
// and a bare `./gradlew test` must stay a fast unit-only run.
tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.named<Test>("test"))
    dependsOn(tasks.named<Test>("integrationTest"))
    executionData.setFrom(
        fileTree(layout.buildDirectory).matching {
            include("jacoco/test.exec", "jacoco/integrationTest.exec")
        }
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    dependsOn(tasks.named("jacocoTestReport"))
    executionData.setFrom(
        fileTree(layout.buildDirectory).matching {
            include("jacoco/test.exec", "jacoco/integrationTest.exec")
        }
    )
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
    dependsOn(tasks.named("integrationTest"))
    dependsOn(tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification"))
    dependsOn(tasks.named("pitest"))
}

pitest {
    // Deliberately left on the default testSourceSets = [test]. Mutation analysis
    // stays on the fast unit tests; running it against the integration suite would
    // boot two Spring contexts per mutant, which is untenable. Do not "fix" this.
    junit5PluginVersion.set("1.2.1")
    pitestVersion.set("1.22.1")
    targetClasses.set(setOf("com.flamingo.tiktaktoe.session.*"))
    // Excluded because Pitest mutates method *bodies*, and what these classes
    // decide lives in annotations: which RestClient.Builder is @Primary, which is
    // @LoadBalanced. Those decisions are load-bearing — getting the first one
    // wrong silently breaks this service's Eureka registration — but no body
    // mutant can express them, so including the package would only measure
    // "return null instead of the bean". The real gate for them is
    // RestClientConfigTest, which asserts the resulting behaviour against a
    // booted context.
    excludedClasses.set(setOf("com.flamingo.tiktaktoe.session.config.*"))
    outputFormats.set(setOf("XML", "HTML"))
    timestampedReports.set(false)
    verbose.set(true)
    mutationThreshold.set(80)
}
