# Ana Dépôt API — container image (Spring Boot 4.1 / Java 25).
#
#   docker build -t ana-depot-api:local .
#
# Stateless by design: nothing is written to the container filesystem (uploads go to R2, state to PostgreSQL), so any platform that
# replaces or restarts the container at will is fine. No secret is baked in: every credential arrives as an environment variable at run
# time (docs/staging/api.staging.env.template). The image runs as a non-root user.
#
# The build stage does NOT run the test suite (it needs Docker-in-Docker for Testcontainers); run `mvn test` in CI before building the image.

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml ./
# Resolve dependencies in their own layer so a source-only change does not download them again.
RUN mvn -B -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/ana-depot-api-*.jar extract --layers --destination /workspace/layers

FROM eclipse-temurin:25-jre
RUN groupadd --system --gid 10001 ana && useradd --system --uid 10001 --gid ana --no-create-home --shell /usr/sbin/nologin ana
WORKDIR /app
COPY --from=build /workspace/layers/dependencies/ ./
COPY --from=build /workspace/layers/spring-boot-loader/ ./
COPY --from=build /workspace/layers/snapshot-dependencies/ ./
COPY --from=build /workspace/layers/application/ ./
USER 10001:10001
EXPOSE 8080
# Heap = 75 % of the container's memory limit (the rest is metaspace, thread stacks, direct buffers); exit on OOM so the platform restarts a clean process.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# Probes: GET /actuator/health/liveness and /actuator/health/readiness on the management port when MANAGEMENT_SERVER_PORT is set (docs/operations.md).
ENTRYPOINT ["java", "-jar", "/app/ana-depot-api-0.0.1-SNAPSHOT.jar"]
