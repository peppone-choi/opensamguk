# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /src
RUN corepack enable
COPY web/game/package.json web/game/pnpm-lock.yaml web/game/
WORKDIR /src/web/game
RUN echo "ignore-build-dependency-scripts=true" >> .npmrc && corepack pnpm install
COPY web/game/ .
RUN corepack pnpm build

FROM node:22-alpine AS run
WORKDIR /app
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./.next/static
COPY --from=build /src/web/game/public ./public
EXPOSE 3001
CMD ["node", "server.js"]
