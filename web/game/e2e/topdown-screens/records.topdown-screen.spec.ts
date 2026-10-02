// 기록 화면 지도(P-H01 RecordMap) 새 지도 — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 옛 지도와 같은 동작(K5 10-01): CITY ref 가 풀리는 기록을 고르면 그 현을 가운데 · 현 보기 · 노란 테두리로 그린다.
// 합성 bake · 키트(e2e/fixtures/topdown, 원작 그림 없음)를 bake 주소와 승인 키트 주소에 page.route 로 대 주고, 기록 자료는 records.spec 방식이다.
import { deflateSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile, press, smallTouchTargets } from '../support/parity';

const FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
const COLS = 60;
const ROWS = 60;

/** 합성 bake의 城 1(선무, 발자국 1399,899 · 3칸)과 같은 id · 이름. 구역 2개(합성 bake provinceCount). */
function preview(withBake: boolean) {
  return {
    mapCode: 'han-world-v3', width: 700, height: 610,
    cities: [{ id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true, x: 116, y: 101,
      isCommanderySeat: true, commanderyName: '하남윤', provinceId: 1 }],
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#bf4f4f' }],
    provinceOccupancy: [
      { provinceRecordId: 'A', provinceIndex: 0, nationId: 1 },
      { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 },
    ],
    ...(withBake ? { topdownBakeId: BAKE_ID } : {}),
  };
}
// 옛 지도 훅(useCampaignWorldMap)이 ready 가 되려면 지형 · 省 그림이 있어야 한다 — 새 지도 분기도 그 훅의 preview 를 쓴다.
const TILES = {
  _meta: { cols: COLS, rows: ROWS, year: 200, terrainLegend: { 0: 'SEA', 1: 'PLAIN' } },
  terrain: Array.from({ length: ROWS }, () => '1'.repeat(COLS)),
  owner: [[0, COLS * ROWS]],
  juns: [{ name: '하남윤', nameCh: '河南尹', seat: 0, col: 10, row: 10 }],
  provinceRecords: [
    { id: 'A', displayName: '북현', nameCh: '北', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: null, geometryBasis: 'smoke', confidence: 'smoke' },
    { id: 'B', displayName: '선무현', nameCh: '鮮無', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: 0, geometryBasis: 'smoke', confidence: 'smoke' },
  ],
  parentRegions: [{ id: 'P1', displayName: '하남윤', nameCh: '河南尹', administrativeSystem: 'HAN_COMMANDERY' }],
  adjacency: { county: [], commandery: [] },
  regions: [],
  cities: [{ id: '1', name: '선무', nameCh: '鮮無', level: 8, kind: 'COUNTY', seat: true, col: 10, row: 10, lat: 0, lon: 0 }],
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
/** 옛 지도용 省 식별 PNG: 위 절반 省 0, 아래 절반 省 1. */
function provincePng(): Buffer {
  const stride = 1 + COLS * 3;
  const raw = Buffer.alloc(ROWS * stride);
  for (let row = 0; row < ROWS; row += 1) {
    const code = (1 << 12) | ((row < 30 ? 0 : 1) + 1);
    for (let col = 0; col < COLS; col += 1) {
      const offset = row * stride + 1 + col * 3;
      raw[offset] = (code >> 16) & 0xff; raw[offset + 1] = (code >> 8) & 0xff; raw[offset + 2] = code & 0xff;
    }
  }
  const header = Buffer.alloc(13);
  header.writeUInt32BE(COLS, 0); header.writeUInt32BE(ROWS, 4); header[8] = 8; header[9] = 2;
  return Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0))]);
}
const contentType = (path: string) => (path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream');

const WORLD = [{ id: 101, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 5 },
  refs: { CITY: 1, FROM_NATION: 2, TO_NATION: 1 }, facts: {} }];

