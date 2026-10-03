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
    // 로컬에서 다른 설정의 빌드를 기본 빌드 옆 폴더에 굽고 싶을 때만 쓴다(CI · 운영은 쓰지 않는다).
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
