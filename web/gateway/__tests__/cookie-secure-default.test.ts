// 쿠키 Secure 기본값(K3 2026-10-04, 원장 D90) — 운영 빌드는 켜고, COOKIE_SECURE 를 주면 그 값이 이긴다.
import { describe, expect, it } from 'vitest';
import { cookieSecureFrom } from '@/lib/cookies';

describe('cookieSecureFrom', () => {
    it('운영 빌드(NODE_ENV=production)는 COOKIE_SECURE 가 없어도 켠다', () => {
        expect(cookieSecureFrom({ NODE_ENV: 'production' })).toBe(true);
    });
    it('HTTP 로 띄우는 운영 빌드(로컬 compose · CI 스모크)는 COOKIE_SECURE=false 로 끈다', () => {
        expect(cookieSecureFrom({ NODE_ENV: 'production', COOKIE_SECURE: 'false' })).toBe(false);
    });
    it('개발 · 시험 빌드는 기본 끔, COOKIE_SECURE=true 면 켠다', () => {
        expect(cookieSecureFrom({ NODE_ENV: 'development' })).toBe(false);
        expect(cookieSecureFrom({ NODE_ENV: 'test' })).toBe(false);
        expect(cookieSecureFrom({ NODE_ENV: 'development', COOKIE_SECURE: 'true' })).toBe(true);
    });
    it('알 수 없는 값은 짐작하지 않고 빌드 기본값을 따른다', () => {
        expect(cookieSecureFrom({ NODE_ENV: 'production', COOKIE_SECURE: 'yes' })).toBe(true);
        expect(cookieSecureFrom({ NODE_ENV: 'development', COOKIE_SECURE: '1' })).toBe(false);
    });
});
