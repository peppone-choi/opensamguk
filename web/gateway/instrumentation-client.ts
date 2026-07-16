import * as Sentry from '@sentry/nextjs';

// DSN은 env로만 주입한다 — 미설정(로컬 dev 등)이면 SDK 전체가 no-op.
Sentry.init({
    dsn: process.env.NEXT_PUBLIC_SENTRY_DSN,
    enabled: !!process.env.NEXT_PUBLIC_SENTRY_DSN,
    tracesSampleRate: 0.1,
});

export const onRouterTransitionStart = Sentry.captureRouterTransitionStart;
