// @vitest-environment node
import { describe, expect, it } from 'vitest';
import { NextRequest } from 'next/server';
import { middleware } from '@/middleware';

const ORIGIN = 'http://gw.test';

type Init = { method?: string; headers?: Record<string, string>; body?: string };
// 브라우저처럼 본문이 있으면 content-length 를 함께 보낸다(NextRequest 는 스스로 붙이지 않는다).
const call = (path: string, init: Init = {}) => middleware(new NextRequest(`${ORIGIN}${path}`, {
    method: init.method ?? 'POST',
    headers: { ...(init.body === undefined ? {} : { 'content-length': String(Buffer.byteLength(init.body)) }), ...init.headers },
    body: init.body,
}));
const passes = (res: Response) => res.headers.get('x-middleware-next') === '1';
const json = { 'Content-Type': 'application/json' };

describe('비-GET 요청 확인 — 같은 출처 · JSON 본문', () => {
    it('같은 출처 · JSON 본문은 지난다(양성 대조)', () => {
        expect(passes(call('/api/auth/login', { headers: { origin: ORIGIN, ...json }, body: '{"a":1}' }))).toBe(true);
        expect(passes(call('/api/proxy/admin/notices/5', { method: 'PUT', headers: { origin: ORIGIN, ...json }, body: '{}' }))).toBe(true);
    });

    it('본문 없는 같은 출처 요청(로그아웃 · 지우기)은 Content-Type 없이 지난다', () => {
        expect(passes(call('/api/auth/logout', { headers: { origin: ORIGIN } }))).toBe(true);
        expect(passes(call('/api/board/posts/3', { method: 'DELETE', headers: { origin: ORIGIN } }))).toBe(true);
    });

    it('Origin 도 Sec-Fetch-Site 도 없는 요청(서버 간 호출)은 지난다', () => {
        expect(passes(call('/api/auth/refresh', { headers: json, body: '{}' }))).toBe(true);
    });

    it('GET 은 확인하지 않는다', () => {
        expect(passes(call('/api/board/posts', { method: 'GET', headers: { origin: 'http://other.example' } }))).toBe(true);
    });

    it.each([
        [{ origin: 'http://other.example' }],
        [{ origin: 'null' }],
        [{ origin: 'http://gw.test.other.example' }],
        [{ 'sec-fetch-site': 'cross-site' }],
        [{ 'sec-fetch-site': 'same-site' }],
    ])('다른 출처 %j 는 403', (headers) => {
        const res = call('/api/auth/login', { headers: { ...headers, ...json }, body: '{}' });
        expect(res.status).toBe(403);
    });

    it('Sec-Fetch-Site 가 있으면 그것으로 판정한다(Host 머리글과 무관)', () => {
        expect(passes(call('/api/auth/login', { headers: { 'sec-fetch-site': 'same-origin', ...json }, body: '{}' }))).toBe(true);
        expect(passes(call('/api/auth/login', { headers: { 'sec-fetch-site': 'same-origin', origin: 'https://sam.example', ...json }, body: '{}' }))).toBe(true);
        expect(call('/api/auth/login', { headers: { 'sec-fetch-site': 'cross-site', origin: ORIGIN, ...json }, body: '{}' }).status).toBe(403);
    });

    it.each([
        ['text/plain;charset=UTF-8'],
        ['application/x-www-form-urlencoded'],
        ['multipart/form-data; boundary=x'],
    ])('같은 출처라도 본문 Content-Type %s 는 415', (contentType) => {
        const res = call('/api/auth/login', { headers: { origin: ORIGIN, 'content-type': contentType }, body: 'a=1' });
        expect(res.status).toBe(415);
    });

    it('초상 올리기(multipart)는 Origin 만 본다', () => {
        const ct = { 'content-type': 'multipart/form-data; boundary=x' };
        expect(passes(call('/api/account/profile-icon', { headers: { origin: ORIGIN, ...ct }, body: '--x--' }))).toBe(true);
        expect(call('/api/account/profile-icon', { headers: { origin: 'http://other.example', ...ct }, body: '--x--' }).status).toBe(403);
    });

    it('보호 경로 · 손님 전용 동작은 그대로다', () => {
        const res = middleware(new NextRequest(`${ORIGIN}/account`));
        expect(res.headers.get('location')).toBe(`${ORIGIN}/login?next=%2Faccount`);
    });
});
