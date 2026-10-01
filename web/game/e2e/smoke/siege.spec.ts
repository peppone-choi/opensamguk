// 군단 › 공성(P-C02) 스모크 — 합성 자료로 백엔드 없이, 데스크톱 · 모바일 같은 흐름(@both).
// 입력은 바로 보내지 않고 명령 흐름(작전실 `?do=`)을 연다 — 순은 흐름이 고른다. 공용 픽스처는 고치지 않는다.
import { expect, test, type Locator, type Page } from '@playwright/test';
import { frontInfo, serveCampaign } from '../support/campaignFixtures';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets, titleOnlyInfo } from '../support/parity';

const siege = {
  countyId: 12, countyName: '진류현', status: 'ACTIVE', endReason: null,
  besieger: { generalId: 7, name: '하후돈', nationId: 1, nationName: '조조' },
  defenderNationId: 2, defenderNationName: '원소', startedAt: { year: 190, month: 2, phase: 3 },
  turns: 1, grain: 1200, morale: 6400, garrison: 900, trust: 62,
  countySupplied: true, besiegerTroops: 2400, besiegerFed: true, canAct: true, surrenderDemandAccepted: false,
  timeline: [{ year: 190, month: 2, phase: 3, event: 'START', morale: 7000, garrison: 1000 }, { year: 190, month: 3, phase: 1, event: 'TURN', morale: 6400, garrison: 900 }],
};
const table = {
  '/api/front-info': frontInfo(),
  '/api/map/preview': { mapCode: 'x', width: 1, height: 1, cities: [], nations: [{ id: 2, name: '원소', color: '#9c4a3f' }] },
  '/api/sieges': { status: 'READY', sieges: [siege, { ...siege, countyId: 13, countyName: '패현', status: 'LIFTED', canAct: false, turns: 4 }] },
  '/api/road-forts': { status: 'READY', roadMode: true, gates: [], forts: [{
    id: 'land-boundary:gate@12,34', edgeId: 'land-boundary:gate', provinceId: 'p-unknown', row: 12, col: 34,
    ownerNationId: 2, wall: 800, garrison: 30, besiegerGeneralId: null, siegeProgress: 0, canBesiege: true,
  }] },
};
/** 이 화면이 부르는 조회 — 셸 · 도움말 조회는 각자 스모크 몫. */
const MINE = /\/api\/(sieges|road-forts|map\/preview)/;

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

test('목록 · 형편 · 명령(데) / 카드 → 상세 · 단추 줄(모) — 덮임 · 넘침 0, 44 · title 전용 · 영어 원문 0, 여백 12', { tag: [BOTH] }, async ({ page }, info) => {
  const served = await serveCampaign(page, table);
  await page.goto('/game/corps/siege', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '군단' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  const list = main.getByRole('list', { name: '포위' });
  await expect(list).toContainText('진류현');
  await expect(list).toContainText('보루 — 이름 모를 구역'); // 구역 id · 좌표는 화면에 없다
  await expect(list).toContainText('도로 보루 · 원소');
  expect(await insetFromMain(page, list)).toBe(12);
  expect(served.unknown.filter((u) => MINE.test(u))).toEqual([]);
  await checkQuality(page);

  if (isMobile(info)) {
    await press(list.getByRole('button', { name: /진류현/ }), info);
    await expect(main.getByRole('heading', { name: '진류현' })).toBeVisible();
    await expect(main).toContainText('성 안 수비');
    await expect(main.locator('button[data-input-id="action.assault"]')).toHaveAccessibleDescription(/3순/);
    await checkQuality(page);
    await press(main.getByRole('radiogroup', { name: '보기' }).getByRole('radio', { name: '기록' }), info);
    await expect(main.getByRole('list', { name: '포위 기록' })).toContainText('포위 시작');
    await press(main.getByRole('button', { name: '← 포위 목록' }), info);
    await expect(list).toBeVisible();
  } else {
    await expect(main.getByRole('region', { name: '형편' })).toContainText('강공까지 2순');
    await expect(main.getByRole('region', { name: '포위 기록' })).toContainText('포위 시작');
    await expect(main.getByRole('region', { name: '항복 권고' })).toContainText('지금 권하면 거절합니다.');
    await expect(main.locator('button[data-input-id="action.assault"]')).toHaveAccessibleDescription(/3순/);
  }
});

test('항복 권고 → 명령 흐름(?do=action.demandSurrender)이 열린다', { tag: [BOTH] }, async ({ page }, info) => {
  await serveCampaign(page, table);
  await page.goto('/game/corps/siege', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('heading', { level: 2, name: '군단' })).toBeVisible({ timeout: 60_000 });
  const main = page.getByRole('main', { name: '게임 콘텐츠' });
  if (isMobile(info)) await press(main.getByRole('list', { name: '포위' }).getByRole('button', { name: /진류현/ }), info);
  await press(main.locator('button[data-input-id="action.demandSurrender"]'), info);
  await expect(page).toHaveURL(/\/game(\/pep)?\?do=action\.demandSurrender$/);
});
