// P-G03 가입(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(지도 미리보기는 page.route).
// 게이트웨이 틀(K3): web/gateway/playwright.config.ts 의 desktop · mobile 프로젝트, baseURL = E2E_GATEWAY_URL.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

async function open(page: Page) {
  // 지도 판은 일부러 모르는 판 — 지형을 받지 않는다(캔버스 그림은 지도 레인 스모크가 본다).
  await page.route('**/api/server-map/**', (route) => route.fulfill({ status: 200, contentType: 'application/json',
    body: JSON.stringify({ serverName: 'pep', year: 200, month: 3, mapCode: 'smoke-unsupported', width: 1, height: 1, cities: [], nations: [] }) }));
  await page.goto('/join');
  await expect(page.getByRole('heading', { level: 1, name: '회원 가입' })).toBeVisible();
}

test.describe('P-G03 가입 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 계정 안내 · 가입 패널 · 정책, 가로 넘침 없음, 누를 영역 44', { tag: BOTH }, async ({ page }) => {
    await open(page);
    await expect(page.getByRole('region', { name: '계정 안내' })).toBeVisible();
    await expect(page.getByText('선택', { exact: true })).toBeVisible();
    await expect(page.getByRole('navigation', { name: '정책' }).getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy');
    await expect(page.getByAltText('오픈삼국')).toHaveCount(1);
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'main')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('가입 폼: 비밀번호가 다르면 확인 칸 아래 오류 · 표시는 두 칸을 함께', { tag: BOTH }, async ({ page }) => {
    await open(page);
    const card = page.getByRole('region', { name: '회원 가입' });
    await card.getByRole('button', { name: '회원가입', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('계정명을 입력하세요');
    await card.getByLabel('계정명').fill('hahoudon');
    await card.getByLabel('비밀번호', { exact: true }).fill('secret1');
    await card.getByLabel('비밀번호 확인').fill('secret2');
    await card.getByRole('button', { name: '회원가입', exact: true }).click();
    await expect(card.getByRole('alert')).toHaveText('비밀번호가 서로 다릅니다.');
    await expect(card.getByLabel('비밀번호 확인')).toHaveAttribute('aria-invalid', 'true');
    await card.getByRole('button', { name: '표시' }).click();
    await expect(card.getByLabel('비밀번호 확인')).toHaveAttribute('type', 'text');
  });
});
