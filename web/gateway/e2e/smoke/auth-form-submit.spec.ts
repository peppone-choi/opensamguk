// 로그인 · 가입 폼 제출 방식 — 스크립트가 붙기 전(여기선 스크립트를 끈 채) 칸을 채우고 Enter 를 누르면 폼이 네이티브로 제출된다(@both).
// 제출은 POST 라 같은 주소가 405 · 403 · 500 없이 다시 그려지고, 입력값이 주소(기록 · 접근 로그의 질의)에 실리지 않는다.
import { expect, test, type Page, type Response } from '@playwright/test';
import { BOTH } from '../../../game/e2e/support/parity';

test.use({ javaScriptEnabled: false });

async function submitWithoutScript(page: Page, path: string, fields: Record<string, string>) {
  await page.goto(path);
  const form = page.locator('form.gw31-form');
  // soft: method 가 틀려도 아래 제출 · 주소 단언까지 가서 실제로 무엇이 새는지 보이게 한다.
  await expect.soft(form).toHaveAttribute('method', 'post', { timeout: 2_000 });
  for (const [name, value] of Object.entries(fields)) await page.locator(`input[name="${name}"]`).fill(value);
  const submitted = page.waitForResponse(
    (res: Response) => res.request().isNavigationRequest() && res.frame() === page.mainFrame(),
    { timeout: 15_000 },
  );
  await page.locator('input[name="password"]').press('Enter');
  const res = await submitted;
  await page.waitForLoadState('domcontentloaded');

  expect.soft(res.request().method(), '제출 방식').toBe('POST');
  expect(res.status(), '같은 화면이 오류 없이 다시 그려진다(405 · 403 · 500 아님)').toBe(200);
  const url = new URL(page.url());
  expect(url.pathname, '제출로 다른 주소로 가지 않는다').toBe(path);
  expect(url.search, '입력값이 주소에 실리지 않는다').not.toMatch(/password|smoke-pass|smoke-user/);
  await expect(form, '다시 그려진 화면에 폼이 있다').toBeVisible();
}

test.describe('스크립트 전 폼 제출 방식', () => {
  test('로그인: POST 로 다시 그려지고 주소에 입력값이 없다', { tag: BOTH }, async ({ page }) => {
    await submitWithoutScript(page, '/login', { username: 'smoke-user', password: 'smoke-pass' });
  });

  test('가입: POST 로 다시 그려지고 주소에 입력값이 없다', { tag: BOTH }, async ({ page }) => {
    await submitWithoutScript(page, '/join', { username: 'smoke-user', password: 'smoke-pass', passwordConfirm: 'smoke-pass', nickname: 'smoke' });
  });
});
