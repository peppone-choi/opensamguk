// 지도 스모크 — 백엔드 없이 합성 지도로 「그려짐」과 「조작됨」을 따로 본다(@both, K1 지도 M1).
// 겹친 투명 상자가 휠 · 끌기를 먹은 전례가 있어, 가운데가 지도에 닿는지(elementFromPoint)와 조작 뒤 그림이 바뀌는지를 둘 다 잰다.
// 지도 밖 API 는 503 으로 막는다 — 셸 · 레일은 오류 상태로 그려지고 지도만 합성 자료로 뜬다.
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectCenterHitsMap, isMobile, press } from '../support/parity';

const COLS = 60;
const ROWS = 60;
const PREVIEW = {
  mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [
    { id: 1, name: '낙양', level: 8, nationId: 1, x: 300, y: 280, state: 0, supply: true,
      isCapital: true, isCommanderySeat: true, commanderyName: '하남윤' },
    { id: 2, name: '허창', level: 6, nationId: 2, x: 420, y: 360, state: 0, supply: true,
      isCommanderySeat: true, commanderyName: '영천군' },
  ],
  nations: [{ id: 1, name: '위', color: '#b03a2e' }, { id: 2, name: '오', color: '#2e6fb0' }],
};
const TILES = {
  _meta: { cols: COLS, rows: ROWS, year: 200, terrainLegend: { 0: 'SEA', 1: 'PLAIN', 2: 'MOUNTAIN' } },
  terrain: Array.from({ length: ROWS }, (_, row) => Array.from({ length: COLS }, (_, col) => (
    row < 4 || col < 4 ? '0' : (row * 7 + col * 3) % 11 === 0 ? '2' : '1')).join('')),
  owner: [[0, COLS * ROWS]],
  juns: [{ name: '하남윤', nameCh: '河南尹', seat: 0, col: 26, row: 28 }, { name: '영천군', nameCh: '潁川郡', seat: 1, col: 36, row: 35 }],
  adjacency: { county: [], commandery: [] },
  regions: [],
  cities: [],
};

async function syntheticMap(page: Page, tiles: object = TILES) {
  const provinceRequests: string[] = [];
  const spriteRequests: string[] = [];
  page.on('request', (request) => {
    const url = request.url();
    if (url.includes('/map/provinces')) provinceRequests.push(url);
    if (/\/(city|status)\//.test(new URL(url).pathname)) spriteRequests.push(url);
  });
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url());
    // 게임 화면은 /api/auth/me 로 로그인 사용자를 확정한다 — 스모크 전용 가짜 사용자(자격증명 없음).
    if (url.pathname === '/api/auth/me') {
      return route.fulfill({ json: { user: { id: 1, username: 'smoke', email: null, nickname: '스모크', role: 'USER' } } });
    }
    // 천하 지도 화면은 지도 판 이름(/api/const)을 먼저 읽고서야 지도를 붙인다.
    if (url.pathname.endsWith('/api/const')) {
      return route.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
    }
    if (url.pathname.endsWith('/api/map/preview')) return route.fulfill({ json: PREVIEW });
    if (url.pathname.endsWith('/api/map/terrain')) return route.fulfill({ json: tiles });
    // 省 지도 계약 위반(PNG 아님) — 다시 받아도 같으므로 지도 훅과 지도판이 합쳐 한 번만 청해야 한다.
    if (url.pathname.endsWith('/api/map/provinces')) {
      return route.fulfill({ status: 200, contentType: 'text/plain', body: 'not a province map' });
    }
    return route.fulfill({ status: 503, json: { error: 'smoke' } });
  });
  return { provinceRequests, spriteRequests };
}

async function canvasPixels(page: Page): Promise<{ painted: number; hash: number }> {
  return page.locator('.os-iso-map__canvas').first().evaluate((node) => {
    const canvas = node as HTMLCanvasElement;
    const context = canvas.getContext('2d')!;
    let painted = 0;
    let hash = 0;
    for (let i = 1; i < 16; i += 1) for (let j = 1; j < 16; j += 1) {
      const d = context.getImageData(Math.floor(canvas.width * i / 16), Math.floor(canvas.height * j / 16), 1, 1).data;
      if (d[3] > 0) painted += 1;
      hash = (hash * 31 + d[0] * 7 + d[1] * 13 + d[2] * 17) >>> 0;
    }
    return { painted, hash };
  });
}

