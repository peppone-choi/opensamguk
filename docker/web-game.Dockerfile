# web/game (:3001) — Next.js standalone (game client). NEXT_PUBLIC_* are inlined at BUILD time.
FROM node:22-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@10.33.0
# pnpm-workspace.yaml MUST be copied with the manifest+lock so the install honors
# `onlyBuiltDependencies` (sharp/unrs-resolver) — pnpm 10 blocks dep build scripts by default and
# would otherwise leave the natives unbuilt (next build then fails).
COPY web/package.json web/pnpm-lock.yaml web/pnpm-workspace.yaml web/
COPY web/gateway/package.json web/gateway/
COPY web/game/package.json web/game/
COPY web/shared/package.json web/shared/
WORKDIR /src/web
RUN pnpm install --frozen-lockfile --filter @opensamguk/web-game...
COPY web/shared/ /src/web/shared/
COPY web/game/ /src/web/game/
RUN pnpm --dir shared verify:topology game
WORKDIR /src/web/game
# Next.js rewrites are captured during build, including server-only proxy destinations.
ARG GATEWAY_WEB_URL=http://web-gateway:3000
ENV GATEWAY_WEB_URL=$GATEWAY_WEB_URL
ARG NEXT_PUBLIC_GATEWAY_URL=
ENV NEXT_PUBLIC_GATEWAY_URL=$NEXT_PUBLIC_GATEWAY_URL
# 공유 도메인 에셋 충돌 방지 — prod는 ASSET_PREFIX=/game(next.config assetPrefix가 빌드타임에 읽음).
# 미설정(로컬) 시 기본 /_next. assetPrefix는 에셋 URL만 바꿈(라우트/ api 경로 불변).
ARG ASSET_PREFIX=
ENV ASSET_PREFIX=$ASSET_PREFIX
# 제품 화면 새 지도(탑다운) 교체 스위치 — 빌드 때 박힌다(교체 계획 3단계 ③, K0 10-02 「가」).
# 켜도 서버가 미리보기에 topdownBakeId 를 주지 않으면(C9 bake 활성 A04 전) 옛 지도 그대로다. 되돌리기: --build-arg NEXT_PUBLIC_TOPDOWN_SCREENS=0.
ARG NEXT_PUBLIC_TOPDOWN_SCREENS=1
ENV NEXT_PUBLIC_TOPDOWN_SCREENS=$NEXT_PUBLIC_TOPDOWN_SCREENS
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3001
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./game/.next/static
COPY --from=build /src/web/game/public ./game/public
EXPOSE 3001
CMD ["sh", "-c", "HOSTNAME=0.0.0.0 exec node game/server.js"]
