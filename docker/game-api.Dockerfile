# game-api (:8081) — read / precheck / intake / SSE + JWT verifier. Multi-stage JDK21 build.
FROM gradle:8.12-jdk21 AS build
WORKDIR /src
COPY . .
ARG IMAGE_TAG=dev
ENV IMAGE_TAG=$IMAGE_TAG
RUN gradle :app:game-api:bootJar --no-daemon
RUN apt-get update \
    && apt-get install -y --no-install-recommends python3-minimal \
    && rm -rf /var/lib/apt/lists/*
RUN python3 tools/map/build_province_map.py \
    --input data/map/han-tiles.json \
    --output-dir build/generated-map \
    --map-code han

FROM eclipse-temurin:21-jre AS run
WORKDIR /app
# curl: container healthcheck (Spring actuator probe)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /src/app/game-api/build/libs/*.jar app.jar
# 후한 군현 타일맵(ADR-LITE-040). jar 리소스가 아니라 파일로 두는 건 그대로다 —
# 내리고 싶으면 이 한 줄을 지우면 되고, 그러면 /api/map/terrain 이 404 로 폴백한다.
COPY data/map/han-tiles.json /app/data/map/han-tiles.json
COPY --from=build /src/build/generated-map/han-provinces.png /app/data/map/han-provinces.png
COPY --from=build /src/build/generated-map/han-provinces.meta.json /app/data/map/han-provinces.meta.json
ENV JAVA_OPTS="-Djava.security.egd=file:/dev/./urandom"
EXPOSE 8081
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
