// 공통 상태 화면(보드 P-X01, K3 2026-10-05) — /game 안의 없는 주소는 셸(머리줄 · 메뉴)을 남긴 채 본문만 「찾는 화면이 없습니다」.
import { expect, test } from '@playwright/test';
import { BOTH, press } from '../support/parity';

test('없는 화면: 셸 안에서 「찾는 화면이 없습니다」 · 작전실로 · 기록으로', { tag: [BOTH] }, async ({ page }, info) => {
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.endsWith('/front-info'), (r) => r.fulfill({ json: {
    result: true,
    global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
    nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
  } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/') && !url.pathname.endsWith('/front-info'), (r) => r.fulfill({ status: 503, json: {} }));

  await page.goto('/game/no-such-screen', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('status').filter({ hasText: '찾는 화면이 없습니다' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByRole('navigation', { name: '게임 메뉴' }).first()).toBeVisible(); // 셸은 그대로
  await expect(main.getByRole('link', { name: '기록으로' })).toHaveAttribute('href', '/game/pep/records');
  await press(main.getByRole('link', { name: '작전실로' }), info);
  await expect(page).toHaveURL(/\/game\/pep\/?$/);
});
