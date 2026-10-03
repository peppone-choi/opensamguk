// 로그인 · 가입 폼 제출 방식 — 스크립트가 붙기 전(여기선 스크립트를 끈 채)에는 폼이 제출되지 않고, 입력값이 주소에 실리지 않는다(@both).
// 칸 묶음(fieldset)이 네이티브 disabled 라 입력 · Enter · 클릭이 모두 막힌다. 칸이 열려 있으면(옛 화면) 채우고 Enter 를 눌러 본다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH } from '../../../game/e2e/support/parity';

test.use({ javaScriptEnabled: false });

async function trySubmit(page: Page, path: string, fields: Record<string, string>) {
  await page.goto(path);
  const form = page.locator('form.gw31-form');
  await expect(form).toHaveAttribute('method', 'post');
  const first = page.locator(`input[name="${Object.keys(fields)[0]}"]`);
  await expect(first).toBeVisible();
  if (await first.isEnabled()) {
    for (const [name, value] of Object.entries(fields)) await page.locator(`input[name="${name}"]`).fill(value);
    await page.locator('input[name="password"]').press('Enter');
  }
  await form.locator('button[type="submit"]').click({ force: true, timeout: 2_000 }).catch(() => {});
  await page.waitForLoadState('domcontentloaded');
  await page.waitForTimeout(500);
  const url = new URL(page.url());
  expect(url.pathname, '제출로 다른 주소로 가지 않는다').toBe(path);
  expect(url.search, '입력값이 주소에 실리지 않는다').not.toMatch(/password|smoke-pass/);
}

test.describe('스크립트 전 폼 제출 방식', () => {
  test('로그인: 칸이 잠겨 제출되지 않고 주소에 입력값이 없다', { tag: BOTH }, async ({ page }) => {
    await trySubmit(page, '/login', { username: 'smoke-user', password: 'smoke-pass' });
    await expect(page.locator('form.gw31-form fieldset')).toBeDisabled();
  });

  test('가입: 칸이 잠겨 제출되지 않고 주소에 입력값이 없다', { tag: BOTH }, async ({ page }) => {
    await trySubmit(page, '/join', { username: 'smoke-user', password: 'smoke-pass', passwordConfirm: 'smoke-pass', nickname: 'smoke' });
    await expect(page.locator('form.gw31-form fieldset')).toBeDisabled();
  });
});
