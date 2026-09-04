# game-engine (:8082) — turn daemon (InMemoryTurnWorld) + scenario/admin seed on empty DB.
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
ARG IMAGE_TAG=dev
ENV IMAGE_TAG=$IMAGE_TAG
RUN gradle :app:game-engine:bootJar --no-daemon

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# curl: container healthcheck (Spring actuator probe)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/app/game-engine/build/libs/*.jar app.jar
COPY data/map/han-tiles.json /app/data/map/han-tiles.json
COPY data/map/han-scenario-province-ownership-v1.json /app/data/map/han-scenario-province-ownership-v1.json
COPY data/curated/han/supply-disconnection-adjudications-v1.json /app/data/curated/han/supply-disconnection-adjudications-v1.json
COPY data/curated/han/supply-disconnection-adjudications-v3.json /app/data/curated/han/supply-disconnection-adjudications-v3.json
COPY data/curated/han/territory-disconnection-adjudications-v1.json /app/data/curated/han/territory-disconnection-adjudications-v1.json
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom"
EXPOSE 8082
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
