// /game/{id} 공개 전 화면(D112 ② A안 · D120) — 껍데기는 뜨고, 첫 front-info 가 game-api admission 에 막히면 게임 화면 대신 그 화면만(@both).
//  403 SERVER_NOT_PUBLIC → 「이 서버는 지금 공개되지 않았습니다」 + 로비로, 503 SERVER_ADMISSION_UNAVAILABLE → 「확인하지 못했습니다」 + 다시.
//  공개 전으로 판정되면 셸(머리줄 · 메뉴 · 턴 SSE)을 그리지 않는다. 첫 응답 전에는 셸이 잠깐 보일 수 있다(UNKNOWN 세션 셸 규칙 — GameFrame 시험).
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, smallTouchTargets } from '../support/parity';

async function serve(page: Page, frontInfo: { status: number; body: unknown }) {
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  // game-api admission 은 /api/** 전부를 같은 거절로 막는다 — 흉내도 모든 게임 API 에 같은 응답(front-info 포함)
  await page.route((url) => url.pathname.startsWith('/api/game/'), (route) =>
    route.fulfill({ status: frontInfo.status, contentType: 'application/json', body: JSON.stringify(frontInfo.body) }));
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
