import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';
import { withSentryConfig } from '@sentry/nextjs';

const here = dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    outputFileTracingRoot: join(here, '..'),
    // CI는 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드를 기본 빌드 옆 폴더에 한 번 더 굽는다.
    // 미설정이면 기본 `.next` 그대로(운영 · 로컬 동일).
    distDir: process.env.NEXT_DIST_DIR || '.next',
    reactStrictMode: true,
    transpilePackages: ['@opensamguk/ui'],
    // 공유 도메인(sam.peppone.dev)에서 gateway-frontend와 `/_next` 에셋 경로가 충돌한다(둘 다 Next 앱,
    // 같은 /_next). 게임 에셋만 구분 경로(`/game/_next/...`)로 내보내 nginx가 game-frontend로 보낼 수
    // 있게 한다. 라우트(app/game/* → /game)는 그대로 — assetPrefix는 에셋 URL만 바꾼다(basePath 아님).
    // 미설정(로컬 dev, web-game :3001 직접 접속)이면 기본 `/_next`. prod 빌드만 ASSET_PREFIX=/game.
    assetPrefix: process.env.ASSET_PREFIX || undefined,
    // #516 §5 — web/game은 더 이상 자체 /api/game 프록시를 갖지 않는다(단일 프록시로 통합, dev/prod
    // 발산 소멸). docker/prod에서는 nginx(infra/nginx/nginx.conf `/api/game/`)가 web-gateway로 보내고,
    // nginx 없이 `pnpm dev`만 단독 실행하는 프론트 dev 흐름에서는 이 rewrite가 그 역할을 대신한다.
    // 쿠키는 포트 무관 same-host 매치라(RFC 6265 §5.1.3) sam_access가 그대로 전달된다.
    // Standalone builds capture these destinations; Docker must supply GATEWAY_WEB_URL at build time.
    async rewrites() {
        return [
            {
                source: '/profile-icons/:path*',
                destination: `${process.env.GATEWAY_WEB_URL || 'http://localhost:3000'}/profile-icons/:path*`,
            },
            {
                source: '/api/game/:path*',
                destination: `${process.env.GATEWAY_WEB_URL || 'http://localhost:3000'}/api/game/:path*`,
            },
        ];
    },
};

// 소스맵 업로드는 SENTRY_AUTH_TOKEN이 있을 때만 — 미설정이면 업로드 없이 빌드된다(백로그 항목).
export default withSentryConfig(nextConfig, {
    org: process.env.SENTRY_ORG,
    project: process.env.SENTRY_PROJECT,
    authToken: process.env.SENTRY_AUTH_TOKEN,
    sourcemaps: { disable: !process.env.SENTRY_AUTH_TOKEN },
    silent: true,
    telemetry: false,
});
