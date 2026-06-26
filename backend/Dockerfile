# ──────────────────────────────────────────────────────────────────────────
# Shared multi-module build. Pass --build-arg MODULE=<eureka-server|api-gateway|
# core-service> (compose does this per service). The Maven reactor resolves the
# local backend-parent + sibling poms, so each module builds with `-pl … -am`.
# Build context = ./backend
# ──────────────────────────────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21 AS build
ARG MODULE
WORKDIR /build

# Copy all poms first so dependency resolution layers cache well.
COPY pom.xml .
COPY eureka-server/pom.xml eureka-server/pom.xml
COPY api-gateway/pom.xml api-gateway/pom.xml
COPY core-service/pom.xml core-service/pom.xml
RUN mvn -B -q -pl ${MODULE} -am dependency:go-offline

# Copy sources and build the target module (+ its reactor dependencies).
COPY eureka-server/src eureka-server/src
COPY api-gateway/src api-gateway/src
COPY core-service/src core-service/src
RUN mvn -B -q -pl ${MODULE} -am clean package -DskipTests \
    && cp ${MODULE}/target/*.jar /build/app.jar

# ── Runtime ──
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN useradd -r -u 1001 crystal
USER crystal
COPY --from=build /build/app.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
