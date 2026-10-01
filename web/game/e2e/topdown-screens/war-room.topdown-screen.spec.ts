// 작전실(게임 첫 화면 /game) 새 지도(탑다운) — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 서버 preview가 topdownBakeId를 주면 새 지도, 안 주면 옛 지도 그대로다. 합성 bake · 키트(e2e/fixtures/topdown, 원작 그림 없음)를
// bake 주소(/api/game/api/map/topdown/<id>/…)와 승인 키트 주소(/map/waryong/273d596/…)에 page.route로 대 준다.
// 「그려졌다」(상태 · 가운데 요소)와 「조작된다」(휠 · 누르기)를 따로 본다.
import { deflateSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';
import { BOTH } from '../support/parity';

const FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
const COLS = 60;
const ROWS = 60;

/** 합성 bake의 城 1(발자국 1399,899 · 3칸)과 같은 id · 이름. 구역 2개(합성 bake provinceCount). */
function preview(withBake: boolean) {
  return {
    mapCode: 'han-world-v3', width: 700, height: 610,
    cities: [{ id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true, x: 116, y: 101,
      isCommanderySeat: true, commanderyName: '하남윤', provinceId: 1 }],
    nations: [{ id: 1, name: '위', color: '#b03a2e' }],
    provinceOccupancy: [
      { provinceRecordId: 'A', provinceIndex: 0, nationId: 1 },
      { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 },
    ],
    ...(withBake ? { topdownBakeId: BAKE_ID } : {}),
  };
}
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

/** 셸 통합(#1107) 뒤 작전실은 게임 첫 화면(/game)이다 — 장수가 있어야 열린다(front-info 합성, 서버는 쿠키로). */
const FRONT_INFO = {
  result: true,
  global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
  general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1, permission: 0, showSecret: false },
  nation: { id: 1, name: '위', color: '#b03a2e' }, city: { id: 1, name: '선무' }, recentRecord: {},
};

async function serve(page: Page, withBake: boolean) {
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
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url());
    const bake = url.pathname.indexOf(`/api/map/topdown/${BAKE_ID}/`);
    if (bake >= 0) {
      const file = url.pathname.slice(bake + `/api/map/topdown/${BAKE_ID}/`.length);
      try {
        return await route.fulfill({ status: 200, body: readFileSync(join(FIXTURE, 'bake', file)), contentType: contentType(file) });
      } catch {
        return route.fulfill({ status: 404, body: '' });
      }
    }
    if (url.pathname === '/api/auth/me') {
      return route.fulfill({ json: { user: { id: 1, username: 'smoke', email: null, nickname: '스모크', role: 'USER' } } });
    }
    if (url.pathname.endsWith('/front-info')) return route.fulfill({ json: FRONT_INFO });
    if (url.pathname.endsWith('/api/const')) {
      return route.fulfill({ json: { result: true, mapName: 'han-world-v3', mapWidth: 700, mapHeight: 610, maxTurn: 12 } });
    }
    if (url.pathname.endsWith('/api/map/preview')) return route.fulfill({ json: preview(withBake) });
    if (url.pathname.endsWith('/api/map/terrain')) return route.fulfill({ json: TILES });
    if (url.pathname.endsWith('/api/map/provinces')) return route.fulfill({ status: 200, contentType: 'image/png', body: png });
    return route.fulfill({ status: 503, json: { error: 'smoke' } });
  });
}

