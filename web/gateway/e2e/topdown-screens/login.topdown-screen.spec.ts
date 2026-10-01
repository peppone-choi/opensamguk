// 로그인 배경 지도 새 지도(탑다운) — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 서버 미리보기가 topdownBakeId를 주면 새 지도, 안 주면 옛 지도판 그대로다. 합성 bake · 키트(web/game/e2e/fixtures/topdown,
// 원작 그림 없음)를 게이트웨이 게임 프록시 bake 주소(/api/game/api/map/topdown/<id>/…?server=pep)와 승인 키트 주소에 대 준다.
// 「그려졌다」(상태 · 옛 지형 안 받음)와 「조작된다」(지도가 드러난 곳에서 휠 · 끌기 · 누르기 · 이름 단추)를 따로 본다.
// 서버 목록은 smoke 단계와 같은 SERVER_REGISTRY_JSON(pep · uni)으로 띄운다.
import { readFileSync, writeFileSync, readdirSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { deflateSync, gunzipSync } from 'node:zlib';
import { join } from 'node:path';
import { expect, test, type Locator, type Page, type Request, type Route, type TestInfo } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow } from '../../../game/e2e/support/parity';

const FIXTURE = join(__dirname, '..', '..', '..', 'game', 'e2e', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
/** 합성 bake의 城 1(선무) 발자국 가운데 칸. */
const CITY = { col: 1400.5, row: 900.5 };
const sha = (bytes: string | Buffer) => createHash('sha256').update(bytes).digest('hex');

// Synthetic old-map fixture: normal terrain, a verified Ju index and an opaque identity PNG.
// This is renderer evidence, not production geography or a transfer-budget baseline.
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
    // Identity = ((parentIndex + 1) << 12) | (provinceIndex + 1).
    rows[at + 1] = 16; rows[at + 2] = 1;
  }
  return Buffer.concat([Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]), chunk('IHDR', header),
    chunk('IDAT', deflateSync(rows)), chunk('IEND', Buffer.alloc(0))]);
}
const OLD_PNG = oldProvincePng();
const fixturePins = () => {
  const files = (dir: string): { path: string; sha256: string; encodedBytes: number }[] => readdirSync(join(FIXTURE, dir), { withFileTypes: true })
    .sort((a, b) => a.name.localeCompare(b.name)).flatMap((entry) => entry.isDirectory() ? files(`${dir}/${entry.name}`)
      : [{ path: `${dir}/${entry.name}`, sha256: sha(readFileSync(join(FIXTURE, dir, entry.name))), encodedBytes: readFileSync(join(FIXTURE, dir, entry.name)).length }]);
  const pins = { topdown: files('bake').concat(files('kit')), oldTerrain: sha(OLD_BODY), oldProvinces: sha(OLD_PNG), preview: sha(JSON.stringify(preview(true))) };
  return { ...pins, fixtureSha256: sha(JSON.stringify(pins)) };
};

type RequestEvidence = { url: string; resourceType: string; state: 'pending' | 'completed' | 'failed' | 'cancelled'; status?: number;
  contentEncoding?: string; fromServiceWorker?: boolean;
  failure?: string; sizes?: Awaited<ReturnType<Request['sizes']>>; sizesError?: string;
  fixture?: { sha256: string; logicalPayloadBytes: number; encodedAssetBytes: number | null; kind: string } };
type Evidence = { requests: Map<Request, RequestEvidence>; pendingReads: Promise<void>[]; captures: unknown[] };
const evidence = new WeakMap<Page, Evidence>();

function watch(page: Page): Evidence {
  const state: Evidence = { requests: new Map(), pendingReads: [], captures: [] };
  evidence.set(page, state);
  page.on('request', (request) => state.requests.set(request, { url: request.url(), resourceType: request.resourceType(), state: 'pending' }));
  page.on('requestfinished', (request) => {
    const entry = state.requests.get(request)!;
    entry.state = 'completed';
    state.pendingReads.push((async () => {
      const response = await request.response();
      entry.status = response?.status();
      entry.fromServiceWorker = response?.fromServiceWorker();
      entry.contentEncoding = (await response?.allHeaders())?.['content-encoding'];
      try { entry.sizes = await request.sizes(); } catch (error) { entry.sizesError = String(error); }
    })());
  });
  page.on('requestfailed', (request) => {
    const entry = state.requests.get(request)!;
    entry.failure = request.failure()?.errorText ?? 'unknown';
    entry.state = /abort|cancel/i.test(entry.failure) ? 'cancelled' : 'failed';
  });
  return state;
}

