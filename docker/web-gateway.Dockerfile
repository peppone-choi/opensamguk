# web/gateway (:3000) — Next.js standalone (landing + auth). NEXT_PUBLIC_* are inlined at BUILD time.
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
RUN pnpm install --frozen-lockfile --filter @opensamguk/web-gateway...
COPY web/shared/ /src/web/shared/
COPY web/gateway/ /src/web/gateway/
RUN pnpm --dir shared verify:topology gateway
WORKDIR /src/web/gateway
# NEXT_PUBLIC_* must be present at build (Next inlines them into the client bundle).
ARG NEXT_PUBLIC_GAME_URL=http://localhost:3001
ENV NEXT_PUBLIC_GAME_URL=$NEXT_PUBLIC_GAME_URL
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3000
COPY --from=build /src/web/gateway/.next/standalone ./
COPY --from=build /src/web/gateway/.next/static ./gateway/.next/static
COPY --from=build /src/web/gateway/public ./gateway/public
EXPOSE 3000
CMD ["node", "gateway/server.js"]
