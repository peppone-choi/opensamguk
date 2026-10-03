import type { ErrorEvent, EventHint } from '@sentry/nextjs';

const SENSITIVE_HEADER = /^(cookie|authorization|set-cookie)$/i;

/**
 * 서버 · 엣지 Sentry 보내기 전 — 요청의 쿠키와 인증 머리글을 지운다(로그인 토큰이 오류 보고에 실리지 않게).
 * 클라이언트 쪽은 브라우저가 이 머리글을 붙이지 않으므로 서버 · 엣지만 건다.
 */
export function scrubAuthFromEvent(event: ErrorEvent, _hint?: EventHint): ErrorEvent {
    const request = event.request;
    if (!request) return event;
    delete request.cookies;
    if (request.headers) {
        for (const key of Object.keys(request.headers)) {
            if (SENSITIVE_HEADER.test(key)) delete request.headers[key];
        }
    }
    return event;
}
