# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /src
RUN corepack enable
COPY web/game/package.json web/game/
WORKDIR /src/web/game
RUN corepack pnpm install --no-frozen-lockfile
COPY web/game/ .
RUN corepack pnpm build

FROM node:20-alpine AS run
WORKDIR /app
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./.next/static
COPY --from=build /src/web/game/public ./public
EXPOSE 3001
CMD ["node", "server.js"]