async function capture(page: Page, canvas: Locator, name: string, info: TestInfo) {
  const path = info.outputPath(`${name}.png`);
  const png = await canvas.screenshot({ path, style: 'body * { visibility: hidden; } canvas { visibility: visible !important; }' });
  // Decode the screenshot, so PNG metadata/compression changes cannot masquerade as pixel changes.
  const pixels = await page.evaluate(async (encoded) => {
    const image = await createImageBitmap(await (await fetch(`data:image/png;base64,${encoded}`)).blob());
    const copy = document.createElement('canvas'); copy.width = image.width; copy.height = image.height;
    const ctx = copy.getContext('2d')!; ctx.drawImage(image, 0, 0); image.close();
    const data = ctx.getImageData(0, 0, copy.width, copy.height).data;
    const colors = new Set<number>();
    for (let i = 0; i < data.length; i += 4) colors.add((data[i] << 24) | (data[i + 1] << 16) | (data[i + 2] << 8) | data[i + 3]);
    const digest = await crypto.subtle.digest('SHA-256', data);
    return { width: copy.width, height: copy.height, colors: colors.size,
      sha256: Array.from(new Uint8Array(digest), (byte) => byte.toString(16).padStart(2, '0')).join('') };
  }, png.toString('base64'));
  evidence.get(page)!.captures.push({ name, pngSha256: sha(png), pixels,
    camera: await canvas.evaluate((node) => ({ ...((node as HTMLElement).dataset),
      ...((node.closest('[data-map-renderer]') as HTMLElement | null)?.dataset ?? {}) })) });
  await info.attach(name, { path, contentType: 'image/png' });
  await page.screenshot({ path: info.outputPath(`${name}-page.png`) });
  expect(pixels.colors, 'canvas must contain a drawn map, not a blank surface').toBeGreaterThan(2);
  return pixels.sha256;
}

async function saveEvidence(page: Page, info: TestInfo, mode: string) {
  const state = evidence.get(page)!;
  await Promise.all(state.pendingReads);
  const path = info.outputPath(`${mode}-evidence.json`);
  const git = (args: string[]) => execFileSync('git', args, { encoding: 'utf8' }).trim();
  const build = process.env.K2_CI_EVIDENCE ? JSON.parse(readFileSync(process.env.K2_CI_EVIDENCE, 'utf8')) : { unavailable: 'not a normal CI switch execution' };
  writeFileSync(path, JSON.stringify({ mode, comparison: 'current checkout only; pinned base OFF artifact unavailable',
    checkoutSha: git(['rev-parse', 'HEAD']), checkoutTree: git(['rev-parse', 'HEAD^{tree}']),
    sourceSha256: sha(readFileSync(__filename)), build, fixtures: fixturePins(),
    project: info.project.name, retry: info.retry, statusAtCapture: info.status, expectedStatus: info.expectedStatus,
    finalUrl: page.url(), viewport: page.viewportSize(), dpr: await page.evaluate(() => devicePixelRatio),
    cache: 'fresh browser context; routing disables HTTP cache; service workers blocked for OFF context',
    collection: 'page navigation through final canvas capture; all observed page requests, including pending/failed/cancelled; APIRequestContext build-manifest probes excluded; Playwright responseBodySize is browser-reported encoded bytes, not fixture payload; mocked transport is not production wire traffic',
    requests: [...state.requests.values()], captures: state.captures,
  }, null, 2));
  await info.attach(`${mode}-evidence`, { path, contentType: 'application/json' });
}

