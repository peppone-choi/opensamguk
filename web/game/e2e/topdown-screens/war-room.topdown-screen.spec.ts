// 작전실(게임 첫 화면 /game) 새 지도(탑다운) — 제품 화면 새 지도 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1)가 켜진 기본 빌드에서 돈다
// (운영 이미지와 같은 값, *.topdown-screen.spec.ts). 서버 preview가 topdownBakeId를 주면 새 지도, 안 주면(운영 bake 활성 A04 전) 옛 지도 그대로다. 합성 bake · 키트(e2e/fixtures/topdown, 원작 그림 없음)를
// bake 주소(/api/game/api/map/topdown/<id>/…)와 승인 키트 주소(/map/waryong/273d596/…)에 page.route로 대 준다.
// 「그려졌다」(상태 · 가운데 요소)와 「조작된다」(휠 · 누르기)를 따로 본다.
import { deflateSync, gunzipSync, gzipSync } from 'node:zlib';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { BOTH } from '../support/parity';
import { MAP_BACKGROUND, outOfScopeLandColour } from '../support/outOfScopeLand';

const FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
const COLS = 60;
const ROWS = 60;

/** 합성 bake의 城 1(발자국 1399,899 · 3칸)과 같은 id · 이름. 구역 2개(합성 bake provinceCount). */
function preview(withBake: boolean) {
  return {
    mapCode: 'han-world-v3', width: 700, height: 610,
    cities: [{ id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true, x: 116, y: 101,
      isCommanderySeat: true, commanderyName: '시험군', provinceId: 1 }],
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
  juns: [{ name: '시험군', nameCh: '試驗郡', seat: 0, col: 10, row: 10 }],
  provinceRecords: [
    { id: 'A', displayName: '북현', nameCh: '北', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: null, geometryBasis: 'smoke', confidence: 'smoke' },
    { id: 'B', displayName: '선무현', nameCh: '鮮無', administrativeSystem: 'HAN_COMMANDERY', kind: 'COUNTY',
      parentRegionId: 'P1', cityIndex: 0, geometryBasis: 'smoke', confidence: 'smoke' },
  ],
  parentRegions: [{ id: 'P1', displayName: '시험군', nameCh: '試驗郡', administrativeSystem: 'HAN_COMMANDERY' }],
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

/** 시야 · 군단 합성(M2-7 부대 표지): 郡 1은 다 보임 · 郡 2는 안 보임. 안 보이는 郡의 군단은 응답에 섞여 와도 그리지 않는다. */
const VISIBILITY = { status: 'READY', commanderies: [
  { no: 1, id: 'P1', name: '하남윤', tier: 'FULL' }, { no: 2, id: 'P2', name: '진류군', tier: 'FOG' }] };
const CORPS = { status: 'READY', corps: [
  { corpsId: 'c1', ownerGeneralId: 7, commanderGeneralId: 7, commanderName: '하후돈', nationId: 1, nationColor: '#b03a2e',
    provinceId: 'B', commanderyNo: 1, visibility: 'FULL', own: true, marchPath: ['B', 'A'] },
  { corpsId: 'c2', ownerGeneralId: 9, commanderGeneralId: 9, nationId: 2, provinceId: 'A', commanderyNo: 2, visibility: 'FULL', own: false }] };

async function serve(page: Page, withBake: boolean, options: { corps?: boolean; holdPlaces?: Promise<void> } = {}) {
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
      if (options.holdPlaces && file.startsWith('places.json')) await options.holdPlaces;
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
    if (options.corps && url.pathname.endsWith('/api/visibility')) return route.fulfill({ json: VISIBILITY });
    if (options.corps && url.pathname.endsWith('/api/corps')) return route.fulfill({ json: CORPS });
    return route.fulfill({ status: 503, json: { error: 'smoke' } });
  });
}

/** 새 지도 자료(bake · 승인 키트) 요청 경로를 모은다 — 같은 파일은 한 번만 받아야 한다(옛 world-map 「省 PNG 한 번」). */
function recordMapFiles(page: Page): string[] {
  const files: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (path.includes(`/api/map/topdown/${BAKE_ID}/`) || path.startsWith('/map/waryong/273d596/')) files.push(path);
  });
  return files;
}

/**
 * 옛 지도판 몫 요청(지형 · 州 색인 · 省 그림). 새 지도는 州 색인 · 省 그림(운영 24,666,640 B)을 청하지 않는다(실지도 결함 5).
 * 지형만 구역 이름 캐시(영지 · 공성 · 조정 화면)용으로 한 번까지 받는다(#1231 리뷰). 옛 지도 시험이 같은 기록기로 州 색인을 잡아 기록기가 살아 있음을 보인다.
 */
function expectNewMapRequests(asked: string[], why: string): void {
  expect(asked.filter((name) => name !== 'terrain'), why).toEqual([]);
  expect(asked.filter((name) => name === 'terrain').length, `${why} — 이름용 지형은 한 번까지`).toBeLessThanOrEqual(1);
}

