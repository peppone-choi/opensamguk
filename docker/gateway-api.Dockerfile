# gateway-api (:8080) — auth / profile / JWT issuer. Multi-stage JDK21 build.
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:gateway-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# curl: container healthcheck (Spring actuator probe)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/app/gateway-api/build/libs/*.jar app.jar
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
