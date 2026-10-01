// 로그인 배경 지도 새 지도(탑다운) — 제품 화면 교체 스위치(NEXT_PUBLIC_TOPDOWN_SCREENS=1) 빌드에서만 돈다(*.topdown-screen.spec.ts).
// 서버 미리보기가 topdownBakeId를 주면 새 지도, 안 주면 옛 지도판 그대로다. 합성 bake · 키트(web/game/e2e/fixtures/topdown,
// 원작 그림 없음)를 게이트웨이 게임 프록시 bake 주소(/api/game/api/map/topdown/<id>/…?server=pep)와 승인 키트 주소에 대 준다.
// 「그려졌다」(상태 · 옛 지형 안 받음)와 「조작된다」(지도가 드러난 곳에서 휠 · 끌기 · 누르기 · 이름 단추)를 따로 본다.
// 서버 목록은 smoke 단계와 같은 SERVER_REGISTRY_JSON(pep · uni)으로 띄운다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';
import { BOTH, expectNoHorizontalOverflow } from '../../../game/e2e/support/parity';

const FIXTURE = join(__dirname, '..', '..', '..', 'game', 'e2e', 'fixtures', 'topdown');
const BAKE_ID = 'a'.repeat(64);
/** 합성 bake의 城 1(선무) 발자국 가운데 칸. */
const CITY = { col: 1400.5, row: 900.5 };

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

async function serve(page: Page, withBake: boolean): Promise<string[]> {
  const asked: string[] = [];
  page.on('request', (request) => asked.push(new URL(request.url()).pathname));
  const json = (body: unknown, status = 200) => ({ status, contentType: 'application/json', body: JSON.stringify(body) });
  await page.route('**/api/server-map/**', (route) => route.fulfill(json(preview(withBake))));
  await page.route('**/api/server-events/**', (route) => route.fulfill(json({ events: [], nextCursor: null })));
  await page.route('**/api/server-imperial/**', (route) => route.fulfill(json({ status: 'NOT_SEEDED', badges: [] })));
  await page.route('**/api/notices', (route) => route.fulfill(json({ notices: [] })));
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
  test('서버가 bakeId를 주면 새 지도: 천하 보기로 그려지고 휠 · 누르기 · 끌기 · 이름 단추가 된다', { tag: [BOTH] }, async ({ page }) => {
    const asked = await serve(page, true);
    await open(page);
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
    await expect(map).toHaveAttribute('data-map-level', 'ju');
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
    await page.mouse.move(city.x, city.y);
    await page.mouse.down();
    await page.mouse.move(city.x + 60, city.y + 40, { steps: 8 });
    await page.mouse.up();
    await expect.poll(async () => map.getAttribute('data-map-center'), { timeout: 10_000 }).not.toBe(centreBefore);
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

  test('bakeId가 없으면 옛 지도판 그대로', { tag: [BOTH] }, async ({ page }) => {
    await serve(page, false);
    await open(page);
    // 합성 미리보기는 일부러 모르는 지도 판 — 옛 지도판 경로의 안내로 끝난다(login.spec 과 같은 자리)
    await expect(page.getByText('지원하지 않는 지도 판 — smoke-unsupported')).toBeVisible({ timeout: 60_000 });
    await expect(page.locator('[data-map-renderer="topdown"]')).toHaveCount(0);
  });
});
