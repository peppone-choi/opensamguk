// 공통 상태 화면(보드 P-X01, K3 2026-10-05) — 맞는 화면이 없는 주소는 진짜 HTTP 404 로 셸 밖 「찾는 화면이 없습니다」.
// 셸 안 404(app/game/not-found.tsx)는 AuthGate 가 서버 렌더에서 화면을 그리지 않아 200 이 되므로, 없는 주소는 뿌리
// not-found 가 받는다(셸 스모크 「없는 화면 … 404」와 같은 약속). 셸 안 404 화면은 vitest page-states 가 본다.
import { expect, test } from '@playwright/test';
import { BOTH, press } from '../support/parity';

test('없는 화면: 진짜 404 로 「찾는 화면이 없습니다」 · 작전실로', { tag: [BOTH] }, async ({ page }, info) => {
  // 작전실로 간 뒤 AuthGate 가 로그인으로 튕기지 않게(백엔드 없는 스모크).
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/game/') || url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 503, json: {} }));
  const res = await page.goto('/game/no-such-screen', { waitUntil: 'domcontentloaded' });
  expect(res?.status()).toBe(404);
  await expect(page.getByRole('status').filter({ hasText: '찾는 화면이 없습니다' })).toBeVisible();
  // 셸(AuthGate · 게임 메뉴) 밖이다 — 셸 안으로 끌어오면 200 이 된다.
  await expect(page.getByRole('navigation', { name: '게임 메뉴' })).toHaveCount(0);
  const home = page.getByRole('link', { name: '작전실로' });
  await expect(home).toHaveAttribute('href', '/game');
  await press(home, info);
  await expect(page).toHaveURL(/\/game(\/[a-z0-9]+)?\/?$/);
});
