# ---- Build stage ----
FROM eclipse-temurin:17-jdk-alpine AS build

ARG MODULE=gateway-app

WORKDIR /app

# Copy Gradle wrapper & config first (cache dependencies)
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts gradle.properties ./

# Copy all module build files for dependency resolution
COPY shared/build.gradle.kts shared/
COPY gateway-app/build.gradle.kts gateway-app/
COPY game-app/build.gradle.kts game-app/

# Download dependencies (cached layer)
RUN ./gradlew :${MODULE}:dependencies --no-daemon || true

# Copy all source (shared is always needed)
COPY shared/ shared/
COPY gateway-app/ gateway-app/
COPY game-app/ game-app/

# Build the target module's fat jar
RUN ./gradlew :${MODULE}:bootJar --no-daemon -x test

# ---- Runtime stage ----
FROM eclipse-temurin:17-jre-alpine

ARG MODULE=gateway-app

WORKDIR /app

# Gateway needs docker CLI for container orchestration via Docker socket
RUN if [ "$MODULE" = "gateway-app" ]; then \
      apk add --no-cache docker-cli; \
    fi

COPY --from=build /app/${MODULE}/build/libs/*.jar app.jar

EXPOSE 8080 9001

ENTRYPOINT ["java", "-jar", "app.jar"]
