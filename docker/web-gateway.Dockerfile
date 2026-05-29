# syntax=docker/dockerfile:1
FROM node:20-alpine AS build
WORKDIR /src
RUN corepack enable
COPY web/gateway/package.json web/gateway/
WORKDIR /src/web/gateway
RUN corepack pnpm install --no-frozen-lockfile
COPY web/gateway/ .
RUN corepack pnpm build

FROM node:20-alpine AS run
WORKDIR /app
COPY --from=build /src/web/gateway/.next/standalone ./
COPY --from=build /src/web/gateway/.next/static ./.next/static
COPY --from=build /src/web/gateway/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