function recordOldMapRequests(page: Page): string[] {
  const asked: string[] = [];
  page.on('request', (request) => {
    const path = new URL(request.url()).pathname;
    if (/\/api\/map\/(terrain|ju|provinces)$/.test(path)) asked.push(path.slice(path.lastIndexOf('/') + 1));
  });
  return asked;
}

/**
 * 지도 상자 표본 15 × 15점 가운데 바탕색(#0c0f0e, 아직 안 그린 곳)이 아닌 점 수(옛 world-map 「칠한 점 > 150」).
 * 범위 밖 땅의 흐린 땅색(D42)도 세지 않는다 — 고정 bake는 조각 둘 밖이 다 그리지 않는 칸이라 조각을 못 받아도 이 색이 찬다.
 * WebGL 캔버스는 읽을 수 없어 상자 화면 사진으로 센다 — 지도 위 단추 · 핀 몫은 225점 중 일부라 문턱 150을 못 넘긴다.
 */
async function paintedSamples(page: Page, map: Locator): Promise<number> {
  const shot = await map.screenshot();
  const blank = [MAP_BACKGROUND, outOfScopeLandColour(join(FIXTURE, 'kit'))];
  return page.evaluate(async ({ png, blank }) => {
    const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(bitmap, 0, 0);
    let painted = 0;
    for (let i = 1; i < 16; i += 1) for (let j = 1; j < 16; j += 1) {
      const d = ctx.getImageData(Math.floor(bitmap.width * i / 16), Math.floor(bitmap.height * j / 16), 1, 1).data;
      if (blank.every((c) => Math.abs(d[0] - c[0]) + Math.abs(d[1] - c[1]) + Math.abs(d[2] - c[2]) > 12)) painted += 1;
    }
    return painted;
  }, { png: shot.toString('base64'), blank });
}

