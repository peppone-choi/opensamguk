FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
ARG IMAGE_TAG=dev
ENV IMAGE_TAG=$IMAGE_TAG
RUN gradle :app:board-api:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/app/board-api/build/libs/*.jar app.jar
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom"
EXPOSE 8083
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
