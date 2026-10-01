// K5 G05 캡처 전용(커밋하지 않는다). 스모크와 같은 합성 응답으로 production 빌드를 찍는다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { BOTH, isMobile } from '../../../game/e2e/support/parity';

const USER = { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
const DIR = process.env.CAPTURE_DIR ?? 'test-results';

/** 탈퇴 뒤 로그인 화면이 부르는 공개 경로 — 로비 스모크와 같은 모양(백엔드 없음). */
async function loginPageRoutes(page: Page) {
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [] })));
  await page.route('**/api/server-map/**', (route) => route.fulfill(json({ serverName: 'pep', year: 200, month: 3, mapCode: 'smoke-unsupported', width: 1, height: 1, cities: [], nations: [] })));
  await page.route('**/api/server-events/**', (route) => route.fulfill(json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
}

async function open(page: Page, baseURL: string | undefined) {
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: USER })));
  await page.route('**/api/account/representative', (route) => route.fulfill(json({
    current: { generalId: 1495, name: '하후돈', worldId: 1 },
    candidates: [{ generalId: 1495, name: '하후돈', worldId: 1, scenarioCode: 'scenario_1010' }, { generalId: 2001, name: '원양', worldId: 2, scenarioCode: null }],
  })));
  await page.route('**/api/account/password', (route) => route.fulfill(json({ error: '현재 비밀번호가 맞지 않습니다.' }, 400)));
  await page.goto('/account');
  await expect(page.getByRole('listbox', { name: '대표 장수' })).toBeVisible();
}

test('G05 캡처', { tag: BOTH }, async ({ page, baseURL }, info) => {
  const d = isMobile(info) ? 'mobile' : 'desktop';
  const shot = (name: string) => page.screenshot({ path: join(DIR, `${d}-${name}.png`), fullPage: true });
  await open(page, baseURL);
  await shot('g05-overview');

  const source = readFileSync(join(info.project.testDir, '../public/logo-wordmark.png'));
  await page.getByLabel('초상 이미지 파일').setInputFiles({ name: 'portrait.png', mimeType: 'image/png', buffer: source });
  await expect(page.getByRole('radiogroup', { name: '편집할 구도' })).toBeVisible();
  await page.getByRole('button', { name: '올리기', exact: true }).click({ force: true });
  await expect(page.getByRole('dialog', { name: /올리기/ })).toBeVisible();
  await page.screenshot({ path: join(DIR, `${d}-g05-reason.png`) });
  await page.keyboard.press('Escape');
  await page.getByRole('radio', { name: '카드' }).click();
  await page.getByRole('radio', { name: '아이콘' }).click();
  await page.locator('.gw31-account__portrait').screenshot({ path: join(DIR, `${d}-g05-editor.png`) });

  const pw = page.getByRole('region', { name: '비밀번호 바꾸기' });
  await pw.getByLabel('현재 비밀번호').fill('wrongpass');
  await pw.getByLabel('새 비밀번호', { exact: true }).fill('newpass1');
  await pw.getByLabel('새 비밀번호 확인').fill('newpass2');
  await pw.screenshot({ path: join(DIR, `${d}-g05-password-mismatch.png`) });
  await pw.getByLabel('새 비밀번호 확인').fill('newpass1');
  await pw.getByRole('button', { name: '바꾸기', exact: true }).click();
  await expect(pw.getByRole('alert')).toBeVisible();
  await pw.screenshot({ path: join(DIR, `${d}-g05-password-refused.png`) });

  const quit = page.getByRole('region', { name: '계정 탈퇴' });
  await quit.getByLabel('현재 비밀번호').fill('oldpass');
  await quit.getByRole('button', { name: '계정 삭제' }).click();
  await expect(page.getByRole('dialog', { name: '계정 탈퇴' })).toBeVisible();
  await page.screenshot({ path: join(DIR, `${d}-g05-quit-dialog.png`) });
});

test('G05 캡처 — 탈퇴 뒤 로그인', { tag: BOTH }, async ({ page }, info) => {
  const d = isMobile(info) ? 'mobile' : 'desktop';
  await loginPageRoutes(page);
  await page.goto('/login?notice=account-deleted');
  await expect(page.getByText('계정을 지웠습니다')).toBeVisible();
  await page.locator('#login-form').screenshot({ path: join(DIR, `${d}-g05-login-notice.png`) });
});
