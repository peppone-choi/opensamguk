// 영지 — 배치 · 방침 · 공사(P-T01) 스모크. 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 공용 픽스처(e2e/support/campaignFixtures)는 고치지 않고, 이 화면만 쓰는 조회(방침 · 공사 · 창고망 · 도로)는 여기 표에 둔다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, posts, retinue, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo, coveredTargets } from '../support/parity';

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const table = {
  '/api/front-info': frontInfo(),
  '/api/posts': posts('full'),
  '/api/retinue': retinue('full'),
  '/api/policies': {
    status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }, { code: 'TRADE', label: '상업' }], corpsOptions: [], defaultPolicy: null, corps: [],
    counties: [
      { countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null },
      { countyId: 130, name: '허현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: false,
        blocked: { code: 'NOT_YOUR_COUNTY', reason: '우리 현이 아닙니다.' } },
    ],
  },
  '/api/works': {
    status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: ['p-yang'], name: '양성현', commanderyName: '영천군', warehouse: null, active: null,
      completed: [], startable: [
        { work: 'ROAD', label: '도로', available: true, blocked: null, cost: zero, estimatedPhases: 9 },
        { work: 'IRRIGATION', label: '수리', available: true, blocked: null, cost: zero, estimatedPhases: 1 },
      ] }],
  },
  '/api/warehouses': { status: 'READY', warehouses: [{ cityId: 3, name: '허현', commanderyName: null, isCapital: true, supplied: true, stock: { ...zero, money: 900 } }] },
  '/api/road-forts': { status: 'READY', roadMode: true, forts: [], gates: [] },
  '/api/commands/policy/set': { status: 'AVAILABLE' },
  '/api/commands/work/start': { status: 'AVAILABLE' },
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(posts|policies|works|warehouses|road-forts|retinue|commands)/;

/** 본문 왼쪽 여백 — 셸(GameShell .body)이 주는 12와 정확히 같다(화면 루트가 또 주면 24로 겹친다, #1133). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 덮임 — 공용 coveredTargets(support/parity, 한 화면씩 내려가며 · 붙박인 층은 스크롤해 다시)로 옮겼다(K10 10-02). */
const coveredIn = (root: Locator): Promise<string[]> => coveredTargets(root, 'a, button, [role="radio"], [role="option"]');

test('세 칸 · 창고망 띠 · 방침 시트 접수 — 덮임 · 넘침 0, 44 · title 전용 · 영어 원문 0, 여백', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/territory', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '영지' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main).toContainText('금 900');
  await expect(main).not.toContainText('출병'); // 출병은 명령 흐름 · 군단으로 옮겼다(설계 P-T01).
  if (isMobile(info)) {
    const seg = page.getByRole('radiogroup', { name: '보기' });
    expect(await insetFromMain(page, seg)).toBe(12);
    await press(seg.getByRole('radio', { name: '방침' }), info);
  } else {
    expect(await insetFromMain(page, page.getByRole('region', { name: '배치' }))).toBe(12);
  }
  const policy = isMobile(info) ? main : page.getByRole('region', { name: '방침' });
  await expect(policy).toContainText('양성현');
  // 막힌 현은 점선 + 서버 사유(호버 전용 아님).
  await expect(policy).toContainText('허현');
  expect(await coveredIn(main)).toEqual([]);
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  expect(await main.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);

  await press(policy.getByRole('button', { name: '바꾸기' }).first(), info);
  const sheet = page.getByRole('dialog', { name: '방침 바꾸기' });
  await press(sheet.getByRole('option', { name: '농업' }), info);
  expect(await coveredIn(sheet)).toEqual([]);
  expect(await smallTouchTargets(page, '[role="dialog"]')).toEqual([]);
  await press(sheet.getByRole('button', { name: '이 방침으로' }), info);
  await expect(page.getByRole('status').filter({ hasText: '방침을' })).toContainText('방침을 접수했습니다');
});

test('창고망 읽기 실패 — 띠는 「금 —」 빈 값이 아니라 실패 한 줄, 서버 원문 0', { tag: [BOTH] }, async ({ page }) => {
  const { '/api/warehouses': _wh, ...rest } = table;
  await serveCampaign(page, rest);
  await page.goto('/game/territory', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '영지' })).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText('창고망을 불러오지 못했습니다.')).toBeVisible();
  await expect(page.getByRole('main', { name: '게임 콘텐츠' })).not.toContainText('금 —');
  expect(await page.locator('body').innerText()).not.toContain('Not Found');
});

test('?view=work — 공사 칸으로 바로 열고(도움말 첫걸음 바로가기), 새 공사 → 수리 → 이 공사로 접수', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/territory?view=work', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '영지' })).toBeVisible({ timeout: 60_000 });
  if (isMobile(info)) await expect(page.getByRole('radio', { name: '공사' })).toHaveAttribute('aria-checked', 'true');
  const works = isMobile(info) ? page.getByRole('main', { name: '게임 콘텐츠' }) : page.getByRole('region', { name: '공사' });
  await press(works.getByRole('button', { name: '새 공사' }), info);
  const sheet = page.getByRole('dialog', { name: '양성현 공사' });
  await press(sheet.getByRole('option', { name: /수리/ }), info);
  const sent = page.waitForRequest((r) => r.method() === 'POST' && new URL(r.url()).pathname === '/api/game/api/commands/work/start');
  await press(sheet.getByRole('button', { name: '이 공사로' }), info);
  expect((await sent).postDataJSON()).toEqual({ countyId: 129, work: 'IRRIGATION' });
  await expect(page.getByRole('status').filter({ hasText: '공사를' })).toBeVisible();
});