async function openRecords(page: Page, withBake: boolean) {
  const baseURL = test.info().project.use.baseURL ?? 'http://localhost:3001';
  await page.context().addCookies([{ name: 'sam_server', value: 'pep', url: baseURL }]);
  const png = provincePng();
  await page.route((url) => url.pathname.startsWith('/map/waryong/273d596/'), async (route) => {
    const file = new URL(route.request().url()).pathname.replace('/map/waryong/273d596/', '');
    try {
      await route.fulfill({ status: 200, body: readFileSync(join(FIXTURE, 'kit', file)), contentType: contentType(file) });
    } catch {
      await route.fulfill({ status: 404, body: '' });
    }
  });
  await page.route((url) => url.pathname === '/api/auth/me', (r) => r.fulfill({ json: { user: { id: 1, username: 'qa', nickname: 'qa', role: 'USER' } } }));
  await page.route((url) => url.pathname.startsWith('/api/server-basic-info/'), (r) => r.fulfill({ status: 404, json: {} }));
  await page.route((url) => url.pathname.startsWith('/api/game/'), async (r) => {
    const url = new URL(r.request().url());
    const bake = url.pathname.indexOf(`/api/map/topdown/${BAKE_ID}/`);
    if (bake >= 0) {
      const file = url.pathname.slice(bake + `/api/map/topdown/${BAKE_ID}/`.length);
      try {
        return await r.fulfill({ status: 200, body: readFileSync(join(FIXTURE, 'bake', file)), contentType: contentType(file) });
      } catch {
        return r.fulfill({ status: 404, body: '' });
      }
    }
    if (url.pathname.endsWith('/front-info')) {
      return r.fulfill({ json: {
        result: true,
        global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
        general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
        nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: null, recentRecord: {},
      } });
    }
    if (url.pathname.endsWith('/api/world-events')) return r.fulfill({ json: { events: WORLD, nextCursor: null } });
    if (url.pathname.endsWith('/api/events')) return r.fulfill({ json: { events: [], nextCursor: null } });
    if (url.pathname.endsWith('/api/const')) return r.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
    if (url.pathname.endsWith('/api/map/preview')) return r.fulfill({ json: preview(withBake) });
    if (url.pathname.endsWith('/api/map/terrain')) return r.fulfill({ json: TILES });
    if (url.pathname.endsWith('/api/map/provinces')) return r.fulfill({ status: 200, contentType: 'image/png', body: png });
    return r.fulfill({ status: 503, json: {} });
  });
  await page.goto('/game/records', { waitUntil: 'domcontentloaded' });
  await expect(page.getByRole('list', { name: '기록' })).toBeVisible({ timeout: 60_000 });
}

test.describe('기록 지도 새 지도(교체 스위치 빌드)', () => {
  test('현이 있는 기록을 고르면 그 현이 가운데 · 현 보기 · 노란 테두리, 휠 · 끌기가 된다', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await openRecords(page, true);
    await press(page.getByRole('button', { name: /소유 세력이/ }), testInfo);
    const holder = isMobile(testInfo) ? page.getByRole('dialog') : page.getByRole('region', { name: '고른 기록' });
    const map = holder.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(holder.locator('.os-iso-map__canvas')).toHaveCount(0);
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await expect(map).toHaveAttribute('data-map-zoom', '16.000');
    await expect(map).toHaveAttribute('data-map-selected', '1');
    // 칸이 작아(모바일 200) 지도 위 단추를 두지 않는다 — 누를 것 44 는 시트 · 칸 전체로 본다
    await expect(holder.locator('[data-map-control]')).toHaveCount(0);
    expect(await smallTouchTargets(page, isMobile(testInfo) ? '[role="dialog"]' : 'main[aria-label="게임 콘텐츠"]')).toEqual([]);

    // 그려짐: 지도 가운데 맨 위 요소가 새 지도 캔버스
    await map.scrollIntoViewIfNeeded();
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    expect(await page.evaluate(({ x, y }) => {
      const el = document.elementFromPoint(x, y);
      return el ? `${el.tagName}:${Boolean(el.closest('[data-map-renderer="topdown"]'))}` : null;
    }, { x: cx, y: cy })).toBe('CANVAS:true');
    // 조작됨 ① 휠로 한 멈춤 자리 올라간다 ② 끌면 가운데 칸이 옮겨 간다
    await page.mouse.move(cx, cy);
    await page.mouse.wheel(0, -400);
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom')), { timeout: 10_000 }).toBeGreaterThan(16);
    const centre = await map.getAttribute('data-map-center');
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx - 60, cy - 30, { steps: 8 });
    await page.mouse.up();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centre);
    await expectNoHorizontalOverflow(page);
  });

  test('bakeId가 없으면 기록 지도도 옛 지도 그대로', { tag: [BOTH] }, async ({ page }, testInfo) => {
    await openRecords(page, false);
    await press(page.getByRole('button', { name: /소유 세력이/ }), testInfo);
    const holder = isMobile(testInfo) ? page.getByRole('dialog') : page.getByRole('region', { name: '고른 기록' });
    await expect(holder.locator('.os-iso-map__canvas').first()).toBeVisible({ timeout: 60_000 });
    await expect(holder.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
  });
});
