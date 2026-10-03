import * as Sentry from '@sentry/nextjs';
import { scrubAuthFromEvent } from './lib/sentry-scrub';

const dsn = process.env.SENTRY_DSN || process.env.NEXT_PUBLIC_SENTRY_DSN;

Sentry.init({
    dsn,
    enabled: !!dsn,
    tracesSampleRate: 0.1,
    beforeSend: scrubAuthFromEvent,
});
