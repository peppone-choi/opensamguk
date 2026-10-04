import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { withSentryConfig } from '@sentry/nextjs';

const here = dirname(fileURLToPath(import.meta.url));

// 보안 머리글 — 모든 응답(두 앱 같은 값, 시험 e2e/smoke/security-headers.spec.ts).
// CSP 는 지금 frame-ancestors 하나만 둔다. 스크립트 · 스타일까지 거는 전체 CSP 는 report-only 로 먼저 관측한 뒤 따로 켠다
// (docs/development/security-headers.md). HSTS 는 엣지(nginx) 몫이라 여기 두지 않는다.
const SECURITY_HEADERS = [
    { key: 'X-Frame-Options', value: 'DENY' },
    { key: 'Content-Security-Policy', value: "frame-ancestors 'none'" },
    { key: 'X-Content-Type-Options', value: 'nosniff' },
    { key: 'Referrer-Policy', value: 'strict-origin-when-cross-origin' },
    { key: 'Permissions-Policy', value: 'camera=(), microphone=(), geolocation=(), payment=(), usb=()' },
];

/** @type {import('next').NextConfig} */
const nextConfig = {
    output: 'standalone',
    outputFileTracingRoot: join(here, '..'),
    reactStrictMode: true,
    poweredByHeader: false,
    async headers() {
        return [{ source: '/:path*', headers: SECURITY_HEADERS }];
    },
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