/** 합성 bake의 城 1과 같은 id · 이름, 구역 2개(합성 bake provinceCount). */
function preview(withBake: boolean) {
  return {
    serverName: 'pep', year: 200, month: 3, turnPhaseText: '중순',
    mapCode: withBake ? 'han-world-v3' : 'smoke-unsupported', width: 700, height: 610,
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

const contentType = (path: string) => (path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream');
const fixtureFile = (dir: string, file: string) => {
  try {
    return { status: 200, body: readFileSync(join(FIXTURE, dir, file)), contentType: contentType(file) };
  } catch {
    return { status: 404, body: '' };
  }
};

async function serve(page: Page, withBake: boolean, oldMap = false): Promise<string[]> {
  const asked: string[] = [];
  page.on('request', (request) => asked.push(new URL(request.url()).pathname));
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  const fulfill = (route: Route, response: { status: number; body: string | Buffer; contentType?: string; headers?: Record<string, string> }) => {
    const body = Buffer.isBuffer(response.body) ? response.body : Buffer.from(response.body);
    const asset = /\.(png|gz)$|\/provinces\?/.test(route.request().url());
    const compressed = route.request().url().split('?')[0].endsWith('.gz') && response.status === 200;
    evidence.get(page)!.requests.get(route.request())!.fixture = { sha256: sha(body),
      logicalPayloadBytes: compressed ? gunzipSync(body).length : body.length,
      encodedAssetBytes: asset ? body.length : null, kind: asset ? 'synthetic encoded asset' : 'synthetic logical JSON payload' };
    return route.fulfill(response);
  };
  const mapPreview = oldMap ? { ...preview(true), cities: [{ ...preview(true).cities[0], x: 350, y: 305, provinceId: 0 }] } : preview(withBake);
  await page.route('**/api/server-map/**', (route) => fulfill(route, json(mapPreview)));
  await page.route('**/api/server-events/**', (route) => fulfill(route, json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => fulfill(route, json({ status: 'NOT_SEEDED', badges: [] })));
  await page.route('**/api/notices', (route) => fulfill(route, json({ notices: [] })));
  if (oldMap) {
    await page.route('**/api/game/api/map/terrain?*', (route) => fulfill(route, {
      ...json(OLD_TILES), headers: { etag: `"sha256-${sha(OLD_BODY)}"` },
    }));
    await page.route('**/api/game/api/map/ju?*', (route) => fulfill(route, json({ sourceSha256: sha(OLD_BODY), juByParent: ['사예'] })));
    await page.route('**/api/game/api/map/provinces?*', (route) => fulfill(route, { status: 200, body: OLD_PNG, contentType: 'image/png' }));
  }
  await page.route((url) => url.pathname.startsWith('/map/waryong/273d596/'), (route) =>
    fulfill(route, fixtureFile('kit', new URL(route.request().url()).pathname.replace('/map/waryong/273d596/', ''))));
  await page.route((url) => url.pathname.startsWith(`/api/game/api/map/topdown/${BAKE_ID}/`), (route) => {
    const url = new URL(route.request().url());
    // 게이트웨이 프록시가 서버를 고르는 값 — 모든 bake 파일에 실려야 한다
    if (url.searchParams.get('server') !== 'pep') return route.fulfill({ status: 400, body: 'server 빠짐' });
    return fulfill(route, fixtureFile('bake', url.pathname.slice(`/api/game/api/map/topdown/${BAKE_ID}/`.length)));
  });
  return asked;
}

async function open(page: Page) {
  await page.goto('/login');
  await expect(page.getByRole('button', { name: /^pep/ }), '서버 목록이 비었다 — SERVER_REGISTRY_JSON 으로 게이트웨이를 띄웠는지 확인').toBeVisible();
}

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

/**
 * 城을 화면 점 `to`(지도가 드러난 곳)로 끌어 온다. 확대하며 화면 끝에 맞추느라 城이 패널 밑(「이름」 단추 등)으로 밀릴 수 있다.
 * `to`에서 끌기 시작하니 손가락이 패널 위로 지나가도 지도가 붙잡는다. 놓기 전에 100ms 넘게 멈춰 관성을 0으로 둔다.
 */
async function bringCity(page: Page, map: Locator, to: { x: number; y: number }) {
  for (let tries = 0; tries < 4; tries += 1) {
    const at = await screenOf(map, CITY);
    const dx = to.x - at.x;
    const dy = to.y - at.y;
    if (Math.hypot(dx, dy) < 4) return at;
    await page.mouse.move(to.x, to.y);
    await page.mouse.down();
    await page.mouse.move(to.x + dx, to.y + dy, { steps: 12 });
    await page.waitForTimeout(150);
    await page.mouse.up();
    await settled(map);
  }
  return screenOf(map, CITY);
}

test.describe('로그인 배경 지도 새 지도(교체 스위치 빌드)', () => {
  test.beforeEach(async ({ page }) => { watch(page); });
  test.afterEach(async ({ page }, info) => {
    await saveEvidence(page, info, page.url() === 'about:blank' ? 'unused-ON-context' : 'ON');
    const path = info.outputPath('test-outcome.json');
    writeFileSync(path, JSON.stringify({ status: info.status, expectedStatus: info.expectedStatus, retry: info.retry,
      errors: info.errors.map((error) => error.message), project: info.project.name }, null, 2));
    await info.attach('test-outcome', { path, contentType: 'application/json' });
  });

  test('서버가 bakeId를 주면 새 지도: 천하 보기로 그려지고 휠 · 누르기 · 끌기 · 이름 단추가 된다', { tag: [BOTH] }, async ({ page }, info) => {
    const pins = JSON.parse(readFileSync(process.env.K2_CI_EVIDENCE!, 'utf8'));
    expect(pins.on.buildFlag).toBe('1');
    expect((await page.request.get(`/_next/static/${pins.on.buildId}/_buildManifest.js`)).status(), 'ON server must serve the pinned switch build').toBe(200);
    const asked = await serve(page, true);
    await open(page);
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(map).toHaveAttribute('data-map-level', 'ju');
    const canvas = map.locator('canvas').first();
    const initialPixels = await capture(page, canvas, 'on-initial', info);
    await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
    await expect(page.getByRole('alert').filter({ hasText: /칠하지 못했습니다|불러오지 못했습니다/ })).toHaveCount(0);
    // 옛 지도판 자료(지형 JSON · 省 그림)는 받지 않는다
    expect(asked.filter((path) => /\/api\/game\/api\/map\/(terrain|provinces)/.test(path))).toEqual([]);

    // 조작됨 ① 합성 城이 그려진 곳(P0)은 패널에 가리지 않고 지도가 맨 위다 — 거기서 한 칸씩 굴리면 멈출 때마다 한 멈춤 자리씩 올라간다
    // (휴대폰 폭 맞춤 0.127 에서 한 칸이 맞춤으로 되돌아가던 결함을 여기서 본다). 한 칸마다 城을 P0 로 다시 끌어 온다.
    let city = await screenOf(map, CITY);
    const p0 = { x: city.x, y: city.y };
    expect(await mapOnTop(page, p0), `城 자리(${Math.round(p0.x)},${Math.round(p0.y)})가 가려졌다`).toBe('CANVAS:true');
    const before = city.zoom;
    for (let step = 0; step < 16 && city.zoom < 6; step += 1) {
      await page.mouse.move(p0.x, p0.y);
      await page.mouse.wheel(0, -400);
      await settled(map);
      const zoom = Number(await map.getAttribute('data-map-zoom'));
      expect(zoom, `${step + 1}번째 칸이 확대되지 않았다(${city.zoom} → ${zoom})`).toBeGreaterThan(city.zoom);
      city = await bringCity(page, map, p0);
    }
    expect(city.zoom).toBeGreaterThan(before);
    expect(city.zoom, '휠로 郡 보기까지 확대되지 않았다').toBeGreaterThanOrEqual(6);
    expect(await capture(page, canvas, 'on-wheel', info), 'wheel must change canvas pixels').not.toBe(initialPixels);

    // 조작됨 ② 城을 누르면 옛 지도판과 같은 이름표(이름 · 세력)
    expect(Math.hypot(city.x - p0.x, city.y - p0.y), '城을 P0 로 끌어 오지 못했다').toBeLessThan(4);
    expect(await mapOnTop(page, city)).toBe('CANVAS:true');
    await page.mouse.click(city.x, city.y);
    const tip = page.locator('.map-preview-tooltip');
    await expect(tip).toContainText('선무');
    await expect(tip).toContainText('위');

    // 조작됨 ③ 「이름」 단추는 가리지 않았고, 누르면 城 이름 층이 꺼진다
    const names = page.getByRole('button', { name: '지도 이름 보이기' });
    await expect(names).toHaveAttribute('aria-pressed', 'true');
    const nameBox = (await names.boundingBox())!;
    expect(nameBox.width).toBeGreaterThanOrEqual(44);
    expect(nameBox.height).toBeGreaterThanOrEqual(44);
    const nameTop = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.getAttribute('aria-label') ?? null,
      { x: nameBox.x + nameBox.width / 2, y: nameBox.y + nameBox.height / 2 });
    expect(nameTop).toBe('지도 이름 보이기');
    await names.click();
    await expect(names).toHaveAttribute('aria-pressed', 'false');

    // 조작됨 ④ 지도가 드러난 곳에서 끌면 가운데 칸이 옮겨 간다
    const centreBefore = await map.getAttribute('data-map-center');
    const dragPixels = await capture(page, canvas, 'on-before-drag', info);
    await page.mouse.move(city.x, city.y);
    await page.mouse.down();
    await page.mouse.move(city.x + 60, city.y + 40, { steps: 8 });
    await page.mouse.up();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centreBefore);
    await settled(map);
    expect(await capture(page, canvas, 'on-drag', info), 'drag must change canvas pixels').not.toBe(dragPixels);
    await page.waitForLoadState('networkidle');
  });

  test('실제 OFF 기본 빌드는 bakeId가 있어도 정상 옛 지도를 그리고 새 지도 자료를 받지 않는다', { tag: [BOTH] }, async ({ browser }, info) => {
    const pins = JSON.parse(readFileSync(process.env.K2_CI_EVIDENCE!, 'utf8'));
    expect(pins.off.buildId, 'normal OFF build must exist').toBeTruthy();
    const use = info.project.use;
    const context = await browser.newContext({ baseURL: 'http://127.0.0.1:3000', viewport: use.viewport,
      deviceScaleFactor: use.deviceScaleFactor, isMobile: use.isMobile, hasTouch: use.hasTouch,
      userAgent: use.userAgent, serviceWorkers: 'block' });
    const page = await context.newPage();
    watch(page);
    try {
      const manifest = await page.request.get(`/_next/static/${pins.off.buildId}/_buildManifest.js`);
      expect(manifest.status(), 'OFF server must serve the pinned normal build').toBe(200);
      const asked = await serve(page, true, true);
      await open(page);
      const canvas = page.locator('.os-iso-map__canvas');
      await expect(canvas).toBeVisible();
      await expect(canvas).toHaveAttribute('aria-label', 'han-world-v3 서버 지도');
      await expect(canvas).toHaveAttribute('data-view-center', /.+/);
      await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
      await expect(page.getByText(/지원하지 않는 지도 판|지형을 받지 못했습니다/)).toHaveCount(0);
      await page.waitForLoadState('networkidle');
      for (const kind of ['terrain', 'ju', 'provinces']) {
        const entries = [...evidence.get(page)!.requests.values()].filter((entry) => new URL(entry.url).pathname === `/api/game/api/map/${kind}`);
        expect(entries, `${kind} fixture must actually load`).toHaveLength(1);
        expect(entries[0]).toMatchObject({ state: 'completed', status: 200 });
      }
      const initial = await capture(page, canvas, 'off-initial', info);
      const input = await canvas.evaluate((node) => {
        const { x, y, width, height } = node.getBoundingClientRect();
        // Desktop corner panels cover the old edge samples. Start at the map centre,
        // and require both ends of the real drag to stay on this canvas in the viewport.
        const candidates = [[0.5, 0.5], [0.1, 0.2], [0.9, 0.2], [0.1, 0.5], [0.9, 0.5], [0.1, 0.8]].map(([fx, fy]) => {
          const at = { x: x + width * fx, y: y + height * fy };
          const end = { x: at.x + 60, y: at.y + 40 };
          const inViewport = [at, end].every((point) => point.x >= 0 && point.x < innerWidth && point.y >= 0 && point.y < innerHeight);
          const startTop = document.elementFromPoint(at.x, at.y);
          const endTop = document.elementFromPoint(end.x, end.y);
          return { at, end, inViewport, exposed: inViewport && startTop === node && endTop === node,
            startTop: startTop?.className ?? null, endTop: endTop?.className ?? null };
        });
        return { canvas: { x, y, width, height }, viewport: { width: innerWidth, height: innerHeight },
          candidates, hit: candidates.find((candidate) => candidate.exposed)?.at ?? null };
      });
      const inputPath = info.outputPath('off-input-surface.json');
      writeFileSync(inputPath, JSON.stringify(input, null, 2));
      await info.attach('off-input-surface', { path: inputPath, contentType: 'application/json' });
      const hit = input.hit;
      expect(hit, `OFF map input must be exposed: ${JSON.stringify(input)}`).not.toBeNull();
      await page.mouse.move(hit!.x, hit!.y);
      await page.mouse.wheel(0, -400);
      await page.waitForTimeout(300);
      const wheel = await capture(page, canvas, 'off-wheel', info);
      expect(wheel, 'OFF wheel must change canvas pixels').not.toBe(initial);
      const center = await canvas.getAttribute('data-view-center');
      await page.mouse.down();
      await page.mouse.move(hit!.x + 60, hit!.y + 40, { steps: 8 });
      await page.mouse.up();
      await expect.poll(async () => canvas.getAttribute('data-view-center')).not.toBe(center);
      expect(await capture(page, canvas, 'off-drag', info), 'OFF drag must change canvas pixels').not.toBe(wheel);
      expect(asked.filter((path) => /\/map\/(topdown|waryong)\//.test(path))).toEqual([]);
    } finally {
      await saveEvidence(page, info, 'OFF');
      await context.close();
    }
  });

  test('WebGL2가 없으면 천하 그림 한 장과 안내, 옛 지형은 받지 않는다', { tag: [BOTH] }, async ({ page }) => {
    await page.addInitScript(() => {
      const original = HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
        return type === 'webgl2' ? null : (original as (...a: unknown[]) => RenderingContext | null).call(this, type, ...rest);
      } as typeof HTMLCanvasElement.prototype.getContext;
    });
    const asked = await serve(page, true);
    await open(page);
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'unsupported', { timeout: 60_000 });
    await expect(page.getByRole('img', { name: '천하 지도(그림만)' })).toBeVisible();
    const notice = page.getByRole('status').filter({ hasText: '이 브라우저에서는 지도를 그릴 수 없습니다. 천하 그림만 보입니다.' });
    await expect(notice).toHaveCount(1);
    // 안내는 패널에 가리지 않는다(지도 아래 끝은 데스크톱 · 모바일 모두 패널 밑이다). 가운데와 네 귀퉁이에서 맨 위 요소가 안내 자신이다.
    // 이름표 자리는 pointer-events: none 이라 elementFromPoint 가 뚫고 지나간다 — 재는 동안만 켠다.
    const covered = await notice.evaluate((node) => {
      const element = node as HTMLElement;
      const before = element.style.pointerEvents;
      element.style.pointerEvents = 'auto';
      const rect = element.getBoundingClientRect();
      const points = [[0.5, 0.5], [0.05, 0.1], [0.95, 0.1], [0.05, 0.9], [0.95, 0.9]];
      const hidden = points.filter(([fx, fy]) => {
        const top = document.elementFromPoint(rect.x + rect.width * fx, rect.y + rect.height * fy);
        return !top || !(top === element || element.contains(top));
      }).map(([fx, fy]) => `${fx},${fy}`);
      element.style.pointerEvents = before;
      return hidden;
    });
    expect(covered, '그릴 수 없음 안내가 다른 패널에 가렸다(가린 점)').toEqual([]);
    await expectNoHorizontalOverflow(page);
    await expect(page.locator('.os-iso-map__canvas')).toHaveCount(0);
    expect(asked.filter((path) => /\/api\/game\/api\/map\/(terrain|provinces)/.test(path))).toEqual([]);
  });

  test('ON 빌드에서 bakeId가 없는 unsupported fixture는 별도 fallback 경로다(OFF 증거 아님)', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, false);
    await open(page);
    // 합성 미리보기는 일부러 모르는 지도 판 — 옛 지도판 경로의 안내로 끝난다(login.spec 과 같은 자리)
    await expect(page.getByText('지원하지 않는 지도 판 — smoke-unsupported')).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
  });
});