test('천하 지도는 그려지고, 가운데를 누르면 지도에 닿고, 확대 · 끌기로 바뀐다', { tag: [BOTH] }, async ({ page }, testInfo) => {
  const { provinceRequests, spriteRequests } = await syntheticMap(page);
  await page.goto('/game/map');
  const canvas = page.locator('.os-iso-map__canvas').first();
  await expect(canvas).toBeVisible({ timeout: 60_000 });
  await canvas.scrollIntoViewIfNeeded();

  // 그려짐: 캔버스 표본 225점 가운데 지형이 칠해진 점이 대부분이다.
  await expect.poll(async () => (await canvasPixels(page)).painted, { timeout: 30_000 }).toBeGreaterThan(150);
  await expectCenterHitsMap(page, '.os-iso-map');

  // 조작됨: 확대(데스크톱 휠 · 모바일 단추) 뒤, 끌기 뒤 그림이 바뀐다.
  const box = (await canvas.boundingBox())!;
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  const beforeZoom = (await canvasPixels(page)).hash;
  if (isMobile(testInfo)) await press(page.getByRole('button', { name: '지도 확대' }).first(), testInfo);
  else { await page.mouse.move(cx, cy); await page.mouse.wheel(0, -480); }
  await expect.poll(async () => (await canvasPixels(page)).hash).not.toBe(beforeZoom);
  const beforeDrag = (await canvasPixels(page)).hash;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 80, cy - 40, { steps: 6 });
  await page.mouse.up();
  await expect.poll(async () => (await canvasPixels(page)).hash).not.toBe(beforeDrag);

  // 省 지도는 한 번만 청한다(지도 훅 · 지도판 공유). 城 그림은 붙을 때 표 전부(388장)가 아니라 그린 城 몫만.
  expect(provinceRequests).toHaveLength(1);
  expect(spriteRequests.length).toBeLessThanOrEqual(PREVIEW.cities.length * 2);
});

/** 행정 레이어 단추(구역 · 현 · 군)는 정본 위계(縣 · 郡 기록)가 있을 때만 뜬다 — 그 단추까지 재려고 기록을 붙인 판. */
const TILES_WITH_HIERARCHY = {
  ...TILES,
  provinceRecords: [{ id: 'A', displayName: '낙양현', nameCh: '雒陽', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
    parentRegionId: 'P1', cityIndex: 0, geometryBasis: 'smoke', confidence: 'smoke' }],
  parentRegions: [{ id: 'P1', displayName: '하남윤', nameCh: '河南尹', administrativeSystem: 'HAN_COMMANDERY' }],
  jurisdictionRecords: [{ id: 'J1', displayName: '낙양현', nameCh: '雒陽', kind: 'COUNTY', commanderyId: 'P1', seatPlaceId: '1', provinceIds: ['A'] }],
};

test('지도 위 단추(확대 · 축소 · 레이어)는 모두 44 × 44 이상', { tag: [BOTH] }, async ({ page }) => {
  await syntheticMap(page, TILES_WITH_HIERARCHY);
  await page.goto('/game/map');
  const map = page.locator('.os-iso-map').first();
  await expect(page.locator('.os-iso-map__canvas').first()).toBeVisible({ timeout: 60_000 });
  await expect(map.getByRole('button', { name: '지도 확대' })).toBeVisible();
  const sizes = await map.locator('button').evaluateAll((buttons) => buttons
    .filter((button) => (button as HTMLElement).offsetParent !== null)
    .map((button) => {
      const box = button.getBoundingClientRect();
      return { name: button.getAttribute('aria-label') ?? button.textContent?.trim() ?? '', width: box.width, height: box.height };
    }));
  // 확대 · 축소 2 + 행정 레이어 3(구역 · 현 · 군)
  expect(sizes.map((size) => size.name)).toEqual(expect.arrayContaining(['지도 확대', '지도 축소', '구역 레이어', '현급 도시 레이어', '군급 도시 레이어']));
  const small = sizes.filter((size) => size.width < 44 || size.height < 44);
  expect(small, JSON.stringify(sizes)).toEqual([]);
  // 단추 가운데를 누르면 그 단추가 받는다(덮이지 않음), 조작 층은 지도판 안에서만 겨룬다(isolation)
  const covered = await map.locator('button').evaluateAll((buttons) => buttons
    .filter((button) => (button as HTMLElement).offsetParent !== null)
    .filter((button) => {
      const box = button.getBoundingClientRect();
      const hit = document.elementFromPoint(box.left + box.width / 2, box.top + box.height / 2);
      return !(hit && (hit === button || button.contains(hit)));
    })
    .map((button) => button.getAttribute('aria-label') ?? ''));
  expect(covered).toEqual([]);
  expect(await map.evaluate((node) => getComputedStyle(node).isolation)).toBe('isolate');
});
