// P-G01 게이트웨이 `/` — 이동만 하는 화면(app/page.tsx). 세션 쿠키가 없으면 로그인, 있으면 로비로 보낸다(@both).
// 화면을 그리지 않으므로 이동 응답(상태 · Location)과 실제 도착 주소를 잰다.
import { expect, test } from '@playwright/test';
import { BOTH } from '../../../game/e2e/support/parity';

test.describe('게이트웨이 / — 이동 대상', () => {
    test('세션 쿠키가 없으면 /login', { tag: BOTH }, async ({ page }) => {
        const res = await page.request.get('/', { maxRedirects: 0 });
        expect([303, 307, 308]).toContain(res.status());
        expect(new URL(res.headers()['location'] ?? '', 'http://x').pathname).toBe('/login');
        await page.goto('/', { waitUntil: 'domcontentloaded' });
        await expect(page).toHaveURL(/\/login$/);
    });

    test('세션 쿠키(sam_access)가 있으면 /lobby', { tag: BOTH }, async ({ page }) => {
        const res = await page.request.get('/', { maxRedirects: 0, headers: { cookie: 'sam_access=smoke' } });
        expect([303, 307, 308]).toContain(res.status());
        expect(new URL(res.headers()['location'] ?? '', 'http://x').pathname).toBe('/lobby');
    });
});
