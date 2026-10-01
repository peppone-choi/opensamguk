// P-G05 계정(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 미들웨어는 세션 쿠키가 있는지만 본다 — 합성 쿠키를 넣고, /api/auth/me 가 합성 사용자를 돌려준다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const USER = { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });

/** 원본 이미지 — 공용 로고(1200×448 png). 64~8192px · 8MB 이하라 편집기가 받는다. */
function sourceImage(info: TestInfo) {
  return { name: 'portrait.png', mimeType: 'image/png', buffer: readFileSync(join(info.project.testDir, '../public/logo-wordmark.png')) };
}

/** 탈퇴 뒤 로그인 화면이 부르는 공개 경로 — 로비 스모크와 같은 모양(백엔드 없음). */
async function loginPageRoutes(page: Page) {
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [] })));
  await page.route('**/api/server-map/**', (route) => route.fulfill(json({ serverName: 'pep', year: 200, month: 3, mapCode: 'smoke-unsupported', width: 1, height: 1, cities: [], nations: [] })));
  await page.route('**/api/server-events/**', (route) => route.fulfill(json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
}

async function open(page: Page, baseURL: string | undefined, user: Record<string, unknown> = USER) {
  let me = user;
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: me })));
  await page.route('**/api/account/representative', (route) => route.fulfill(json({
    current: { generalId: 1495, name: '하후돈', worldId: 1 },
    candidates: [{ generalId: 1495, name: '하후돈', worldId: 1, scenarioCode: 'scenario_1010' }],
  })));
  await page.route('**/api/account/profile-icon', (route) => {
    me = { ...me, picture: 'a1b2c3d4.portrait', imageServer: 1 };
    return route.fulfill(json(me));
  });
  await page.goto('/account');
  await expect(page.getByRole('heading', { level: 1, name: '계정 설정' })).toBeVisible();
  await expect(page.getByRole('listbox', { name: '대표 장수' })).toBeVisible();
}

