FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
RUN gradle :app:gateway-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
COPY --from=build /src/app/gateway-api/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
