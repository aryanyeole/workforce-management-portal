# syntax=docker/dockerfile:1

# ---- Build stage ------------------------------------------------------
# JDK, not JRE: compiling needs javac, the runtime stage below doesn't.
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Maven Wrapper only (CLAUDE.md: no standalone mvn on PATH) — mvnw, the
# Linux script, not mvnw.cmd. git tracks mvnw as mode 100755 (Phase 10 CI
# hygiene), but whether that mode survives a Windows host's working tree
# into this build context isn't something worth trusting blindly here;
# set it explicitly, same defensive call the CI workflow made before its
# own git-mode fix made it provably unnecessary there.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw

# Dependency layer, built from pom.xml alone before src/ exists in this
# image at all: as long as pom.xml doesn't change, Docker reuses this
# layer's cache regardless of what changes in src/. dependency:go-offline
# is the standard goal for exactly this — it resolves what an offline
# build afterward would need (main, test, and plugin dependencies), not
# just the runtime dependency graph a narrower goal would fetch.
RUN ./mvnw dependency:go-offline -B

COPY src ./src

# -DskipTests is deliberate, not incidental. This project's surefire
# currently matches zero test classes, and package alone never reaches
# failsafe's bound integration-test/verify phases (see pom.xml) — so this
# would happen to produce a correct jar even without the flag. Relying on
# that would be fragile: add one real surefire unit test later and this
# build silently starts trying to run it inside a container with no
# Docker daemon of its own for Testcontainers to use. CI
# (.github/workflows/ci.yml) is the one place this suite runs against a
# real Testcontainers Postgres; this build only ever produces the
# artifact.
RUN ./mvnw clean package -DskipTests -B


# ---- Runtime stage ------------------------------------------------------
# JRE, not JDK: nothing here compiles anything, only runs an already-built jar.
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# Plain system user/group — no login shell, no home directory, nothing a
# single `java -jar` process needs beyond permission to read its own jar.
RUN groupadd --system spring \
    && useradd --system --gid spring --no-create-home --shell /usr/sbin/nologin spring

# Wildcard rather than the exact versioned name: spring-boot-maven-plugin's
# default repackage replaces the plain jar at target/<artifactId>-<version>.jar
# with the executable one (stashing the original as *.jar.original, which
# this pattern correctly excludes) — matching by *.jar avoids a version bump
# in pom.xml also requiring an edit here.
COPY --from=build --chown=spring:spring /app/target/*.jar app.jar

USER spring

# Spring Boot's own default; application.yml sets no server.port override.
EXPOSE 8080

# spring.datasource.url has no ${...} placeholder in application.yml, and
# none was added for this — it doesn't need one. Spring Boot's
# environment-variable property binding already overrides any property
# this way: setting SPRING_DATASOURCE_URL at `docker run`/compose time
# (e.g. jdbc:postgresql://db:5432/wmp on the compose network) takes
# precedence over application.yml's dev default
# (jdbc:postgresql://localhost:5433/wmp) with no file in this image
# changed and no property added. Same mechanism already covers
# SPRING_DATASOURCE_USERNAME/SPRING_DATASOURCE_PASSWORD if DB_USER/
# DB_PASSWORD's own defaults ever need overriding the same way.
ENTRYPOINT ["java", "-jar", "app.jar"]