test.describe('작전실 새 지도(교체 스위치 빌드)', () => {
  test('서버가 bakeId를 주면 새 지도: 그려지고 휠 · 누르기가 된다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
    await expect(page.getByRole('alert').filter({ hasText: '세력 색을 칠하지 못했습니다' })).toHaveCount(0);
    await map.scrollIntoViewIfNeeded();
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    const top = await page.evaluate(({ x, y }) => {
      const el = document.elementFromPoint(x, y);
      return el ? `${el.tagName}:${Boolean(el.closest('[data-map-renderer="topdown"]'))}` : null;
    }, { x: cx, y: cy });
    expect(top).toBe('CANVAS:true');
    // 초점 = 합성 城 1(선무) 발자국 가운데. 내 城도 선무라 내 위치 표지가 그 위에 선다 — 누르면 「내 위치 — 선무」
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await page.mouse.click(cx, cy);
    await expect(page.getByTestId('war-room-picked')).toContainText('내 위치');
    await expect(page.getByTestId('war-room-picked')).toContainText('선무');
    // 조작됨 ① 휠이 지도 캔버스에 닿아 확대된다
    const before = Number(await map.getAttribute('data-map-zoom'));
    await page.mouse.move(cx, cy);
    await page.mouse.wheel(0, -400);
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom')), { timeout: 10_000 }).toBeGreaterThan(before);
    // 조작됨 ② 끌면 가운데 칸이 옮겨 간다
    const centreBefore = await map.getAttribute('data-map-center');
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx - 120, cy - 80, { steps: 8 });
    await page.mouse.up();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centreBefore);
  });

  test('지도 위 조작(보드 MapViewBar · 레이어 · 범례): 44 · 안 가림, 주 · 군 · 현 · + · 내 위치로 · 레이어 · 범례가 지도를 바꾼다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.scrollIntoViewIfNeeded();

    // 그려짐: 단추는 모두 44 이상이고 가운데의 맨 위 요소가 그 단추 자신이다(겹친 상자가 먹지 않는다)
    const controls = page.locator('[data-map-control] button');
    const count = await controls.count();
    expect(count).toBeGreaterThanOrEqual(8);
    for (let i = 0; i < count; i += 1) {
      const button = controls.nth(i);
      const box = (await button.boundingBox())!;
      expect(box.width, `단추 ${i} 폭`).toBeGreaterThanOrEqual(44);
      expect(box.height, `단추 ${i} 높이`).toBeGreaterThanOrEqual(44);
      const onTop = await button.evaluate((node) => {
        const rect = node.getBoundingClientRect();
        const top = document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2);
        return Boolean(top && (top === node || node.contains(top)));
      });
      expect(onTop, `단추 ${i}(${await button.getAttribute('aria-label') ?? await button.textContent()})가 가렸다`).toBe(true);
    }

    // 조작됨 ① 보기 수준: 주 → 지도가 州 보기로, 현 → 縣 보기로
    await page.getByRole('radio', { name: '주 보기' }).click();
    await expect(map).toHaveAttribute('data-map-level', 'ju');
    await expect(page.getByRole('radio', { name: '주 보기' })).toHaveAttribute('aria-checked', 'true');
    await page.getByRole('radio', { name: '현 보기' }).click();
    await expect(map).toHaveAttribute('data-map-level', 'county');
    // ② 축소 단추는 한 멈춤 자리 내려간다
    const zoomBefore = Number(await map.getAttribute('data-map-zoom'));
    await page.getByRole('button', { name: '축소' }).click();
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom')), { timeout: 10_000 }).toBeLessThan(zoomBefore);
    // ③ 끌어서 옮긴 뒤 「내 위치로」는 내 城(선무) 가운데 현 보기로 돌아온다. Home 키도 같다
    const box = (await map.boundingBox())!;
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 - 150, box.y + box.height / 2 - 90, { steps: 8 });
    await page.mouse.up();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe('1400.5,900.5');
    await page.getByRole('button', { name: '내 위치로(Home)' }).click();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).toBe('1400.5,900.5');
    await expect(map).toHaveAttribute('data-map-zoom', '16.000');
    await page.getByRole('radio', { name: '군 보기' }).click();
    await expect(map).toHaveAttribute('data-map-level', 'commandery');
    await map.focus();
    await page.keyboard.press('Home');
    await expect(map).toHaveAttribute('data-map-zoom', '16.000');
    // ④ 지도 레이어 판: 군 경계를 켜면 눌림, 서버 칸이 없는 층은 「서버 대기 · 계약판 행」
    await page.getByRole('button', { name: '지도 레이어' }).click();
    const layersPanel = page.getByRole('region', { name: '지도 레이어' });
    await expect(layersPanel).toBeVisible();
    const commanderyLines = layersPanel.getByRole('button', { name: /군 경계/ });
    await expect(commanderyLines).toHaveAttribute('aria-pressed', 'false');
    await commanderyLines.click();
    await expect(commanderyLines).toHaveAttribute('aria-pressed', 'true');
    await expect(layersPanel).toContainText('서버 대기 · K2-08');
    // ⑤ 범례 판: 세력 색 이름 · 무주 · 미정찰. Esc 로 닫힌다
    await page.getByRole('button', { name: '범례' }).click();
    const legendPanel = page.getByRole('region', { name: '범례' });
    await expect(legendPanel).toContainText('위');
    await expect(legendPanel).toContainText('무주');
    await expect(layersPanel).toHaveCount(0);
    await page.keyboard.press('Escape');
    await expect(legendPanel).toHaveCount(0);
  });

  test('bakeId가 없으면 옛 지도 그대로', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, false);
    await page.goto('/game');
    await expect(page.locator('.os-iso-map__canvas').first()).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
  });
});
