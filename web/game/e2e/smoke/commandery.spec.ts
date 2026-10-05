// 군 내정 현황(P-T03) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 현 목록(K4-11 첫 판)에 방침 · 공사 · 창고를 잇는다. 7지표 · 민심 위험 · 적 군단은 서버 보강 전까지 준비 중.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, coveredTargets, expectNoHorizontalOverflow, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const directory = (u: URL) => {
  const scope = u.searchParams.get('scope') ?? 'NATION';
  return {
    status: 'READY', scope, commandery: scope === 'COMMANDERY' ? { id: 'c-yc', name: '영천군' } : null, period: 'GAME_MONTH', basis: 'x', stamp: null,
    counties: [
      { cityId: 129, name: '양성현', commanderyId: 'c-yc', visibility: 'FULL', income: { money: 10, grain: 5 } },
      { cityId: 130, name: '허현', commanderyId: 'c-yc', visibility: 'FULL', income: { money: 20, grain: 8 } },
    ],
  };
};
const table = {
  '/api/front-info': frontInfo(),
  '/api/counties': directory,
  '/api/policies': {
    status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }, { code: 'TRADE', label: '상업' }], corpsOptions: [], defaultPolicy: null, corps: [],
    counties: [
      { countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null },
      { countyId: 130, name: '허현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null },
    ],
    commanderies: [{ commanderyId: 'c-yc', name: '영천군', countyIds: [129, 130], active: null, pending: null, settable: true, blocked: null }],
  },
  '/api/works': { status: 'READY', counties: [] },
  '/api/warehouses': { status: 'READY', warehouses: [] },
  '/api/commands/policy/set': { status: 'AVAILABLE' },
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(counties|policies|works|warehouses|commands)/;
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, [role="radio"], [role="option"]');

async function quality(page: Page, scope = 'main') {
  expect(await coveredIn(page.locator(scope).first())).toEqual([]);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, scope)).toEqual([]);
  expect(await titleOnlyInfo(page, scope)).toEqual([]);
}

test('한 군 — 현 표 · 현 상세 고리 · 군 방침 시트 접수, 범위를 우리 세력 전체로', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/territory/commandery/c-yc', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '영천군' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('link', { name: '양성현' }).first()).toHaveAttribute('href', /\/territory\/county\/129$/);
  await quality(page);
  await press(main.getByRole('button', { name: '군 방침 바꾸기' }), info);
  const sheet = page.getByRole('dialog', { name: '영천군 군 방침' });
  await press(sheet.getByRole('option', { name: '농업' }), info);
  expect(await coveredIn(sheet)).toEqual([]);
  await press(sheet.getByRole('button', { name: '이 방침으로' }), info);
  await expect(page.getByRole('status').filter({ hasText: '군 방침을' })).toContainText('군 방침을 접수했습니다');
  await press(main.getByRole('radiogroup', { name: '범위' }).getByRole('radio', { name: '우리 세력 전체' }), info);
  await expect(page.getByRole('heading', { name: '우리 세력 전체' })).toBeVisible();
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
});

test('탭 첫 화면(군 없음) — 우리 세력 전체만, 범위 · 첩보 단추 없음', { tag: [BOTH] }, async ({ page }) => {
  await serveCampaign(page, table);
  await page.goto('/game/territory/commandery', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { name: '우리 세력 전체' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('radiogroup', { name: '범위' })).toHaveCount(0);
  await expect(main.getByRole('button', { name: /첩보/ })).toHaveCount(0);
  await quality(page);
});