test.describe('P-G05 계정 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 패널 다섯 · 삼모 공유 칸 없음 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    for (const name of ['초상', '별명 바꾸기', '비밀번호 바꾸기', '대표 장수', '계정 탈퇴']) {
      await expect(page.getByRole('heading', { level: 2, name })).toBeVisible();
    }
    await expect(page.getByLabel('별명', { exact: true })).toHaveValue('원양');
    const reps = page.getByRole('listbox', { name: '대표 장수' }).getByRole('option');
    await expect(reps).toHaveCount(2);
    await expect(reps.first()).toContainText('서버 정보 준비 중');
    await expect(page.getByText(/전콘|닉네임|파일명|월드 1|scenario_/)).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('모바일은 초상부터 쌓고, 데스크톱은 왼쪽 열 400 + 오른쪽 초상', { tag: BOTH }, async ({ page, baseURL }, info) => {
    await open(page, baseURL);
    const box = async (name: string) => (await page.getByRole('heading', { level: 2, name }).locator('xpath=ancestor::section[1]').boundingBox())!;
    const portrait = await box('초상');
    const nickname = await box('별명 바꾸기');
    if (isMobile(info)) {
      expect(portrait.y).toBeLessThan(nickname.y);
      expect(Math.round(portrait.width)).toBe(Math.round(nickname.width));
    } else {
      expect(Math.round(nickname.width)).toBe(400);
      expect(portrait.x).toBeGreaterThan(nickname.x + 400);
    }
  });

  test('초상: 세 구도를 보기 전엔 사유 시트, 본 뒤 올리기', { tag: BOTH }, async ({ page, baseURL }, info) => {
    await open(page, baseURL);
    await page.getByLabel('초상 이미지 파일').setInputFiles(sourceImage(info));
    const upload = page.getByRole('button', { name: '올리기', exact: true });
    await expect(page.getByRole('radiogroup', { name: '편집할 구도' })).toBeVisible();
    await expect(upload).toHaveAttribute('aria-disabled', 'true');
    // 사유 단추는 aria-disabled 다 — Playwright 는 이를 잠긴 것으로 보고 기다리므로 force 로 누른다(누르면 사유가 열려야 한다).
    await upload.click({ force: true });
    const sheet = page.getByRole('dialog', { name: /올리기/ });
    await expect(sheet.getByText('세 구도를 확인하세요')).toBeVisible();
    await expect(sheet.getByText('큰 그림 · 카드 · 아이콘을 한 번씩 눌러 구도를 맞추면 올릴 수 있습니다.')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(sheet).toHaveCount(0);

    // 편집기가 열린 채로 잰다 — 구도 단추 · 슬라이더 · 초기화도 44.
    const stage = (await page.locator('.portrait-editor__stage').boundingBox())!;
    if (isMobile(info)) {
      expect(Math.round(stage.width)).toBe(Math.round(stage.height));
      expect(stage.width).toBeLessThanOrEqual(358);
    } else {
      expect([Math.round(stage.width), Math.round(stage.height)]).toEqual([300, 380]);
    }
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);

    await page.getByRole('radio', { name: '카드' }).click();
    await page.getByRole('radio', { name: '아이콘' }).click();
    await expect(upload).not.toHaveAttribute('aria-disabled', 'true');
    const sent = page.waitForRequest((r) => r.url().endsWith('/api/account/profile-icon') && r.method() === 'POST');
    await upload.click();
    const form = (await sent).postData() ?? '';
    expect(form).toContain('name="crops"');
    await expect(page.getByRole('status').filter({ hasText: '초상을 올렸습니다.' })).toBeVisible();
  });

  test('비밀번호: 확인 칸이 다르면 사유, 서버가 거절하면 받은 문장', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await page.route('**/api/account/password', (route) => route.fulfill(json({ error: '현재 비밀번호가 맞지 않습니다.' }, 400)));
    const panel = page.getByRole('region', { name: '비밀번호 바꾸기' });
    await panel.getByLabel('현재 비밀번호').fill('wrongpass');
    await panel.getByLabel('새 비밀번호', { exact: true }).fill('newpass1');
    await panel.getByLabel('새 비밀번호 확인').fill('newpass2');
    const submit = panel.getByRole('button', { name: '바꾸기', exact: true });
    await expect(submit).toHaveAttribute('data-reason', '비밀번호가 서로 다릅니다.');
    await panel.getByLabel('새 비밀번호 확인').fill('newpass1');
    await submit.click();
    await expect(panel.getByRole('alert')).toHaveText('현재 비밀번호가 맞지 않습니다.');
  });

  test('탈퇴: 자기 비밀번호 칸 → 확인 대화상자 → 로그인 화면 「계정을 지웠습니다」', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await page.route('**/api/account', async (route) => {
      expect(route.request().method()).toBe('DELETE');
      expect(route.request().postDataJSON()).toEqual({ currentPassword: 'oldpass' });
      await page.context().clearCookies();
      await route.fulfill(json({ deleted: true }));
    });
    await loginPageRoutes(page);
    const quit = page.getByRole('region', { name: '계정 탈퇴' });
    await expect(quit.getByRole('button', { name: '계정 삭제' })).toHaveAttribute('data-reason', '현재 비밀번호를 쓰세요');
    await quit.getByLabel('현재 비밀번호').fill('oldpass');
    await quit.getByRole('button', { name: '계정 삭제' }).click();
    const dialog = page.getByRole('dialog', { name: '계정 탈퇴' });
    await expect(dialog).toContainText('계정을 삭제하면 되돌릴 수 없습니다. 현재 비밀번호로 탈퇴하시겠습니까?');
    await dialog.getByRole('button', { name: '계정 삭제' }).click();
    await expect(page).toHaveURL(/\/login\?notice=account-deleted$/);
    await expect(page.getByRole('status').filter({ hasText: '계정을 지웠습니다' })).toBeVisible();
  });
});
