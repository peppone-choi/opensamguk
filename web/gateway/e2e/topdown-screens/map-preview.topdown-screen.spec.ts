// 게이트웨이 지도 미리보기(MapPreview) 세 화면 — 로그인 배경 · 가입 배경 · 로비 카드 펼친 지도(M2-8).
// 제품 화면 새 지도 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1)가 켜진 기본 빌드에서 돈다(운영 이미지와 같은 값, CI topdown screens 단계 · 포트 3002).
// - bakeId가 오면 새 지도. 「그려졌다」(상태 · 옛 지형 안 받음)와 「조작된다」(드러난 자리 휠 · 끌기 · 누르기)를 따로 본다.
// - bakeId가 없으면(운영 bake 활성 A04 전) 세 화면 모두 옛 지도판이 그려지고 조작되며 새 지도 자료는 받지 않는다 — 스위치를 켠 이미지가
//   먼저 나가도 운영 화면이 바뀌지 않는다는 증거다(K0 10-02 「가」 조건 1).
// 합성 bake · 키트(web/game/e2e/fixtures/topdown, 원작 그림 없음)와 합성 옛 지형을 page.route로 대 준다. 서버 목록은 SERVER_REGISTRY_JSON(pep · uni).
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { deflateSync } from 'node:zlib';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow, isMobile } from '../../../game/e2e/support/parity';