test.describe('작전실 새 지도(교체 스위치 빌드)', () => {
  test('서버가 bakeId를 주면 새 지도: 그려지고 휠 · 누르기가 된다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    const mapFiles = recordMapFiles(page);
    const oldMap = recordOldMapRequests(page);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
    await expect(page.getByRole('alert').filter({ hasText: '세력 색을 칠하지 못했습니다' })).toHaveCount(0);
    await map.scrollIntoViewIfNeeded();
    // 그려짐: 표본 225점 대부분이 지형으로 칠해졌다(바탕색만 남은 빈 그림이 아니다)
    await expect.poll(async () => paintedSamples(page, map), { timeout: 15_000 }).toBeGreaterThan(150);
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    // 가운데 = 내 城 = 내 위치 핀 끝. 핀 머리(지도 위 DOM, 48 × 62)는 그 위로 선다. 지도 조작은 일부러 핀 머리 위에서 한다 —
    // 핀 단추가 포인터를 먹으면 핀 위 끌기 · 휠이 지도로 가지 않는다(#1199 리뷰 회귀)
    const tipY = box.y + box.height / 2;
    const cy = tipY - 31;
    // 초점 = 합성 城 1(선무) 발자국 가운데. 내 城도 선무라 내 위치 핀이 그 위에 선다
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await expect(page.getByRole('button', { name: /^내 위치 — 하후돈/ })).toBeVisible();
    // 덮개 없음: 핀 머리 가운데 · 핀 끝 바로 위 · 핀 머리 모서리를 모두 지도 캔버스가 받는다
    const tops = await page.evaluate((points) => points.map(({ x, y }) => {
      const el = document.elementFromPoint(x, y);
      return el ? `${el.tagName}:${Boolean(el.closest('[data-map-renderer="topdown"]'))}` : null;
    }), [{ x: cx, y: cy }, { x: cx, y: tipY - 2 }, { x: cx - 20, y: tipY - 58 }]);
    expect(tops, '핀 머리 · 핀 끝 · 모서리를 받은 요소').toEqual(['CANVAS:true', 'CANVAS:true', 'CANVAS:true']);
    // 핀 머리를 누르면(탭) 렌더러 히트(kind 'me')로 내 城(선무)을 고른다 — 작전실 내 장수 카드(보드 me_card: 데스크톱 오른쪽 위 · 모바일 아래 알약, K4)에
    // 내 장수(하후돈)와 선무. 모바일은 고르기만으로 시트를 열지 않는다 — 아래 휠 · 끌기가 같은 자리에서 지도에 닿아야 한다
    await page.mouse.click(cx, cy);
    await expect(page.getByTestId('war-room-pick')).toContainText('하후돈');
    await expect(page.getByTestId('war-room-pick')).toContainText('선무');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    // 고른 城은 지도에 노란 테두리(보드 sel) — 발자국(3칸 × 6px)보다 커서 40 상자, 아래 변 가운데가 노랑이다(내 위치 핀은 위로 선다)
    await expect(map).toHaveAttribute('data-map-selected', '1');
    const shot = await map.screenshot();
    const edge = await page.evaluate(async ({ png, x, y, width }) => {
      const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
      const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
      const scale = bitmap.width / width;
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(bitmap, 0, 0);
      // 노란 선(2px)이 아래 변 위에 걸친다 — 내 위치 핀 줄기를 피해 가운데에서 12px 왼쪽.
      // 선이 어두운 둘레(4px) 안에 있어 한 점만 찍으면 반 화소 어긋남(DPR 3)에 둘레를 찍는다 — 변 위아래 ±4px 띠를 본다
      const strip: number[][] = [];
      for (let dy = -4; dy <= 4; dy += 1) strip.push(Array.from(ctx.getImageData(Math.round(x * scale), Math.round((y + dy) * scale), 1, 1).data.slice(0, 3)));
      return strip;
    }, { png: shot.toString('base64'), x: box.width / 2 - 12, y: box.height / 2 + 20, width: box.width });
    const yellow = (c: number[]) => Math.abs(c[0] - 0xff) <= 24 && Math.abs(c[1] - 0xd3) <= 24 && Math.abs(c[2] - 0x6d) <= 32;
    expect(edge.some(yellow), `고른 城 아래 변 띠에 노랑 없음 ${edge.map((c) => c.join(',')).join(' | ')}`).toBe(true);
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
    // 확대 · 끌기 뒤에도 같은 bake · 키트 파일을 두 번 받지 않는다(장소 표는 렌더러와 작전실 틀이 같이 쓴다)
    expect(mapFiles.length).toBeGreaterThan(0);
    expect(mapFiles.filter((path, index) => mapFiles.indexOf(path) !== index), '같은 지도 파일을 두 번 받았다').toEqual([]);
    // 새 지도는 미리보기만 받는다 — 옛 지도판 몫(지형 · 州 색인 · 省 그림)은 한 번도 청하지 않는다(실지도 결함 5)
    expectNewMapRequests(oldMap, '새 지도인데 옛 지도판 자료를 청했다');
    // 시야 · 첩보 줄의 郡은 옛 지형 대신 bake 장소 표에서 온다 — 내 城의 郡에 「지금 여기」
    const focusLine = page.getByTestId('commandery-focus');
    await expect(focusLine).toHaveText('시험군');
    await expect(focusLine.locator('xpath=..')).toContainText('지금 여기');
    // 작전실은 지도가 틀을 채운다(K4 P-W01) — 郡 정보 줄(시야 · 「첩보 보내기」)은 지도 위 겹층이라 화면 안에 보인다
    // (#1232 리뷰: 흐름 배치면 상자 밖으로 밀려 모바일에서 잘렸다).
    await expect(focusLine).toBeInViewport();
    // 겹층 띠는 글만 보이고 누르기는 지도로 지나간다(지도 표지 DOM 규칙) — 띠 위에서 누르면 지도 캔버스가 받는다(#1232 CI: 핀치 손가락이 띠에 떨어졌다)
    expect(await focusLine.evaluate((el) => {
      const b = el.getBoundingClientRect();
      return document.elementFromPoint(b.x + b.width / 2, b.y + b.height / 2)?.tagName ?? null;
    }), '郡 정보 줄이 지도 누르기를 먹는다').toBe('CANVAS');
    // 모바일: 띠는 엿보기 시트 · 선택 알약 위에 선다(겹치면 띠가 그 밑에 깔려 안 보인다)
    const peek = page.getByRole('region', { name: '명령 목록 12순 — 다음 순' });
    if (await peek.count()) {
      const line = (await focusLine.locator('xpath=..').boundingBox())!;
      const peekTop = (await peek.boundingBox())!.y;
      // 위에서 핀을 눌러 내 장수를 골랐으니 선택 알약은 「내 장수 — 하후돈」이다(작전실 내 장수 카드)
      const pillTop = (await page.getByRole('button', { name: '내 장수 — 하후돈', exact: true }).boundingBox())!.y;
      expect(line.y + line.height, '郡 정보 줄이 엿보기 시트 · 선택 알약에 걸린다').toBeLessThanOrEqual(Math.min(peekTop, pillTop) + 1);
    }
  });

  test('지도 위 조작(보드 MapViewBar · 레이어 · 범례): 44 · 안 가림, 주 · 군 · 현 · + · 내 위치로 · 레이어 · 범례가 지도를 바꾼다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.scrollIntoViewIfNeeded();

    // 그려짐: 단추는 모두 44 이상이고 가운데의 맨 위 요소가 그 단추 자신이다(겹친 상자가 먹지 않는다).
    // 모바일은 지도 상자(560)가 화면보다 길어 아래 단추가 고정 하단 탭 밑에 걸린다 — 사람처럼 단추를 화면 가운데로 굴린 뒤 본다.
    const controls = page.locator('[data-map-control] button');
    const count = await controls.count();
    expect(count).toBeGreaterThanOrEqual(8);
    for (let i = 0; i < count; i += 1) {
      const button = controls.nth(i);
      await button.evaluate((node) => node.scrollIntoView({ block: 'center', inline: 'center' }));
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
    // 지도 위 층(내 위치 · 레이어 판 · 보기 단추)은 셸이 띄우는 층(--z-float 30 · 서랍 · 시트 · 대화상자) 밑에서만 겨룬다.
    // 옛 지도는 지도판에 isolation을 걸어 막았다. 새 지도는 층 토큰(--z-map-ctrl 20 + 1까지)으로 막는다 — 그 위로 새는 층이 없어야 한다.
    const leaks = await map.evaluate((node) => {
      const box = node.parentElement!;
      const float = Number(getComputedStyle(document.documentElement).getPropertyValue('--z-float'));
      return [...box.querySelectorAll('*')].map((el) => ({ el, z: Number(getComputedStyle(el).zIndex) }))
        .filter(({ z }) => Number.isFinite(z) && z >= float)
        .map(({ el, z }) => `${el.tagName}${el.getAttribute('aria-label') ? `(${el.getAttribute('aria-label')})` : ''} z=${z}`)
        .concat(Number.isFinite(float) && float > 0 ? [] : [`--z-float 없음(${float})`]);
    });
    expect(leaks, '지도 층이 셸 층 위로 샌다').toEqual([]);

    await map.scrollIntoViewIfNeeded();
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
    // 가운데는 내 위치 핀이라 30 아래에서 끈다
    await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2 + 30);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width / 2 - 150, box.y + box.height / 2 - 60, { steps: 8 });
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
    // 판은 지도 상자 안에 펼친다(모바일 작전실 좁은 열에서 왼쪽이 잘린 적이 있다)
    const panelBox = (await layersPanel.boundingBox())!;
    const mapBox = (await map.boundingBox())!;
    expect(panelBox.x, '레이어 판이 지도 왼쪽 끝을 넘었다').toBeGreaterThanOrEqual(mapBox.x - 1);
    expect(panelBox.x + panelBox.width, '레이어 판이 지도 오른쪽 끝을 넘었다').toBeLessThanOrEqual(mapBox.x + mapBox.width + 1);
    // 열린 판의 줄은 다른 지도 조작에 가리지 않는다(모바일 좁은 열에서 왼쪽 아래 보기 단추가 판 위에 올라탄 적이 있다).
    // 보기 단추는 줄의 왼쪽만 덮어 가운데 한 점으로는 못 잡는다 — 왼쪽 · 가운데 · 오른쪽 세 점을 본다
    const rows = layersPanel.getByRole('button');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(5);
    for (let i = 0; i < rowCount; i += 1) {
      const row = rows.nth(i);
      await row.evaluate((node) => node.scrollIntoView({ block: 'center', inline: 'center' }));
      const hit = await row.evaluate((node) => {
        const rect = node.getBoundingClientRect();
        for (const x of [rect.x + 6, rect.x + rect.width / 2, rect.right - 6]) {
          const top = document.elementFromPoint(x, rect.y + rect.height / 2);
          if (!(top && (top === node || node.contains(top)))) return `${Math.round(x - rect.x)}px: ${top?.getAttribute('aria-label') ?? top?.textContent ?? top?.tagName ?? 'none'}`;
        }
        return null;
      });
      expect(hit, `레이어 판 줄 ${i}(${await row.textContent()})를 가린 것`).toBeNull();
    }
    // 서버 대기 줄의 이름은 한 줄이다(좁은 판에서 「보/급/선」 한 글자씩 접힌 적이 있다)
    const pendingNames = layersPanel.locator('[data-pending-layer] > span:first-child');
    expect(await pendingNames.count()).toBe(3);
    for (const name of await pendingNames.all()) {
      const nameBox = (await name.boundingBox())!;
      expect(nameBox.height, `서버 대기 줄 이름 「${await name.textContent()}」이 여러 줄로 접혔다`).toBeLessThan(30);
    }
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
    // ⑥ 작은 지도(보드 V31 · 실지도 결함 2): 데스크톱 작전실에만 있다 — 모바일(V31K4MWarRoom)에는 없다.
    // 모바일 「없음」은 위 조작들(수 초) 뒤에 본다. 데스크톱 「있음」이 같은 빌드에서 작은 지도 그림이 뜨는 길을 보인다
    const minimap = page.getByRole('button', { name: /작은 지도/ });
    if (test.info().project.name === 'mobile') await expect(minimap).toHaveCount(0);
    else await expect(minimap).toBeVisible();
  });

  // 작전실 주소로 연 보기(K0 10-02 배정, K8 천하 형세 「지도에서 보기 — 주 경계」가 쓴다). 처음 한 번만 맞추고, 모르는 값은 기본 보기.
  test('주소 ?view=…&focus=… 로 지도 보기 수준 · 초점 城을 연다, 모르는 값은 기본 보기', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    const map = page.locator('[data-map-renderer="topdown"]');
    const open = async (search: string) => {
      await page.goto(`/game${search}`);
      await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    };
    // 주 보기
    await open('?view=ju');
    await expect(map).toHaveAttribute('data-map-level', 'ju', { timeout: 15_000 });
    await expect(page.getByRole('radio', { name: '주 보기' })).toHaveAttribute('aria-checked', 'true');
    // 현 보기 + 초점 城(합성 bake 城 1 = 선무) → 그 城 가운데 현 보기
    await open('?view=county&focus=1');
    await expect(map).toHaveAttribute('data-map-level', 'county', { timeout: 15_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    // 모르는 값 · 모르는 城 → 기본(초점 城 郡 보기)
    await open('?view=province&focus=999999');
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await expect(map).toHaveAttribute('data-map-level', 'commandery');
    await expect(map).toHaveAttribute('data-map-zoom', '6.000');
  });

  // 주소에 城이 없으면(?view=county) 수준은 지키고 중심은 내 城을 따른다(#1213 리뷰). 순이 넘어 front-info를 다시 읽어 내 城이
  // 바뀌면(이동), 사용자가 아무것도 누르지 않았어도 새 내 城으로 옮긴다 — 현 보기 · 배율 그대로. 장소 표에 城 하나(옆성)를 더한다.
  test('주소에 城이 없으면 수준은 지키고, 순이 넘어 바뀐 내 城으로 중심을 옮긴다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    const places = JSON.parse(gunzipSync(readFileSync(join(FIXTURE, 'bake', 'places.json.gz'))).toString('utf8'));
    places.cities.push({ ...places.cities[0], id: 2, name: '옆성', cell: [1450, 930], isSeat: false, roofCell: [1450, 930],
      footprint: { originCol: 1449, originRow: 929, span: 3, innerSpan: 0 } });
    await page.route((url) => url.pathname.endsWith(`/api/map/topdown/${BAKE_ID}/places.json.gz`),
      (route) => route.fulfill({ status: 200, body: gzipSync(Buffer.from(JSON.stringify(places))), contentType: 'application/octet-stream' }));
    const base = preview(true);
    await page.route((url) => url.pathname.endsWith('/api/map/preview'), (route) => route.fulfill({ json: {
      ...base, cities: [...base.cities, { ...base.cities[0], id: 2, name: '옆성', isCommanderySeat: false }] } }));
    let moved = false;
    await page.route((url) => url.pathname.endsWith('/front-info'),
      (route) => route.fulfill({ json: moved ? FRONT_INFO : { ...FRONT_INFO, city: { id: 2, name: '옆성' } } }));
    // 순 넘김 신호(셸 SSE turnCompleted) — 놓아 줄 때까지 붙잡는다
    let turn!: () => void;
    const turned = new Promise<void>((resolve) => { turn = resolve; });
    await page.route((url) => url.pathname === '/api/game/sse/turn', async (route) => {
      await turned;
      await route.fulfill({ status: 200, contentType: 'text/event-stream', body: 'event: turnCompleted\ndata: {}\n\n' });
    });
    await page.goto('/game?view=county');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    // 처음: 내 城 = 옆성, 현 보기
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1450.5,930.5');
    await expect(map).toHaveAttribute('data-map-level', 'county');
    const zoom = await map.getAttribute('data-map-zoom');
    // 순이 넘어 내 城이 선무로 바뀐다(아무것도 누르지 않음) → 중심만 선무로, 현 보기 · 배율 그대로
    moved = true;
    turn();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await expect(map).toHaveAttribute('data-map-level', 'county');
    await expect(map).toHaveAttribute('data-map-zoom', zoom!);
  });

  // M2-11 내 위치 표지(보드 V31SystemMarker · V31SystemMMarker): 내 장수 초상 핀(핀 끝 = 내 城 가운데), 모든 보기 수준에서 같은 크기,
  // 현 보기에서만 꼬리표, 화면 밖이면 가장자리 「내 위치」 단추 + 거리 → 누르면 돌아온다. 성 밖 · 군단 · 이동 중은 U-04 대기.
  test('내 위치 표지: 내 장수 핀이 모든 보기 수준에 서고, 화면 밖이면 가장자리 단추로 돌아온다', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.evaluate((node) => node.scrollIntoView({ block: 'center' }));
    await expect(page.locator('[data-my-location]')).toHaveAttribute('data-server-wait', 'U-04');
    // 이름 · 링은 내 장수 · 내 세력(초상이 없는 합성 장수라 이름 첫 글자 「하」, 城 이름 「선」이 아니다)
    const pin = page.getByRole('button', { name: '내 위치 — 하후돈, 성 안. 누르면 내 장수 카드' });
    await expect(pin).toBeVisible();
    await expect(pin).toContainText('하');
    const mapBox = (await map.boundingBox())!;
    const pinBox = (await pin.boundingBox())!;
    expect(Math.abs(pinBox.x + pinBox.width / 2 - (mapBox.x + mapBox.width / 2)), '핀 끝 가로 = 내 城 가운데').toBeLessThan(2);
    expect(Math.abs(pinBox.y + pinBox.height - (mapBox.y + mapBox.height / 2)), '핀 끝 세로 = 내 城 가운데').toBeLessThan(2);
    // 보기 수준을 바꿔도 핀 크기는 같고(화면 48), 꼬리표는 현 보기에서만
    for (const [name, tag] of [['군 보기', false], ['현 보기', true]] as const) {
      await page.getByRole('radio', { name }).click();
      await expect(pin).toBeVisible();
      expect((await pin.boundingBox())!.width, `${name} 핀 폭`).toBe(48);
      if (tag) await expect(pin).toContainText('내 위치 · 성 안');
      else await expect(pin).not.toContainText('내 위치 ·');
    }
    // 주 보기는 지도 가장자리에서 보는 곳이 당겨져(합성 bake는 작다) 내 城이 화면 밖일 수 있다 — 핀이든 가장자리 단추든 늘 하나는 있다
    const edge = page.getByRole('button', { name: /^내 위치는 화면 밖 — \d+칸, 누르면 그리로$/ });
    await page.getByRole('radio', { name: '주 보기' }).click();
    await expect(page.locator('[data-my-location]')).not.toHaveAttribute('data-my-location', 'none');
    expect(await pin.count() + await edge.count(), '주 보기에서 핀 또는 가장자리 단추').toBe(1);
    // 화면 밖: 내 자리(현 보기)로 돌아와서 지도를 왼쪽으로 크게 민다(가운데 높이). 내 城이 가로로만 빠져
    // 단추가 왼쪽 가장자리 가운데 높이에 선다 — 왼쪽 아래 보기 단추와 겹치는 자리다
    await page.getByRole('button', { name: '내 위치로(Home)' }).click();
    await expect(pin).toBeVisible();
    const y = mapBox.y + mapBox.height / 2;
    for (let i = 0; i < 4 && !(await edge.isVisible()); i += 1) {
      await page.mouse.move(mapBox.x + mapBox.width * 0.8, y);
      await page.mouse.down();
      await page.mouse.move(mapBox.x + mapBox.width * 0.2, y, { steps: 6 });
      await page.mouse.up();
      await page.waitForTimeout(400);
    }
    await expect(edge).toBeVisible();
    await expect(pin).toHaveCount(0);
    // 화면 밖 단추는 지도 조작에 깔리지 않는다(모바일 좁은 지도에서 왼쪽 보기 단추 밑에 깔린 적이 있다) — 왼쪽 · 가운데 · 오른쪽
    const covered = await edge.evaluate((node) => {
      const rect = node.getBoundingClientRect();
      for (const x of [rect.x + 6, rect.x + rect.width / 2, rect.right - 6]) {
        const top = document.elementFromPoint(x, rect.y + rect.height / 2);
        if (!(top && (top === node || node.contains(top)))) return `${Math.round(x - rect.x)}px: ${top?.getAttribute('aria-label') ?? top?.textContent ?? top?.tagName ?? 'none'}`;
      }
      return null;
    });
    expect(covered, '화면 밖 단추를 가린 것').toBeNull();
    const edgeBox = (await edge.boundingBox())!;
    expect(edgeBox.width).toBeGreaterThanOrEqual(44);
    expect(edgeBox.height).toBeGreaterThanOrEqual(52);
    expect(edgeBox.x, '단추가 지도 왼쪽 가장자리 안').toBeGreaterThanOrEqual(mapBox.x - 1);
    await edge.click();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).toBe('1400.5,900.5');
    await expect(pin).toBeVisible();
    // 핀을 누르면 내 장수 카드(K4). 핀 단추는 포인터를 받지 않아 탭은 지도 렌더러 히트가 받는다
    const head = (await pin.boundingBox())!;
    await page.mouse.click(head.x + head.width / 2, head.y + 24);
    await expect(page.getByTestId('war-room-pick')).toContainText('하후돈');
    // 키보드로는 핀 단추가 고른다(Tab · Enter) — Esc 로 풀고 다시 고른다
    await map.focus();
    await page.keyboard.press('Escape');
    await expect(page.getByTestId('war-room-pick')).toHaveCount(0);
    await pin.focus();
    await page.keyboard.press('Enter');
    await expect(page.getByTestId('war-room-pick')).toContainText('하후돈');
  });

  // M2-7: 옛 지도가 그리던 군단(시야 거르기 뒤)을 새 지도에도 싣는다. 지도 뿌리의 실린 수로 본다(표지 그리기 · 누르기는 지도 시험 화면 spec이 본다).
  // 군단 자리는 bake 개관 격자의 구역 대표 칸이다 — 옛 省 식별 PNG가 없어도(운영은 24.7MB라 16MiB 상한으로 버려진다) 선다.
  test('군단: 보이는 郡의 군단만 새 지도에 실린다(옛 省 PNG 없이)', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, true, { corps: true });
    const oldMap = recordOldMapRequests(page);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(map).toHaveAttribute('data-map-corps', '1', { timeout: 15_000 });
    expectNewMapRequests(oldMap, '군단 자리에 옛 省 그림을 청했다');
  });

  // M2-7 키보드(보드 B1: 방향키 옮기기 · +/− 확대 · Esc 선택 해제). 모바일 열은 「—」라 데스크톱만.
  test('키보드: 방향키는 옮기고, − 는 축소, Esc 는 고른 城을 푼다', async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.scrollIntoViewIfNeeded();
    const box = (await map.boundingBox())!;
    await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    await expect(map).toHaveAttribute('data-map-selected', '1');
    const center = async () => (await map.getAttribute('data-map-center'))!.split(',').map(Number);
    await map.focus();
    await page.keyboard.press('ArrowRight');
    await expect.poll(async () => (await center())[0]).toBeGreaterThan(1400.5);
    await page.keyboard.press('ArrowDown');
    await expect.poll(async () => (await center())[1]).toBeGreaterThan(900.5);
    const zoomBefore = Number(await map.getAttribute('data-map-zoom'));
    await page.keyboard.press('-');
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom'))).toBeLessThan(zoomBefore);
    await page.keyboard.press('Escape');
    await expect(map).not.toHaveAttribute('data-map-selected', /.+/);
    await expect(page.getByTestId('war-room-pick')).toHaveCount(0);
  });

  // M2-7 모바일(보드 B1: 한 손가락 끌기 · 핀치). 마우스 흉내가 아니라 실제 터치 점(CDP)을 넣는다.
  test('터치: 한 손가락으로 끌면 옮기고, 두 손가락을 벌리면 확대한다', { tag: ['@mobile-only'] }, async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.evaluate((node) => node.scrollIntoView({ block: 'center' }));
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    const cdp = await page.context().newCDPSession(page);
    // 모바일 흉내는 터치 점을 하나만 받는다 — 핀치에 두 점이 필요하다
    await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });
    const touch = (type: 'touchStart' | 'touchMove' | 'touchEnd', points: { x: number; y: number }[]) =>
      cdp.send('Input.dispatchTouchEvent', { type, touchPoints: points.map((p, index) => ({ ...p, id: index + 1, radiusX: 4, radiusY: 4, force: 1 })) });
    // 한 손가락 끌기
    await touch('touchStart', [{ x: cx, y: cy }]);
    for (let step = 1; step <= 8; step += 1) await touch('touchMove', [{ x: cx - step * 8, y: cy - step * 6 }]);
    await touch('touchEnd', []);
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe('1400.5,900.5');
    // 두 손가락 벌리기(핀치 확대) — 놓으면 가까운 멈춤 자리로 붙는다
    await expect.poll(async () => map.getAttribute('data-map-zoom'), { timeout: 10_000 }).toMatch(/\.000$/);
    const zoomBefore = Number(await map.getAttribute('data-map-zoom'));
    // 위아래로 벌린다 — 지금 모바일 작전실 지도 열(151)은 좌우에 조작 단추가 있어 가로로 벌리면 손가락이 단추에 닿는다.
    // 가운데 위도 피한다: 끈 뒤 내 위치 핀이 화면 밖이면 「내 위치」 가장자리 단추가 보기 단추를 비켜 가운데 위쪽에 선다(M2-11).
    const fingers = [{ x: cx, y: cy + 20 }, { x: cx, y: cy + 80 }];
    const hits = await page.evaluate((points) => points.map(({ x, y }) => document.elementFromPoint(x, y)?.tagName ?? null), fingers);
    expect(hits, '핀치 손가락이 지도 캔버스가 아닌 것(조작 단추 · 표지)에 닿는다').toEqual(['CANVAS', 'CANVAS']);
    await touch('touchStart', fingers);
    for (let step = 1; step <= 10; step += 1) await touch('touchMove', [{ x: cx, y: cy + 20 - step * 9 }, { x: cx, y: cy + 80 + step * 9 }]);
    await touch('touchEnd', []);
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom')), { timeout: 10_000 }).toBeGreaterThan(zoomBefore);
  });

  // 옛 world-map-focus 규칙: 늦게 온 자료가 사용자가 움직인 지도를 끌고 가지 않는다. 장소 표(places)를 붙잡아 두고 지도가 준비된 뒤
  // 끌고 나서 놓아 준다 — 초점 城으로 다시 끌려가면 안 된다.
  test('장소 표가 늦게 와도 먼저 끈 지도는 그대로 둔다', { tag: [BOTH] }, async ({ page }) => {
    let release!: () => void;
    const holdPlaces = new Promise<void>((resolve) => { release = resolve; });
    await serve(page, true, { holdPlaces });
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await map.evaluate((node) => node.scrollIntoView({ block: 'center' }));
    const box = (await map.boundingBox())!;
    const y = box.y + box.height / 2 + 30;
    await page.mouse.move(box.x + box.width * 0.6, y);
    await page.mouse.down();
    await page.mouse.move(box.x + box.width * 0.4, y - 20, { steps: 6 });
    await page.mouse.up();
    await page.waitForTimeout(600); // 관성이 멎을 때까지
    const dragged = await map.getAttribute('data-map-center');
    release();
    // 장소 표가 오면 군단 · 내 위치 표지가 서지만, 카메라는 끈 자리 그대로다
    await expect(page.getByRole('button', { name: '내 위치로(Home)' })).not.toHaveAttribute('aria-disabled', 'true', { timeout: 15_000 });
    await page.waitForTimeout(500);
    expect(await map.getAttribute('data-map-center'), '늦게 온 장소 표가 카메라를 초점 城으로 끌고 갔다').toBe(dragged);
  });

  // 옛 world-map-focus 짝: 손대지 않았으면 늦게 온 장소 표가 카메라를 초점 城으로 옮긴다(장소 표 전에는 bake 전체 보기).
  test('장소 표가 늦게 와도 손대지 않은 지도는 초점 城으로 간다', { tag: [BOTH] }, async ({ page }) => {
    let release!: () => void;
    const holdPlaces = new Promise<void>((resolve) => { release = resolve; });
    await serve(page, true, { holdPlaces });
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await page.waitForTimeout(500);
    expect(await map.getAttribute('data-map-center'), '장소 표 전인데 이미 초점 城이다(늦은 자료를 못 본다)').not.toBe('1400.5,900.5');
    release();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
  });

  // 옛 season-map 규칙(K8 · 셸): 계절 패널을 연 채로도 지도 한 점은 지도에 닿고(투명 덮개 없음), 휠은 패널을 둔 채 지도만,
  // 끌기의 누름은 바깥 누름이라 패널을 닫고 같은 끌기로 지도도 옮긴다. 모바일은 하단 시트(모달 덮개)라 해당 없다 — 데스크톱만.
  test('계절 패널을 연 채로 지도를 누르면 지도에 닿고, 휠 · 끌기로 지도가 바뀐다', async ({ page }) => {
    await serve(page, true);
    await page.goto('/game');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 15_000 }).toBe('1400.5,900.5');
    await map.scrollIntoViewIfNeeded();
    await page.getByRole('button', { name: /^봄 · 200년 3월/ }).click();
    const dialog = page.getByRole('dialog', { name: '계절 — 봄' });
    await expect(dialog).toBeVisible();
    // 패널 밖 지도 한 점 — 왼쪽 위 사분면(패널은 오른쪽 위, 보기 단추는 왼쪽 아래, 내 위치 핀은 가운데)
    const box = (await map.boundingBox())!;
    const pop = (await dialog.boundingBox())!;
    const x = box.x + box.width * 0.25;
    const y = box.y + box.height * 0.3;
    expect(x < pop.x || y > pop.y + pop.height || y < pop.y, '고른 점이 패널 밖이어야 한다').toBe(true);
    const top = await page.evaluate(({ px, py }) => {
      const el = document.elementFromPoint(px, py);
      return el ? `${el.tagName}:${Boolean(el.closest('[data-map-renderer="topdown"]'))}` : null;
    }, { px: x, py: y });
    expect(top, '패널이 열려 있어도 지도 점은 지도에 닿아야 한다').toBe('CANVAS:true');
    // 휠은 누름이 아니다 — 패널은 그대로, 지도만 확대
    const zoomBefore = Number(await map.getAttribute('data-map-zoom'));
    await page.mouse.move(x, y);
    await page.mouse.wheel(0, -400);
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom')), { timeout: 10_000 }).toBeGreaterThan(zoomBefore);
    await expect(dialog).toBeVisible();
    // 끌기: 패널이 닫히고, 같은 끌기로 지도도 옮겨 간다(먹히지 않는다)
    const centreBefore = await map.getAttribute('data-map-center');
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + 80, y + 40, { steps: 6 });
    await page.mouse.up();
    await expect(dialog).toBeHidden();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centreBefore);
  });

  // 스위치를 켠 운영 이미지가 bake 활성(A04)보다 먼저 나가도 작전실이 바뀌지 않는다는 증거(K0 10-02 「가」 조건 1)
  test('bakeId가 없으면 옛 지도 그대로, 새 지도 자료는 받지 않는다', { tag: [BOTH] }, async ({ page }) => {
    const asked: string[] = [];
    page.on('request', (request) => asked.push(new URL(request.url()).pathname));
    await serve(page, false);
    const oldMap = recordOldMapRequests(page);
    await page.goto('/game');
    await expect(page.locator('.os-iso-map__canvas').first()).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
    expect(asked.filter((path) => path.includes('/map/topdown/') || path.startsWith('/map/waryong/')), 'bakeId가 없는데 새 지도 자료를 받았다').toEqual([]);
    // 옛 지도는 州 색인까지 받는다 — 새 지도 시험의 「0건」이 죽은 기록기의 0이 아니라는 양성 대조
    expect(oldMap).toContain('ju');
  });
});
