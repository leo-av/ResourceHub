# ==============================================================================
# ResourceHub — Dockerfile Multi-Stage Inmutable (ADR-006, ADR-010)
# ==============================================================================
# Preparado para empaquetar el artefacto único multi-perfil (`api`, `jobs`,
# `consumers`) sobre Eclipse Temurin Java 21 LTS sin privilegios de root.
# Coste: $0.00 (100% Open Source).
# ==============================================================================

FROM eclipse-temurin:21.0.5_11-jdk-alpine AS builder
WORKDIR /workspace
COPY pom.xml mvnw ./
COPY .mvn .mvn
COPY config config
COPY modules modules
RUN ./mvnw -B package -DskipTests

FROM eclipse-temurin:21.0.5_11-jre-alpine AS runtime
RUN addgroup -S resourcehub && adduser -S resourcehub -G resourcehub
USER resourcehub:resourcehub
WORKDIR /app

ENV TZ=UTC \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -Dfile.encoding=UTF-8 -Duser.timezone=UTC" \
    SPRING_PROFILES_ACTIVE="api,jobs,consumers"

COPY --from=builder --chown=resourcehub:resourcehub /workspace/modules/*/target/*.jar /app/lib/

EXPOSE 8080