const FIXTURE = join(__dirname, '..', '..', '..', 'game', 'e2e', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
/** 합성 bake의 城 1(선무) 발자국 가운데 칸. */
const CITY = { col: 1400.5, row: 900.5 };

// ---------------------------------------------------------------- 합성 자료

/** 합성 bake의 城 1과 같은 id · 이름, 구역 2개(합성 bake provinceCount). */
function preview(withBake: boolean, oldMap = false) {
  return {
    serverName: 'pep', year: 200, month: 3, turnPhaseText: '중순',
    mapCode: withBake || oldMap ? 'han-world-v3' : 'smoke-unsupported', width: 700, height: 610,
    cities: [{ id: 1, name: '선무', level: 8, nationId: 1, state: 0, supply: true, x: oldMap ? 350 : 116, y: oldMap ? 305 : 101,
      isCommanderySeat: true, commanderyName: '하남윤', provinceId: oldMap ? 0 : 1 }],
    nations: [{ id: 1, name: '위', color: '#b03a2e' }],
    provinceOccupancy: [
      { provinceRecordId: 'A', provinceIndex: 0, nationId: 1 },
      { provinceRecordId: 'B', provinceIndex: 1, nationId: 0 },
    ],
    ...(withBake ? { topdownBakeId: BAKE_ID } : {}),
  };
}

// 옛 지도판용 합성 지형(32×32) · 州 색인 · 省 식별 PNG. 지형 로더는 ETag 의 sha256 과 州 색인 원천 지문을 맞춰 본다.
const OLD_TILES = {
  _meta: { cols: 32, rows: 32, year: 200, terrainLegend: { '0': 'SEA', '1': 'PLAIN', '2': 'MOUNTAIN' } },
  terrain: Array.from({ length: 32 }, (_, row) => '0'.repeat(4) + (row % 4 ? '1' : '2').repeat(28)),
  owner: [[0, 1024]], parentOwner: [[0, 1024]],
  juns: [{ name: '하남윤', nameCh: '', seat: 0, col: 16, row: 16 }],
  parentRegions: [{ id: 'R1', displayName: '하남윤', nameCh: '', administrativeSystem: 'HAN_COMMANDERY' }],
  cities: [{ id: 'A', name: '선무', nameCh: '', level: 8, kind: 'COUNTY', seat: true, col: 16, row: 16, lat: 0, lon: 0 }],
  adjacency: { county: [], commandery: [] }, regions: [],
};
const OLD_BODY = JSON.stringify(OLD_TILES);
const OLD_SHA = createHash('sha256').update(OLD_BODY).digest('hex');

function oldProvincePng(): Buffer {
  const crc = (bytes: Buffer) => {
    let value = 0xffffffff;
    for (const byte of bytes) {
      value ^= byte;
      for (let bit = 0; bit < 8; bit += 1) value = (value >>> 1) ^ (value & 1 ? 0xedb88320 : 0);
    }
    return (value ^ 0xffffffff) >>> 0;
  };
  const chunk = (type: string, bytes: Buffer) => {
    const head = Buffer.alloc(4); head.writeUInt32BE(bytes.length);
    const data = Buffer.concat([Buffer.from(type), bytes]);
    const tail = Buffer.alloc(4); tail.writeUInt32BE(crc(data));
    return Buffer.concat([head, data, tail]);
  };
  const header = Buffer.alloc(13);
  header.writeUInt32BE(32, 0); header.writeUInt32BE(32, 4); header[8] = 8; header[9] = 2;
  const rows = Buffer.alloc(32 * (1 + 32 * 3));
  for (let row = 0; row < 32; row += 1) for (let col = 0; col < 32; col += 1) {
    const at = row * 97 + 1 + col * 3;
    rows[at + 1] = 16; rows[at + 2] = 1; // 식별 = ((부모 + 1) << 12) | (구역 + 1)
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const OLD_PNG = oldProvincePng();

const contentType = (path: string) => (path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream');
const fixtureFile = (dir: string, file: string) => {
  try {
    return { status: 200, body: readFileSync(join(FIXTURE, dir, file)), contentType: contentType(file) };
  } catch {
    return { status: 404, body: '' };
  }
};

const LOBBY_GAME = {
  isUnited: 0, year: 200, month: 3, turnPhaseText: '중순', scenario: '군웅할거', maxUserCnt: 30, turnTerm: 10,
  userCnt: 24, npcCnt: 412, nationCnt: 5, blockGeneralCreate: 0, status: 'OPEN', catchUp: { active: false, multiplier: 2 },
};

/** 미리보기 · 공개 피드 · 지도 자료를 대 주고, 받은 경로를 모은다. 로비는 합성 로그인(쿠키 + /api/auth/me)까지. */
async function serve(page: Page, { bake, oldMap = false, lobby = false, baseURL }: { bake: boolean; oldMap?: boolean; lobby?: boolean; baseURL?: string }) {
  const asked: string[] = [];
  page.on('request', (request) => asked.push(new URL(request.url()).pathname));
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route('**/api/server-map/**', (route) => route.fulfill(json(preview(bake, oldMap))));
  await page.route('**/api/server-events/**', (route) => route.fulfill(json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [] })));
  if (lobby) {
    await page.context().addCookies([{ name: 'sam_access', value: 'smoke', url: baseURL ?? 'http://127.0.0.1:3000' }]);
    await page.route('**/api/auth/me', (route) => route.fulfill(json({ user: { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 } })));
    await page.route('**/api/server-basic-info/**', (route) => route.fulfill(json({ game: LOBBY_GAME, me: { name: '하후돈', picture: null, imageServer: 0 } })));
  }
  if (oldMap) {
    await page.route('**/api/game/api/map/terrain?*', (route) => route.fulfill({ ...json(OLD_TILES), headers: { etag: `"sha256-${OLD_SHA}"` } }));
    await page.route('**/api/game/api/map/ju?*', (route) => route.fulfill(json({ sourceSha256: OLD_SHA, juByParent: ['사예'] })));
    await page.route('**/api/game/api/map/provinces?*', (route) => route.fulfill({ status: 200, body: OLD_PNG, contentType: 'image/png' }));
  }
  await page.route((url) => url.pathname.startsWith('/map/waryong/273d596/'), (route) =>
    route.fulfill(fixtureFile('kit', new URL(route.request().url()).pathname.replace('/map/waryong/273d596/', ''))));
  await page.route((url) => url.pathname.startsWith(`/api/game/api/map/topdown/${BAKE_ID}/`), (route) => {
    const url = new URL(route.request().url());
    // 게이트웨이 프록시가 서버를 고르는 값 — 모든 bake 파일에 실려야 한다
    if (url.searchParams.get('server') !== 'pep') return route.fulfill({ status: 400, body: 'server 빠짐' });
    return route.fulfill(fixtureFile('bake', url.pathname.slice(`/api/game/api/map/topdown/${BAKE_ID}/`.length)));
  });
  return asked;
}

type Screen = 'login' | 'join' | 'lobby';

/** 화면을 열고 지도 미리보기가 놓인 곳(로비는 pep 카드 「현황 펼치기」)까지 간다. */
async function openScreen(page: Page, screen: Screen, base = '') {
  if (screen === 'login') {
    await page.goto(`${base}/login`);
    await expect(page.getByRole('button', { name: /^pep/ }), '서버 목록이 비었다 — SERVER_REGISTRY_JSON 으로 게이트웨이를 띄웠는지 확인').toBeVisible();
  } else if (screen === 'join') {
    await page.goto(`${base}/join`);
    await expect(page.getByRole('heading', { level: 1, name: '회원 가입' })).toBeVisible();
  } else {
    await page.goto(`${base}/lobby`);
    const card = page.getByRole('article', { name: 'pep' });
    await expect(card, '서버 목록이 비었다 — SERVER_REGISTRY_JSON 으로 게이트웨이를 띄웠는지 확인').toBeVisible();
    await card.getByRole('button', { name: '현황 펼치기' }).click();
  }
}

const oldMapPaths = (asked: string[]) => asked.filter((path) => /\/api\/game\/api\/map\/(terrain|provinces)/.test(path));
const newMapPaths = (asked: string[]) => asked.filter((path) => /\/map\/(topdown|waryong)\//.test(path));

// ---------------------------------------------------------------- 그려짐 · 조작됨 도우미

/** 지금 카메라(data-map-center · data-map-zoom)로 칸이 화면 어디에 그려지는지. */
async function screenOf(map: Locator, cell: { col: number; row: number }) {
  const box = (await map.boundingBox())!;
  const [col, row] = (await map.getAttribute('data-map-center'))!.split(',').map(Number);
  const zoom = Number(await map.getAttribute('data-map-zoom'));
  return { x: box.x + box.width / 2 + (cell.col - col) * zoom, y: box.y + box.height / 2 + (cell.row - row) * zoom, zoom };
}

/** 그 화면 점에서 맨 위 요소가 새 지도 캔버스인가(겹친 패널 · 투명 상자가 입력을 먹지 않는가). */
async function mapOnTop(page: Page, at: { x: number; y: number }) {
  return page.evaluate(({ x, y }) => {
    const el = document.elementFromPoint(x, y);
    return el ? `${el.tagName}:${Boolean(el.closest('[data-map-renderer="topdown"]'))}` : null;
  }, at);
}

/**
 * 지도가 드러난 자리 — 화면 안에서 시작점과 끌기 끝점(+60, +40)의 맨 위 요소가 둘 다 이 지도(새 지도면 그 상자, 옛 지도면 그 캔버스)인 첫 점.
 * 배경 지도는 소개 · 로그인 · 가입 판이 대부분을 덮어 가운데부터 고른다.
 */
async function exposedPoint(target: Locator) {
  return target.evaluate((node) => {
    const { x, y, width, height } = node.getBoundingClientRect();
    const mine = (el: Element | null) => Boolean(el && (el === node || node.contains(el)));
    for (const [fx, fy] of [[0.5, 0.5], [0.5, 0.3], [0.3, 0.3], [0.7, 0.3], [0.3, 0.6], [0.7, 0.6], [0.5, 0.8], [0.15, 0.15], [0.85, 0.15]]) {
      const at = { x: x + width * fx, y: y + height * fy };
      const end = { x: at.x + 60, y: at.y + 40 };
      const inside = [at, end].every((p) => p.x >= 0 && p.x < innerWidth && p.y >= 0 && p.y < innerHeight);
      if (inside && mine(document.elementFromPoint(at.x, at.y)) && mine(document.elementFromPoint(end.x, end.y))) return at;
    }
    return null;
  });
}

/** 카메라가 멈출 때까지(휠 멈춤 자리 · 끌기) 기다린다 — 가운데 칸 · 확대가 300ms 동안 그대로면 멈춘 것이다. */
async function settled(map: Locator) {
  const read = async () => `${await map.getAttribute('data-map-center')}@${await map.getAttribute('data-map-zoom')}`;
  let last = await read();
  await expect.poll(async () => {
    const now = await read();
    const same = now === last;
    last = now;
    return same;
  }, { timeout: 15_000, intervals: [300] }).toBe(true);
}

/** 놓기 전에 100ms 넘게 멈춰 관성을 0으로 두는 끌기. */
async function drag(page: Page, from: { x: number; y: number }, dx: number, dy: number) {
  await page.mouse.move(from.x, from.y);
  await page.mouse.down();
  await page.mouse.move(from.x + dx, from.y + dy, { steps: 12 });
  await page.waitForTimeout(150);
  await page.mouse.up();
}

/** 새 지도: 그려짐(ready · 옛 캔버스 0 · 옛 지형 안 받음 · 드러난 자리)과 조작됨(휠 · 끌기)을 본다. 드러난 점을 돌려준다. */
async function expectNewMapWorks(page: Page, asked: string[]) {
  const map = page.locator('[data-map-renderer="topdown"]');
  await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
  await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
  await expect(page.getByRole('alert').filter({ hasText: /칠하지 못했습니다|불러오지 못했습니다/ })).toHaveCount(0);
  expect(oldMapPaths(asked), '옛 지도판 자료(지형 JSON · 省 그림)를 받았다').toEqual([]);
  await map.scrollIntoViewIfNeeded();
  const at = await exposedPoint(map);
  expect(at, '새 지도가 드러난 자리가 없다(패널 · 투명 상자가 덮었다)').not.toBeNull();
  // 휠은 한 칸마다 멈춤 자리 하나씩 올라간다
  const zoom = Number(await map.getAttribute('data-map-zoom'));
  await page.mouse.move(at!.x, at!.y);
  await page.mouse.wheel(0, -400);
  await settled(map);
  expect(Number(await map.getAttribute('data-map-zoom'))).toBeGreaterThan(zoom);
  // 끌면 가운데 칸이 옮겨 간다
  const centre = await map.getAttribute('data-map-center');
  await drag(page, at!, 60, 40);
  await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centre);
  return { map, at: at! };
}

// ---------------------------------------------------------------- 스위치 켬(이 단계의 빌드)

test.describe('지도 미리보기 새 지도 — 교체 스위치 빌드', () => {
  test('로그인 배경: 천하 보기로 그려지고, 城 자리 휠 · 누르기 · 「이름」 단추 · 끌기가 된다', { tag: [BOTH] }, async ({ page }) => {
    const asked = await serve(page, { bake: true });
    await openScreen(page, 'login');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(map).toHaveAttribute('data-map-level', 'ju');
    await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
    expect(oldMapPaths(asked)).toEqual([]);

    // 합성 城 자리(P0)는 패널에 가리지 않는다 — 거기서 한 칸씩 굴리면 멈출 때마다 한 멈춤 자리씩 올라간다.
    // 확대하며 화면 끝에 맞추느라 城이 패널(「이름」 단추 등) 밑으로 밀리면 P0 로 다시 끌어 온다.
    let city = await screenOf(map, CITY);
    const p0 = { x: city.x, y: city.y };
    expect(await mapOnTop(page, p0), `城 자리(${Math.round(p0.x)},${Math.round(p0.y)})가 가려졌다`).toBe('CANVAS:true');
    for (let step = 0; step < 16 && city.zoom < 6; step += 1) {
      await page.mouse.move(p0.x, p0.y);
      await page.mouse.wheel(0, -400);
      await settled(map);
      const zoom = Number(await map.getAttribute('data-map-zoom'));
      expect(zoom, `${step + 1}번째 칸이 확대되지 않았다(${city.zoom} → ${zoom})`).toBeGreaterThan(city.zoom);
      for (let tries = 0; tries < 4; tries += 1) {
        city = await screenOf(map, CITY);
        if (Math.hypot(p0.x - city.x, p0.y - city.y) < 4) break;
        await drag(page, p0, p0.x - city.x, p0.y - city.y);
        await settled(map);
      }
    }
    expect(city.zoom, '휠로 郡 보기까지 확대되지 않았다').toBeGreaterThanOrEqual(6);

    // 城을 누르면 옛 지도판과 같은 이름표(이름 · 세력)
    expect(await mapOnTop(page, city)).toBe('CANVAS:true');
    await page.mouse.click(city.x, city.y);
    const tip = page.locator('.map-preview-tooltip');
    await expect(tip).toContainText('선무');
    await expect(tip).toContainText('위');

    // 「이름」 단추는 44 · 가리지 않았고, 누르면 꺼진다
    const names = page.getByRole('button', { name: '지도 이름 보이기' });
    await expect(names).toHaveAttribute('aria-pressed', 'true');
    const box = (await names.boundingBox())!;
    expect(Math.min(box.width, box.height)).toBeGreaterThanOrEqual(44);
    expect(await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.getAttribute('aria-label') ?? null,
      { x: box.x + box.width / 2, y: box.y + box.height / 2 })).toBe('지도 이름 보이기');
    // 지도 조작 묶음(+ · − · 이름). 모바일은 보드 V31K5MLogin 자리(right 8 · top 64, 단추 위 여백 2).
    // 데스크톱은 로그인 카드 바로 아래 오른쪽(D41 — 보드 left 32 · top 420 은 1280×720 에서 판 사이 칸이 없다). 옛 568 · 184 가 아니다.
    const width = page.viewportSize()!.width;
    const zoomIn = (await page.getByRole('button', { name: '확대' }).boundingBox())!;
    if (width < 1200) {
      expect(Math.round(width - (zoomIn.x + zoomIn.width)), '조작 묶음 오른쪽 여백').toBe(8);
      expect(zoomIn.y, '조작 묶음 위치(머리줄 56 바로 아래)').toBeGreaterThanOrEqual(64);
      expect(zoomIn.y, '조작 묶음 위치(머리줄 56 바로 아래)').toBeLessThanOrEqual(68);
    } else {
      const card = (await page.locator('#login-form').boundingBox())!;
      expect(zoomIn.y, '조작 묶음은 로그인 카드 아래').toBeGreaterThanOrEqual(card.y + card.height);
      expect(Math.abs((zoomIn.x + zoomIn.width) - (card.x + card.width)), '조작 묶음은 카드 오른쪽 끝에 맞춘다').toBeLessThanOrEqual(1);
    }
    for (const label of ['확대', '축소']) {
      const b = (await page.getByRole('button', { name: label }).boundingBox())!;
      expect(Math.min(b.width, b.height), `「${label}」 크기`).toBeGreaterThanOrEqual(44);
      expect(await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.getAttribute('aria-label') ?? null,
        { x: b.x + b.width / 2, y: b.y + b.height / 2 }), `「${label}」이 가려졌다`).toBe(label);
    }
    await names.click();
    await expect(names).toHaveAttribute('aria-pressed', 'false');

    // 끌면 가운데 칸이 옮겨 간다
    const centre = await map.getAttribute('data-map-center');
    await drag(page, city, 60, 40);
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centre);
  });

  test('가입 배경: 새 지도가 그려지고 드러난 자리에서 휠 · 끌기가 된다', { tag: [BOTH] }, async ({ page }) => {
    const asked = await serve(page, { bake: true });
    await openScreen(page, 'join');
    await expectNewMapWorks(page, asked);
    // 가입에는 지도 조작이 없다(보드 V31K5Join · MJoin, K0 10-03).
    await expect(page.getByRole('group', { name: '지도 조작' })).toHaveCount(0);
    await expect(page.getByRole('button', { name: '지도 이름 보이기' })).toHaveCount(0);
    await expectNoHorizontalOverflow(page);
  });

  test('로비 카드 펼친 지도: 새 지도가 그려지고 휠 · 끌기가 된다, 캡션은 서버 날짜', { tag: [BOTH] }, async ({ page, baseURL }, testInfo) => {
    const asked = await serve(page, { bake: true, lobby: true, baseURL });
    await openScreen(page, 'lobby');
    await expectNewMapWorks(page, asked);
    if (!isMobile(testInfo)) {
      // 펼친 지도 칸은 지도 비율(셀 3072 × 2676) — 천하가 한 칸에 든다(사용자 D87: 1032×358 채움은 14주 중 7주가 잘렸다).
      const box = (await page.locator('.gw31-card__map .map-preview-canvas').boundingBox())!;
      expect(Math.abs(box.width / box.height - 3072 / 2676), `펼친 지도 칸 비율 ${Math.round(box.width)}×${Math.round(box.height)}`).toBeLessThan(0.02);
    }
    await expect(page.locator('.map-preview-cap')).toContainText('200년 3월 중순');
    // 로비 상자의 「이름」 단추도 44(배경에만 걸어 35×20 이었다, K10 실지도 10-03).
    const names = (await page.getByRole('button', { name: '지도 이름 보이기' }).boundingBox())!;
    expect(Math.min(names.width, names.height), '로비 「이름」 단추 크기').toBeGreaterThanOrEqual(44);
    await expectNoHorizontalOverflow(page);
  });

  test('WebGL2가 없으면 천하 그림 한 장과 안내, 안내는 패널에 가리지 않는다', { tag: [BOTH] }, async ({ page }) => {
    await page.addInitScript(() => {
      const original = HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
        return type === 'webgl2' ? null : (original as (...a: unknown[]) => RenderingContext | null).call(this, type, ...rest);
      } as typeof HTMLCanvasElement.prototype.getContext;
    });
    const asked = await serve(page, { bake: true });
    await openScreen(page, 'login');
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'unsupported', { timeout: 60_000 });
    await expect(page.getByRole('img', { name: '천하 지도(그림만)' })).toBeVisible();
    const notice = page.getByRole('status').filter({ hasText: '이 브라우저에서는 지도를 그릴 수 없습니다. 천하 그림만 보입니다.' });
    await expect(notice).toHaveCount(1);
    // 이름표 자리는 pointer-events: none 이라 elementFromPoint 가 뚫고 지나간다 — 재는 동안만 켠다. 가운데와 네 귀퉁이.
    const covered = await notice.evaluate((node) => {
      const element = node as HTMLElement;
      const before = element.style.pointerEvents;
      element.style.pointerEvents = 'auto';
      const rect = element.getBoundingClientRect();
      const hidden = [[0.5, 0.5], [0.05, 0.1], [0.95, 0.1], [0.05, 0.9], [0.95, 0.9]].filter(([fx, fy]) => {
        const top = document.elementFromPoint(rect.x + rect.width * fx, rect.y + rect.height * fy);
        return !top || !(top === element || element.contains(top));
      }).map(([fx, fy]) => `${fx},${fy}`);
      element.style.pointerEvents = before;
      return hidden;
    });
    expect(covered, '그릴 수 없음 안내가 다른 패널에 가렸다(가린 점)').toEqual([]);
    expect(oldMapPaths(asked)).toEqual([]);
    await expectNoHorizontalOverflow(page);
  });

  test('bakeId가 없으면 이 빌드에서도 옛 지도판 경로(모르는 지도 판이라 안내로 끝남)', { tag: [BOTH] }, async ({ page }) => {
    const asked = await serve(page, { bake: false });
    await openScreen(page, 'login');
    await expect(page.getByText('지원하지 않는 지도 판 — smoke-unsupported')).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
    expect(newMapPaths(asked)).toEqual([]);
  });
});

