# syntax=docker/dockerfile:1

# One recipe, five images: docker-compose.yml builds this file once per service
# with a different MODULE. The five would otherwise be copies differing in a
# single word, and every base-image or JVM-flag change would have to be made in
# five places (DRY, CLAUDE.md).
#
# The build context is the repository root — the root settings.gradle.kts drives
# every module's build, and the engine and session modules compile against
# :common — so paths below are repository-relative.

FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .

ARG MODULE
# The cache mount keeps GRADLE_USER_HOME across builds, so the five images share
# one dependency download (and one Gradle distribution) instead of repeating it
# five times. sharing=locked because compose builds the services in parallel and
# a Gradle cache is not safe for concurrent writers; the alternative is
# "Timeout waiting to lock artifact cache" appearing at random.
#
# The lock is held for the whole RUN, so the five builds are serialised by
# design: a cold `docker compose up --build` takes ~10 minutes, and BuildKit
# shows four builds apparently idle while the fifth compiles. Giving each module
# its own cache id would restore parallelism, at five copies of the dependency
# cache on disk.
#
# bootJar, not build: running the test suite is the Gradle build's job (and CI's),
# not something to repeat inside five images. It also leaves exactly one jar in
# build/libs, which is what makes the COPY below unambiguous — `build` would also
# produce the -plain.jar.
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon :${MODULE}:bootJar

FROM eclipse-temurin:21-jre AS runtime
# ARG does not survive a stage boundary; it has to be redeclared to be used here.
ARG MODULE
WORKDIR /app
COPY --from=build /workspace/${MODULE}/build/libs/*.jar app.jar

# Nothing in these services needs root, and a JVM that cannot write to its own
# image is a much smaller blast radius if a dependency is ever exploited. The jar
# deliberately stays root-owned and world-readable: `chown`ing it would copy all
# 80 MB into a second layer to change one metadata bit, and the process only ever
# needs to read it.
RUN useradd --system --create-home --uid 10001 spring
USER spring

# No EXPOSE: the port is per-service (8080/8081/8082/8083/8761) and compose
# already declares it, so an ARG here would only be a second place to keep in
# sync.
#
# No memory limit is set on these containers today, so this changes nothing yet:
# it makes the heap follow a cgroup limit the moment one is imposed (compose
# `mem_limit`, a Kubernetes resource limit in Milestone 11), instead of being
# sized from the whole host's RAM and then OOM-killed.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
