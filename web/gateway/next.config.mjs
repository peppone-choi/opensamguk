import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withSentryConfig } from '@sentry/nextjs';

const here = dirname(fileURLToPath(import.meta.url));

/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    outputFileTracingRoot: join(here, '..'),
    reactStrictMode: true,
    transpilePackages: ['@opensamguk/ui'],
    // CI는 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드를 기본 빌드 옆 폴더에 한 번 더 굽는다.
    // 미설정(운영 · 로컬)이면 .next 그대로다.
    distDir: process.env.NEXT_DIST_DIR || '.next',
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