// ---------------------------------------------------------------- bakeId 없음 → 옛 지도(운영 bake 활성 전)

test.describe('지도 미리보기 — 스위치가 켜져도 bakeId가 없으면 지금 그대로', () => {
  for (const screen of ['login', 'join', 'lobby'] as const) {
    test(`${screen}: bakeId가 없으면 옛 지도판이 그려지고 휠 · 끌기가 되며, 새 지도 자료는 받지 않는다`, { tag: [BOTH] }, async ({ page, baseURL }) => {
      const asked = await serve(page, { bake: false, oldMap: true, lobby: screen === 'lobby', baseURL });
      await openScreen(page, screen);
      const canvas = page.locator('.os-iso-map__canvas').first();
      await expect(canvas).toBeVisible({ timeout: 60_000 });
      await expect(canvas).toHaveAttribute('data-view-center', /.+/);
      await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
      await canvas.scrollIntoViewIfNeeded();
      const at = await exposedPoint(canvas);
      expect(at, '옛 지도가 드러난 자리가 없다').not.toBeNull();
      const before = await canvas.screenshot();
      await page.mouse.move(at!.x, at!.y);
      await page.mouse.wheel(0, -400);
      await expect.poll(async () => Buffer.compare(await canvas.screenshot(), before) !== 0, { timeout: 10_000 }).toBe(true);
      const centre = await canvas.getAttribute('data-view-center');
      await drag(page, at!, 60, 40);
      await expect.poll(async () => canvas.getAttribute('data-view-center'), { timeout: 10_000 }).not.toBe(centre);
      expect(newMapPaths(asked), 'bakeId가 없는데 새 지도 자료를 받았다').toEqual([]);
    });
  }
});
