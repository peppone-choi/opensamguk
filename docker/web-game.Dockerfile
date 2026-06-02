# web/game (:3001) — Next.js standalone (game client). NEXT_PUBLIC_* are inlined at BUILD time.
FROM node:22-alpine AS build
WORKDIR /src
RUN npm install -g pnpm@9.15.0
COPY web/game/package.json web/game/pnpm-lock.yaml web/game/
WORKDIR /src/web/game
RUN pnpm install --frozen-lockfile
COPY web/game/ .
# NEXT_PUBLIC_* must be present at build (Next inlines them into the client bundle).
ARG NEXT_PUBLIC_GATEWAY_URL=http://localhost:3000
ENV NEXT_PUBLIC_GATEWAY_URL=$NEXT_PUBLIC_GATEWAY_URL
RUN pnpm build

FROM node:22-alpine AS run
WORKDIR /app
ENV NODE_ENV=production
ENV PORT=3001
COPY --from=build /src/web/game/.next/standalone ./
COPY --from=build /src/web/game/.next/static ./.next/static
COPY --from=build /src/web/game/public ./public
EXPOSE 3001
CMD ["node", "server.js"]
