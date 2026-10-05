// 영지 › 현 상세(P-T02) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both). 공용 픽스처는 고치지 않는다.
// 縣 상세 읽기(K4-04)가 오기 전이라 7지표는 내 장수가 선 현(front-info city)만 값이 있고, 수비군 · 사람 · 사건은 서버 대기다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const base = frontInfo();
const city = {
  id: 3, name: '양성현', level: 2, levelName: '중', nationId: 1, nationName: '조조', region: 0, regionName: '예주',
  population: 900, populationMax: 1000, agriculture: 50, agricultureMax: 100, commerce: 40, commerceMax: 100,
  security: 30, securityMax: 100, defense: 20, defenseMax: 100, wall: 10, wallMax: 100, trust: 45, trade: null,
};
const table = {
  '/api/front-info': { ...base, city },
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, serverName: 's', year: 200, month: 3,
    cities: [
      { id: 3, name: '양성현', level: 2, nationId: 1, x: 0, y: 0, commanderyName: '영천군', state: 0, supply: true, isCapital: false },
      { id: 12, name: '진류현', level: 2, nationId: 2, x: 0, y: 0, commanderyName: '진류군', isCommanderySeat: true, state: 0, supply: true, isCapital: false },
    ],
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#9c4a3f' }] },
  '/api/county/3': { status: 'READY', cityId: 3, name: '양성현', specialties: [{ resource: 'iron', label: '철', monthly: 0, ledgerMonthly: 120 }] },
  '/api/county/12': { status: 'READY', cityId: 12, name: '진류현', specialties: [] },
  '/api/policies': { status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [],
    counties: [{ countyId: 3, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: { policy: 'FARM', label: '권농', source: 'DEFAULT' }, seat: null, settable: true, blocked: null }] },
  '/api/works': { status: 'READY', counties: [{ countyId: 3, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군', warehouse: null,
    active: { work: 'IRRIGATION', label: '수리', percent: 35, remainingPhases: 4, remainingCost: zero, stopReasonText: null, startsAtNextBoundary: false },
    completed: [], startable: [] }] },
  '/api/warehouses': { status: 'READY', warehouses: [{ cityId: 3, name: '양성현', commanderyName: null, isCapital: false, supplied: true, stock: { ...zero, money: 300 } }] },
  '/api/visibility': { status: 'READY', commanderies: [
    { no: 1, id: 'yingchuan', name: '영천군', tier: 'FULL' }, { no: 2, id: 'chenliu', name: '진류군', tier: 'INTEL', ageTurns: 3 },
  ] },
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(county|policies|works|warehouses|visibility|map\/preview)/;

/** 본문 왼쪽 여백 — 셸(GameShell .body)이 주는 12와 정확히 같다(화면 루트가 또 주면 24로 겹친다, #1133). */
async function insetFromMain(page: Page, target: Locator): Promise<number> {
  const main = await page.getByRole('main', { name: '게임 콘텐츠' }).boundingBox();
  const box = await target.boundingBox();
  return Math.round((box?.x ?? 0) - (main?.x ?? 0));
}

/** 누를 것의 가운데를 다른 상자가 덮는지(K10 「덮임」 — elementFromPoint). 화면 밖은 세지 않는다. */
async function coveredIn(root: Locator): Promise<string[]> {
  return root.evaluate((r) => {
    const out: string[] = [];
    for (const el of Array.from(r.querySelectorAll<HTMLElement>('a, button, [role="radio"]'))) {
      const b = el.getBoundingClientRect();
      if (b.width === 0 || b.height === 0) continue;
      const cx = b.x + b.width / 2;
      const cy = b.y + b.height / 2;
      if (cx < 0 || cy < 0 || cx > innerWidth || cy > innerHeight) continue;
      const hit = document.elementFromPoint(cx, cy);
      if (hit !== el && !el.contains(hit)) out.push(`${(el.textContent ?? '').trim()} ← ${hit?.tagName}.${hit?.className}`);
    }
    return out;
  });
}

async function checkQuality(page: Page) {
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  expect(await coveredIn(main)).toEqual([]);
  expect(await main.innerText()).not.toMatch(/[A-Za-z]{3,}/);
  await expectNoHorizontalOverflow(page);
  expect(await smallTouchTargets(page, 'main')).toEqual([]);
  expect(await titleOnlyInfo(page, 'main')).toEqual([]);
}

test('내가 선 현(데 세 칸 · 모 세그먼트) — 7지표 · 다스림 · 공사, 덮임 · 넘침 0, 44 · title 전용 · 영어 원문 0, 여백 12', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  // 셸 하위 탭 「현 상세」 첫 화면 — 현을 고르지 않으면 내 장수가 선 현.
  await page.goto('/game/territory/county', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '영지' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const name = main.getByRole('heading', { name: '양성현' });
  await expect(name).toBeVisible();
  await expect(main).toContainText('지금 여기');
  expect(await insetFromMain(page, name)).toBe(12);
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  // 현 상세(K4-04)는 늘 부른다(D124 미리 짓기) — 표에 없어 404 → 화면 오류 없이 수비군 칸만 서버 대기, 7지표는 front-info 로.
  await expect.poll(() => served.unknown.some((u) => u.startsWith('GET /api/counties/'))).toBe(true);
  await expect(main.getByRole('meter')).toHaveCount(7);
  await checkQuality(page);
  if (isMobile(info)) {
    const seg = main.getByRole('radiogroup', { name: '보기' });
    await press(seg.getByRole('radio', { name: '다스림' }), info);
    await expect(main).toContainText('현령 — 빈자리');
    await press(seg.getByRole('radio', { name: '공사' }), info);
    await expect(main).toContainText('수리 35% · 4순 남음');
    await checkQuality(page);
    // 모바일은 세그먼트 — 수비군 · 이 현의 사람(K4-04 서버 대기)은 「사람」 칸에 있다.
    await press(seg.getByRole('radio', { name: '사람' }), info);
    await expect(main.locator('[data-server-wait="K4-04"]').first()).toBeAttached();
  } else {
    await expect(main.locator('[data-server-wait="K4-04"]').first()).toBeAttached();
    await expect(main.getByRole('region', { name: '다스림' })).toContainText('권농');
    await expect(main.getByRole('region', { name: '공사' })).toContainText('수리 35% · 4순 남음');
  }
});

test('남의 현 · 첩보 3순 전 — 형편 서버 대기, 입력 점선 「우리 현이 아닙니다」, 다시 첩보는 그 군을 대상으로 흐름', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/territory/county/12', { waitUntil: 'domcontentloaded' });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  await expect(main.getByRole('heading', { name: '진류현' })).toBeVisible({ timeout: 60_000 });
  await expect(main).toContainText('첩보 3순 전');
  await expect(main).toContainText('형편 7지표 — 서버 대기');
  if (isMobile(info)) await press(main.getByRole('radiogroup', { name: '보기' }).getByRole('radio', { name: '다스림' }), info);
  await expect(main.locator('button[data-input-id="policy.set"]')).toHaveAccessibleDescription(/우리 현이 아닙니다/);
  await checkQuality(page);
  await press(main.getByRole('button', { name: '다시 첩보 — 명령 목록에 넣기' }), info);
  // 작전실 흐름이 주소에 순(slot)을 덧붙일 수 있어 끝까지 맞추지 않고 검색 인자로 본다.
  await page.waitForURL((u) => /\/game(\/pep)?$/.test(u.pathname) && u.searchParams.get('do') === 'action.scout' && u.searchParams.get('target') === 'commandery:chenliu');
});
