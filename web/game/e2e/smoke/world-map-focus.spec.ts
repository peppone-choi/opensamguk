// 지도 초점 스모크 — 省 지도가 첫 그림 뒤에 와서 내 城 이 치소 칸으로 멀리 옮겨 갈 때(@both).
// 운영 실측: 省 지도 없이 잡은 칸과 치소 칸은 대개 2칸 차이지만 선무 210 · 이석 99칸처럼 멀리 옮겨지는 城 이 있다.
// 사용자가 아직 지도를 움직이지 않았으면 카메라가 새 칸을 다시 비추고, 움직였으면 그대로 둔다.
// 省 응답은 테스트가 풀어 줄 때까지 붙잡는다(「늦게 온다」). 카메라 가운데 칸은 캔버스 data-view-center 로 읽는다.
import { deflateSync } from 'node:zlib';
import { expect, test, type Page } from '@playwright/test';
import { BOTH } from '../support/parity';

const COLS = 60;
const ROWS = 60;
/** 省 지도 없이(투영 좌표) 잡히는 칸과, 省 지도가 오면 앉는 치소 칸 — 50칸 넘게 떨어져 있다. */
const PROJECTED = { col: 10, row: 10 };
const SEAT = { col: 50, row: 45 };

const PREVIEW = {
  mapCode: 'han-world-v3', width: 700, height: 610,
  cities: [{
    id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true,
    x: PROJECTED.col * 700 / COLS, y: PROJECTED.row * 610 / ROWS,
    isCommanderySeat: true, commanderyName: '하남윤', provinceId: 1,
  }],
  nations: [{ id: 1, name: '위', color: '#b03a2e' }],
};
const TILES = {
  _meta: { cols: COLS, rows: ROWS, year: 200, terrainLegend: { 0: 'SEA', 1: 'PLAIN', 2: 'MOUNTAIN' } },
  terrain: Array.from({ length: ROWS }, (_, row) => Array.from({ length: COLS }, (_, col) => (
    (row * 7 + col * 3) % 11 === 0 ? '2' : '1')).join('')),
  owner: [[0, COLS * ROWS]],
  juns: [{ name: '하남윤', nameCh: '河南尹', seat: 0, col: PROJECTED.col, row: PROJECTED.row }],
  provinceRecords: [
    { id: 'A', displayName: '북현', nameCh: '北', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: null, geometryBasis: 'smoke', confidence: 'smoke' },
    { id: 'B', displayName: '선무현', nameCh: '鮮無', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: 0, geometryBasis: 'smoke', confidence: 'smoke' },
  ],
  parentRegions: [{ id: 'P1', displayName: '하남윤', nameCh: '河南尹', administrativeSystem: 'HAN_COMMANDERY' }],
  adjacency: { county: [], commandery: [] },
  regions: [],
  cities: [{ id: '1', name: '선무', nameCh: '鮮無', level: 8, kind: 'COUNTY', seat: true,
    col: SEAT.col, row: SEAT.row, lat: 0, lon: 0 }],
};
/** 천하 지도는 /api/map 의 myCity 로 내 城 을 잡는다. */
const WORLD = {
  result: true, version: 1, mapName: 'han-world-v3', startYear: 190, year: 200, month: 3,
  cityList: [[1, 8, 0, 1, 0, 1]], nationList: [[1, '위', '#b03a2e', 0]], spyList: {}, shownByGeneralList: [1], myCity: 1,
};

