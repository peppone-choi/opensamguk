# syntax=docker/dockerfile:1
FROM node:22-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@10.8.0
COPY web/gateway/package.json web/gateway/pnpm-lock.yaml web/gateway/
WORKDIR /src/web/gateway
RUN pnpm install --ignore-scripts
COPY web/gateway/ .
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
COPY --from=build /src/web/gateway/.next/standalone ./
COPY --from=build /src/web/gateway/.next/static ./.next/static
COPY --from=build /src/web/gateway/public ./public
EXPOSE 3000
CMD ["node", "server.js"]
