// Sentry 서버 · 엣지 보내기 전 — 쿠키 · 인증 머리글을 지운다(K3 2026-10-04, 원장 D90).
import { describe, expect, it } from 'vitest';
import type { ErrorEvent } from '@sentry/nextjs';
import { scrubAuthFromEvent } from '@/lib/sentry-scrub';

describe('scrubAuthFromEvent', () => {
    it('요청의 cookies 와 cookie · authorization · set-cookie 머리글(대소문자 무관)만 지운다', () => {
        const event = {
            type: undefined,
            request: {
                url: 'https://example.test/api/x',
                cookies: { sam_access: 'a', sam_refresh: 'r' },
                headers: { Cookie: 'sam_access=a', authorization: 'Bearer t', 'Set-Cookie': 'x=1', 'user-agent': 'ua', accept: 'text/html' },
            },
        } as unknown as ErrorEvent;
        const out = scrubAuthFromEvent(event);
        expect(out.request?.cookies).toBeUndefined();
        expect(out.request?.headers).toEqual({ 'user-agent': 'ua', accept: 'text/html' });
        expect(out.request?.url).toBe('https://example.test/api/x');
    });

    it('요청이 없는 이벤트는 그대로 둔다', () => {
        const event = { type: undefined, message: 'm' } as unknown as ErrorEvent;
        expect(scrubAuthFromEvent(event)).toEqual({ type: undefined, message: 'm' });
    });
});
