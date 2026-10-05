// /game/{id} 공개 전 화면(D112 ② A안 · D120) — 껍데기는 뜨고, 첫 front-info 가 game-api admission 에 막히면 게임 화면 대신 그 화면만(@both).
//  403 SERVER_NOT_PUBLIC → 「이 서버는 지금 공개되지 않았습니다」 + 로비로, 503 SERVER_ADMISSION_UNAVAILABLE → 「확인하지 못했습니다」 + 다시.
//  그 사이 셸(머리줄 · 메뉴)은 그리지 않는다 — 열리지 않은 서버를 셸이 부르지 않는다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, smallTouchTargets } from '../support/parity';

async function serve(page: Page, frontInfo: { status: number; body: unknown }) {
  const called: string[] = [];
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/'), (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/api\/game/, '');
    called.push(path);
    if (path === '/api/front-info') return route.fulfill({ status: frontInfo.status, contentType: 'application/json', body: JSON.stringify(frontInfo.body) });
    return route.fulfill({ status: frontInfo.status, contentType: 'application/json', body: JSON.stringify(frontInfo.body) });
  });
  return called;
}

test.describe('공개 전 화면', () => {
  test('403 SERVER_NOT_PUBLIC — 「공개되지 않았습니다」 + 로비로, 셸 없음', { tag: BOTH }, async ({ page }) => {
    await serve(page, { status: 403, body: { error: { code: 'SERVER_NOT_PUBLIC', message: '공개 전' } } });
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('이 서버는 지금 공개되지 않았습니다')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByRole('link', { name: /로비로/ })).toHaveAttribute('href', /\/lobby$/);
    await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
    expect(await smallTouchTargets(page, 'main')).toEqual([]);
    await expectNoHorizontalOverflow(page);
  });

  test('503 SERVER_ADMISSION_UNAVAILABLE — 「확인하지 못했습니다」', { tag: BOTH }, async ({ page }) => {
    await serve(page, { status: 503, body: { error: { code: 'SERVER_ADMISSION_UNAVAILABLE', message: '확인 불가' } } });
    await page.goto('/game', { waitUntil: 'domcontentloaded' });
    await expect(page.getByText('서버 공개 상태를 확인하지 못했습니다')).toBeVisible({ timeout: 60_000 });
    await expect(page.getByText('이 서버는 지금 공개되지 않았습니다')).toHaveCount(0);
    await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
  });
});
