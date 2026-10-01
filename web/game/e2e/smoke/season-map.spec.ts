// 계절 패널이 열린 채로 지도가 조작되는지(K8 · 셸) — 데스크톱 패널은 투명 덮개를 깔지 않는다(지도 휠 · 끌기를 먹은 전례).
// 「그려짐」(패널 밖 지도 한 점이 지도에 닿는다)과 「조작됨」(휠 · 끌기 뒤 지도 그림이 바뀐다, 끌기의 누름은 패널을 닫는다)을 본다.
// 모바일은 하단 시트(모달 덮개)라 해당 없다 — 데스크톱 프로필만 돈다(태그 없음).
import { expect, test, type Page } from '@playwright/test';
import { press } from '../support/parity';

const PREVIEW = {
  mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [{ id: 1, name: '낙양', level: 8, nationId: 1, x: 300, y: 280, state: 0, supply: true, isCapital: true, isCommanderySeat: true, commanderyName: '하남윤' }],
  nations: [{ id: 1, name: '위', color: '#b03a2e' }],
};
const COLS = 60;
const ROWS = 60;
const TILES = {
  _meta: { cols: COLS, rows: ROWS, year: 200, terrainLegend: { 0: 'SEA', 1: 'PLAIN', 2: 'MOUNTAIN' } },
  terrain: Array.from({ length: ROWS }, (_, row) => Array.from({ length: COLS }, (_, col) => (
    row < 4 || col < 4 ? '0' : (row * 7 + col * 3) % 11 === 0 ? '2' : '1')).join('')),
  owner: [[0, COLS * ROWS]],
  juns: [{ name: '하남윤', nameCh: '河南尹', seat: 0, col: 26, row: 28 }],
  adjacency: { county: [], commandery: [] },
  regions: [],
  cities: [],
};
const FRONT_INFO = {
  result: true,
  global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
  general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
  nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
};

async function syntheticMapWithSession(page: Page) {
  await page.route('**/api/**', (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/auth/me') return route.fulfill({ json: { user: { id: 1, username: 'smoke', email: null, nickname: '스모크', role: 'USER' } } });
    if (url.pathname.endsWith('/front-info')) return route.fulfill({ json: FRONT_INFO });
    if (url.pathname.endsWith('/api/const')) return route.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
    if (url.pathname.endsWith('/api/map/preview')) return route.fulfill({ json: PREVIEW });
    if (url.pathname.endsWith('/api/map/terrain')) return route.fulfill({ json: TILES });
    return route.fulfill({ status: 503, json: { error: 'smoke' } });
  });
}

async function canvasHash(page: Page): Promise<number> {
  return page.locator('.os-iso-map__canvas').first().evaluate((node) => {
    const canvas = node as HTMLCanvasElement;
    const context = canvas.getContext('2d')!;
    let hash = 0;
    for (let i = 1; i < 16; i += 1) for (let j = 1; j < 16; j += 1) {
      const d = context.getImageData(Math.floor(canvas.width * i / 16), Math.floor(canvas.height * j / 16), 1, 1).data;
      hash = (hash * 31 + d[0] * 7 + d[1] * 13 + d[2] * 17) >>> 0;
    }
    return hash;
  });
}

test('계절 패널을 연 채로 지도를 누르면 지도에 닿고, 휠 · 끌기로 지도가 바뀐다', async ({ page }, testInfo) => {
  await syntheticMapWithSession(page);
  await page.goto('/game/map');
  const canvas = page.locator('.os-iso-map__canvas').first();
  await expect(canvas).toBeVisible({ timeout: 60_000 });
  await expect.poll(() => canvasHash(page), { timeout: 30_000 }).not.toBe(0);

  await press(page.getByRole('button', { name: /^봄 · 200년 3월/ }), testInfo);
  const dialog = page.getByRole('dialog', { name: '계절 — 봄' });
  await expect(dialog).toBeVisible();

  // 패널 밖 지도 한 점 — 지도 상자 왼쪽 가운데(패널은 오른쪽 위).
  const map = (await canvas.boundingBox())!;
  const pop = (await dialog.boundingBox())!;
  const x = map.x + Math.min(map.width * 0.25, Math.max(40, pop.x - map.x - 80));
  const y = map.y + map.height / 2;
  expect(x < pop.x || y > pop.y + pop.height, '고른 점이 패널 밖이어야 한다').toBe(true);
  const hitsMap = await page.evaluate(([px, py]) => {
    const el = document.elementFromPoint(px as number, py as number);
    const host = document.querySelector('.os-iso-map');
    return el !== null && host !== null && (el === host || host.contains(el));
  }, [x, y] as const);
  expect(hitsMap, '패널이 열려 있어도 지도 점은 지도에 닿아야 한다(투명 덮개 없음)').toBe(true);

  // 휠은 누름이 아니라 패널을 닫지 않고 지도만 바꾼다.
  const beforeWheel = await canvasHash(page);
  await page.mouse.move(x, y);
  await page.mouse.wheel(0, -480);
  await expect.poll(() => canvasHash(page)).not.toBe(beforeWheel);
  await expect(dialog).toBeVisible();

  // 끌기의 누름은 바깥 누름 — 패널이 닫히고, 같은 끌기로 지도도 움직인다(먹히지 않는다).
  const beforeDrag = await canvasHash(page);
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + 80, y + 40, { steps: 6 });
  await page.mouse.up();
  await expect(dialog).toBeHidden();
  await expect.poll(() => canvasHash(page)).not.toBe(beforeDrag);
});
