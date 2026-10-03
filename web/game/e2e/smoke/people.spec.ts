// 인물 일람(P-R02) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 정렬 · 초성 찾기는 서버(#1103)라, 화면이 정렬 키 · 방향을 서버에 실어 다시 읽는지만 본다. 공용 픽스처는 고치지 않는다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo, coveredTargets } from '../support/parity';

const stats = (n: number) => ({ leadership: n, strength: n - 1, intel: n - 2, politics: n - 3, charm: n - 4 });
const aptitudes = { command: 60, administration: 40, strategy: 30, envoy: 20 };
const person = (generalId: number, name: string, nation: string | null, s: number | null) => ({
  generalId, name, portrait: { picture: null, imageServer: 0 },
  affiliation: nation ? { nationId: 1, name: nation, color: '#4f7fbf' } : null, role: null, lordGeneralId: null,
  stats: s == null ? null : stats(s), aptitudes: s == null ? null : aptitudes, locationCityId: 3, bonds: s == null ? null : [],
});
const table = {
  '/api/front-info': frontInfo(),
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, cities: [{ id: 3, name: '허현' }], nations: [] },
  // 정렬을 실어 오면 그 순서를 서버가 준 것처럼 돌려준다(화면은 받은 순서 그대로 그린다).
  '/api/people': (url: URL) => ({
    status: 'READY', nextCursor: null,
    people: url.searchParams.get('sort') === 'INTEL'
      ? [person(9, '사마의중달', '조조', 90), person(7, '하후돈', '조조', 70), person(11, '무명 재야', null, null)]
      : [person(7, '하후돈', '조조', 70), person(9, '사마의중달', '조조', 90), person(11, '무명 재야', null, null)],
  }),
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(people|map\/preview)/;

/** 본문 왼쪽 여백 — 셸(GameShell .body)이 주는 12와 정확히 같다(화면 루트가 또 주면 24로 겹친다, #1133). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 덮임 — 공용 coveredTargets(support/parity, 한 화면씩 내려가며 · 붙박인 층은 스크롤해 다시)로 옮겼다(K10 10-02). */
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, select, input, [role="radio"]');

test('목록 · 미리보기(데) / 카드 → 인물 상세(모) — 덮임 · 넘침 0, 44 · title 전용 · 영어 원문 0, 여백 12', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/retinue/people', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '인물 일람' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).toContainText('사마의중달');
  await expect(main).toContainText('허현'); // 소재 城 이름(지도 미리보기 이름)
  // 거르기 줄 상자가 셸 여백 12에 붙는다. 줄 안 여백(보드 filt padding 10 12)은 줄의 몫이라 범위 단추 자체는 24다.
  expect(await insetFromMain(page, page.getByRole('radiogroup', { name: '범위' }).locator('..'))).toBe(12);
  expect(await coveredIn(main)).toEqual([]);
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  expect(await main.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
  if (isMobile(info)) {
    // 카드를 누르면 인물 상세(P-R03) 전체 화면 — 나(하후돈)는 front-info 로 채운다.
    await press(main.getByRole('link', { name: /하후돈/ }), info);
    await page.waitForURL(/\/retinue\/people\/7$/);
    await expect(page.getByRole('heading', { name: '하후돈', exact: true })).toBeVisible({ timeout: 60_000 });
  } else {
    const preview = page.getByRole('complementary', { name: '미리보기' });
    await expect(preview).toContainText('하후돈');
    // 미리보기의 「인물 상세 열기」 → 인물 상세(P-R03)
    await expect(preview.getByRole('link', { name: '인물 상세 열기' })).toHaveAttribute('href', /\/retinue\/people\/7$/);
  }
});

test('정렬 — 키 · 방향을 서버에 싣고 받은 순서대로 그린다', { tag: [BOTH] }, async ({ page }) => {
  await serveCampaign(page, table);
  await page.goto('/game/retinue/people', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '인물 일람' })).toBeVisible({ timeout: 60_000 });
  const sent = page.waitForRequest((r) => {
    const u = new URL(r.url());
    return u.pathname === '/api/game/api/people' && u.searchParams.get('sort') === 'INTEL' && u.searchParams.get('direction') === 'DESC';
  });
  await page.getByRole('combobox', { name: '정렬' }).selectOption('INTEL');
  await sent;
  const names = page.getByRole('main', { name: '게임 콘텐츠' }).locator('.os-serif');
  await expect(names.first()).toHaveText('사마의중달');
});
