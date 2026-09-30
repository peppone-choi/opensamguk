// P-G04 로비(게이트웨이) — 데스크톱 · 모바일 같은 흐름 스모크(@both). 백엔드 없이 돈다(page.route).
// 미들웨어는 세션 쿠키가 있는지만 본다 — 합성 쿠키를 넣고, /api/auth/me 가 합성 사용자를 돌려준다.
// 서버 목록은 SERVER_REGISTRY_JSON(pep · uni) — 비면 건너뛰지 않고 실패한다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, titleOnlyInfo } from '../../../game/e2e/support/parity';
import { smallHitAreas } from '../support/hitArea';

const GAME = {
  isUnited: 0, year: 200, month: 3, turnPhaseText: '중순', scenario: '군웅할거', maxUserCnt: 30, turnTerm: 10,
  userCnt: 24, npcCnt: 412, nationCnt: 5, blockGeneralCreate: 0, status: 'OPEN', catchUp: { active: false, multiplier: 2 },
};

async function open(page: Page, baseURL: string | undefined) {
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
  await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 } })));
  await page.route('**/api/server-basic-info/**', (route) => route.fulfill(route.request().url().includes('/pep')
    ? json({ game: GAME, me: { name: '하후돈', picture: null, imageServer: 0 } })
    : json({ game: { ...GAME, userCnt: 30 }, me: null })));
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [] })));
  await page.route('**/api/server-map/**', (route) => route.fulfill(json({ serverName: 'pep', year: 200, month: 3, mapCode: 'smoke-unsupported', width: 1, height: 1, cities: [], nations: [] })));
  await page.route('**/api/server-events/**', (route) => route.fulfill(json({ events: [] })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
  await page.goto('/lobby');
  await expect(page.getByRole('heading', { level: 1, name: '게임 로비' })).toBeVisible();
  await expect(page.getByRole('article', { name: 'pep' }), '서버 목록이 비었다 — SERVER_REGISTRY_JSON 으로 게이트웨이를 띄웠는지 확인').toBeVisible();
}

test.describe('P-G04 로비 — 데스크톱 · 모바일 같은 흐름', () => {
  test('그려진다: 카드 · 판정 · 가로 넘침 없음 · 누를 영역 44', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    const pep = page.getByRole('article', { name: 'pep' });
    await expect(pep.getByText('참가 중')).toBeVisible();
    await expect(pep.getByRole('link', { name: '입장' })).toBeVisible();
    const uni = page.getByRole('article', { name: '통일 서버' });
    await expect(uni.getByText('마감')).toBeVisible();
    await expect(uni.getByRole('button', { name: '장수 만들기' })).toHaveAttribute('aria-disabled', 'true');
    // 각주 초안 칩은 모바일에서도 보인다(로그인 소개의 초안 칩 규칙을 빌려 모바일에서 숨던 것을 막는다).
    await expect(page.getByText('문구 초안 — 공개 알파 문구와 함께 승인')).toBeVisible();
    await expectNoHorizontalOverflow(page);
    expect(await smallHitAreas(page, 'body')).toEqual([]);
    expect(await titleOnlyInfo(page), 'title 전용 정보 금지').toEqual([]);
  });

  test('거르기 · 현황 펼치기', { tag: BOTH }, async ({ page, baseURL }) => {
    await open(page, baseURL);
    await expect(page.getByRole('article', { name: '통일 서버' }).getByText('마감')).toBeVisible();
    await page.getByRole('button', { name: '참가 가능' }).click();
    await expect(page.getByRole('article', { name: '통일 서버' })).toBeHidden();
    await page.getByRole('button', { name: '전체' }).click();
    const pep = page.getByRole('article', { name: 'pep' });
    await pep.getByRole('button', { name: '현황 펼치기' }).click();
    await expect(pep.getByRole('region', { name: '세력 현황' })).toBeVisible();
    await expect(pep.getByRole('region', { name: '천하 정세' }).getByText('아직 공개된 사건이 없습니다')).toBeVisible();
  });

  test('머리줄 — 데스크톱은 메뉴가 보이고, 모바일은 「메뉴」 시트', { tag: BOTH }, async ({ page, baseURL }, testInfo) => {
    await open(page, baseURL);
    if (isMobile(testInfo)) {
      await page.getByRole('button', { name: '메뉴' }).click();
      const sheet = page.getByRole('dialog', { name: '메뉴' });
      await expect(sheet.getByRole('link', { name: '커뮤니티' })).toBeVisible();
      await expect(sheet.getByRole('button', { name: '로그아웃' })).toBeVisible();
      expect(await smallHitAreas(page, '.gw31-sheet')).toEqual([]);
      await page.keyboard.press('Escape');
      await expect(sheet).toBeHidden();
    } else {
      const nav = page.getByRole('navigation', { name: '게이트웨이 메뉴' });
      await expect(nav.getByRole('link', { name: '로비' })).toHaveAttribute('aria-current', 'page');
      await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
    }
  });
});