const CRC_TABLE = Array.from({ length: 256 }, (_, n) => {
  let c = n;
  for (let k = 0; k < 8; k += 1) c = (c & 1) ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c >>> 0;
});
function crc32(bytes: Buffer): number {
  let c = 0xffffffff;
  for (const byte of bytes) c = CRC_TABLE[(c ^ byte) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}
function chunk(type: string, data: Buffer): Buffer {
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const length = Buffer.alloc(4); length.writeUInt32BE(data.length);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(body));
  return Buffer.concat([length, body, crc]);
}
/** 省 식별 PNG(8비트 RGB · IDAT 하나): 위 절반은 북현(省 0), 아래 절반은 선무현(省 1) — 둘 다 하남윤(郡 0). */
function provincePng(): Buffer {
  const stride = 1 + COLS * 3;
  const raw = Buffer.alloc(ROWS * stride);
  for (let row = 0; row < ROWS; row += 1) {
    const code = (1 << 12) | ((row < 30 ? 0 : 1) + 1);
    for (let col = 0; col < COLS; col += 1) {
      const offset = row * stride + 1 + col * 3;
      raw[offset] = (code >> 16) & 0xff;
      raw[offset + 1] = (code >> 8) & 0xff;
      raw[offset + 2] = code & 0xff;
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(COLS, 0); header.writeUInt32BE(ROWS, 4);
  header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}

/** 省 응답을 붙잡아 두는 합성 지도. 돌려주는 release() 를 부르면 그제야 省 지도가 온다. */
async function mapWithLateProvinces(page: Page) {
  let release!: () => void;
  const released = new Promise<void>((resolve) => { release = resolve; });
  const png = provincePng();
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    if (url.pathname === '/api/auth/me') {
      return route.fulfill({ json: { user: { id: 1, username: 'smoke', email: null, nickname: '스모크', role: 'USER' } } });
    }
    if (url.pathname.endsWith('/api/const')) {
      return route.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
    }
    if (url.pathname.endsWith('/api/map/preview')) return route.fulfill({ json: PREVIEW });
    if (url.pathname.endsWith('/api/map/terrain')) return route.fulfill({ json: TILES });
    if (url.pathname.endsWith('/api/map')) return route.fulfill({ json: WORLD });
    if (url.pathname.endsWith('/api/map/provinces')) {
      await released;
      return route.fulfill({ status: 200, contentType: 'image/png', body: png });
    }
    return route.fulfill({ status: 503, json: { error: 'smoke' } });
  });
  return release;
}

async function viewCenter(page: Page): Promise<{ col: number; row: number }> {
  const value = await page.locator('.os-iso-map__canvas').first().getAttribute('data-view-center');
  const [col, row] = (value ?? 'NaN,NaN').split(',').map(Number);
  return { col, row };
}

const distance = (a: { col: number; row: number }, b: { col: number; row: number }) => Math.hypot(a.col - b.col, a.row - b.row);

test('省 지도가 늦게 와 내 城 이 멀리 옮겨 가면 손대지 않은 카메라는 새 칸을 비춘다', { tag: [BOTH] }, async ({ page }, testInfo) => {
  const release = await mapWithLateProvinces(page);
  await page.goto('/game/map');
  const map = page.locator('.os-iso-map').first();
  await expect(page.locator('.os-iso-map__canvas').first()).toBeVisible({ timeout: 60_000 });
  await map.scrollIntoViewIfNeeded();
  // 첫 초점: 省 지도 없이 잡은(투영 좌표) 칸.
  await expect.poll(async () => distance(await viewCenter(page), PROJECTED), { timeout: 30_000 }).toBeLessThan(2);
  await testInfo.attach('before-provinces', { body: await map.screenshot(), contentType: 'image/png' });

  release();
  // 省 지도가 오면 城 이 치소 쪽(아래 절반, 선무현)으로 옮겨 가고 카메라가 따라간다.
  await expect.poll(async () => {
    const center = await viewCenter(page);
    return center.row >= 30 && distance(center, PROJECTED) > 20;
  }, { timeout: 30_000 }).toBe(true);
  await testInfo.attach('after-provinces', { body: await map.screenshot(), contentType: 'image/png' });
});

test('省 지도가 오기 전에 지도를 끌었으면 카메라를 그대로 둔다', { tag: [BOTH] }, async ({ page }) => {
  const release = await mapWithLateProvinces(page);
  await page.goto('/game/map');
  const canvas = page.locator('.os-iso-map__canvas').first();
  await expect(canvas).toBeVisible({ timeout: 60_000 });
  await canvas.scrollIntoViewIfNeeded();
  await expect.poll(async () => distance(await viewCenter(page), PROJECTED), { timeout: 30_000 }).toBeLessThan(2);

  const box = (await canvas.boundingBox())!;
  const cx = box.x + box.width / 2;
  const cy = box.y + box.height / 2;
  await page.mouse.move(cx, cy);
  await page.mouse.down();
  await page.mouse.move(cx - 60, cy - 30, { steps: 6 });
  await page.mouse.up();
  const dragged = await viewCenter(page);
  expect(distance(dragged, PROJECTED)).toBeGreaterThan(1);

  const provinces = page.waitForResponse((response) => response.url().includes('/map/provinces'));
  release();
  await provinces;
  // 省 지도로 城 자리가 바뀌어 다시 그려질 시간을 준 뒤에도 사용자가 끈 자리 그대로다.
  await page.waitForTimeout(1_500);
  expect(distance(await viewCenter(page), dragged)).toBeLessThan(0.2);
});
