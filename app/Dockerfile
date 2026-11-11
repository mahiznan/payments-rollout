# ---- Stage 1: build the jar (Maven + JDK 25) ----
FROM maven:3-eclipse-temurin-25 AS build
WORKDIR /build

# Copy the pom first and resolve dependencies, so this layer is cached
# until the dependencies change
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q package -DskipTests

# ---- Stage 2: runtime (JRE 25 only, no Maven, no JDK, no sources) ----
FROM eclipse-temurin:25-jre
WORKDIR /app

COPY --from=build /build/target/payment-api.jar /app/payment-api.jar

EXPOSE 8080

# OpenShift runs the container with a random non-root UID; this keeps it
# non-root when run anywhere else too
USER 1001

# Exec form: java is PID 1 and receives SIGTERM directly, which is what
# Spring's graceful shutdown needs. A shell wrapper would swallow the signal.
# MaxRAMPercentage sizes the heap from the container memory limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/payment-api.jar"]
