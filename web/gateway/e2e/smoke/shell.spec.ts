// 게이트웨이 smoke 틀 — 백엔드 없이 Next 서버만으로 돈다. 로그인 · 가입 · 로비 흐름은 각 화면 레인이 이 폴더에 더한다.
import { expect, test } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow } from '../../../game/e2e/support/parity';

test('없는 주소는 404 로 그리고 가로로 넘치지 않는다', { tag: BOTH }, async ({ page }) => {
  const res = await page.goto('/smoke-no-such-page', { waitUntil: 'domcontentloaded' });
  expect(res?.status()).toBe(404);
  await expectNoHorizontalOverflow(page);
});
