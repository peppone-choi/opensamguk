// 탑다운 지도 엔진(기능 플래그 뒤 /map-lab) — 합성 키트 · 굽기(e2e/fixtures/topdown, 원작 그림 없음)를
// page.route로 대 준다. 백엔드 없이 돈다(e2e/smoke 규칙: @both = 데스크톱 · 모바일 두 프로필, @mobile-only).
// 「그려졌다」(스크린샷 화소)와 「조작된다」(상태 속성 · 누르기 결과)를 따로 본다.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Locator, type Page } from '@playwright/test';

const FIXTURE = join(__dirname, '..', 'fixtures', 'topdown');
const LAB = '/map-lab?bake=/e2e-topdown/bake&kit=/e2e-topdown/kit&c=1408,896&z=16';
const RED = [200, 40, 40];
const GREEN = [40, 160, 60];

async function serveFixture(page: Page) {
  await page.route((url) => url.pathname.startsWith('/e2e-topdown/'), async (route) => {
    const path = new URL(route.request().url()).pathname.replace(/^\/e2e-topdown\//, '');
    const body = readFileSync(join(FIXTURE, path));
    const type = path.endsWith('.png') ? 'image/png' : path.endsWith('.json') ? 'application/json' : 'application/octet-stream';
    await route.fulfill({ status: 200, body, contentType: type });
  });
}

/** Colour at a point of the map box, read from a real screenshot (the WebGL buffer is not preserved). */
async function colourAt(page: Page, map: Locator, x: number, y: number): Promise<number[]> {
  const shot = await map.screenshot();
  const box = (await map.boundingBox())!;
  return page.evaluate(async ({ png, x, y, width }) => {
    const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
    const scale = bitmap.width / width;
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(bitmap, 0, 0);
    return Array.from(ctx.getImageData(Math.round(x * scale), Math.round(y * scale), 1, 1).data.slice(0, 3));
  }, { png: shot.toString('base64'), x, y, width: box.width });
}

function near(actual: number[], expected: number[], tolerance = 12) {
  expect(actual.map((v, i) => Math.abs(v - expected[i])).every((d) => d <= tolerance), `${actual} ≈ ${expected}`).toBe(true);
}

async function openLab(page: Page) {
  await serveFixture(page);
  await page.goto(LAB);
  const map = page.locator('[data-map-renderer="topdown"]');
  // 개발 서버는 첫 요청에 화면을 컴파일한다(20초 넘게 걸릴 수 있다)
  await expect(map).toHaveAttribute('data-map-status', 'ready', { timeout: 60_000 });
  await page.waitForTimeout(300);
  return map;
}

const zoomOf = async (map: Locator) => Number(await map.getAttribute('data-map-zoom'));

test.describe('탑다운 지도 시험 화면', () => {
  test('그려진다: 타일 색 · 가운데가 지도 캔버스', { tag: '@both' }, async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    const cx = box.width / 2;
    const cy = box.height / 2;
    near(await colourAt(page, map, cx - 24, cy), RED); // 칸 1406.5 → 조각 (5,3) 왼쪽 반
    near(await colourAt(page, map, cx + 24, cy), GREEN); // 칸 1409.5 → 오른쪽 반
    const top = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.tagName, { x: box.x + cx, y: box.y + cy });
    expect(top).toBe('CANVAS');
  });

  test('조작된다: 휠 · 끌기 · 누르기 · 키보드', { tag: '@both' }, async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.wheel(0, 200);
    await expect(map).not.toHaveAttribute('data-map-zoom', '16.000');
    await expect.poll(() => zoomOf(map)).toBe(8); // 멈추면 가까운 멈춤 자리
    const before = await map.getAttribute('data-map-center');
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx - 60, cy - 40, { steps: 6 });
    await page.mouse.up();
    await expect(map).not.toHaveAttribute('data-map-center', before!);
    await page.mouse.click(cx + 20, cy + 20);
    await expect(page.getByTestId('map-lab-hit')).toContainText('province');
    await map.focus();
    const zoomBefore = await zoomOf(map);
    await page.keyboard.press('+');
    await expect.poll(() => zoomOf(map)).toBeGreaterThan(zoomBefore);
  });

  test('보기 수준 단추: 주 · 군 · 현', { tag: '@both' }, async ({ page }) => {
    const map = await openLab(page);
    await page.getByRole('button', { name: '주', exact: true }).click();
    await expect(map).toHaveAttribute('data-map-level', 'ju');
    await page.getByRole('button', { name: '군', exact: true }).click();
    await expect(map).toHaveAttribute('data-map-level', 'commandery');
    await expect(map).toHaveAttribute('data-map-zoom', '4.000');
    await page.getByRole('button', { name: '현', exact: true }).click();
    await expect(map).toHaveAttribute('data-map-zoom', '16.000');
  });

  test('내 위치로 · 내 위치 표지 누르기 · 작은 지도', { tag: '@both' }, async ({ page }) => {
    const map = await openLab(page);
    await page.getByRole('button', { name: '내 위치로' }).click();
    await expect(map).toHaveAttribute('data-map-center', '1505.0,933.0');
    const box = (await map.boundingBox())!;
    // 핀 머리는 칸 가운데(화면 가운데 + 반 칸) 위 약 31px
    await page.mouse.click(box.x + box.width / 2 + 8, box.y + box.height / 2 + 8 - 31);
    await expect(page.getByTestId('map-lab-hit')).toContainText('me');
    const minimap = page.getByRole('button', { name: /작은 지도/ });
    await expect(minimap).toBeVisible();
    const mini = (await minimap.boundingBox())!;
    await minimap.click({ position: { x: 4, y: 4 } }); // 지도 왼쪽 위 끝으로
    await expect.poll(async () => Number((await map.getAttribute('data-map-center'))!.split(',')[0])).toBeLessThan(400);
    expect(mini.width).toBeGreaterThanOrEqual(44);
  });

  test('WebGL2가 없으면 안내문과 천하 그림 한 장, 작은 지도는 없다', { tag: '@both' }, async ({ page }) => {
    await page.addInitScript(() => {
      const original = HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext = function (this: HTMLCanvasElement, type: string, ...rest: unknown[]) {
        return type === 'webgl2' ? null : (original as (...a: unknown[]) => RenderingContext | null).call(this, type, ...rest);
      } as typeof HTMLCanvasElement.prototype.getContext;
    });
    await serveFixture(page);
    await page.goto(LAB);
    const map = page.locator('[data-map-renderer="topdown"]');
    await expect(map).toHaveAttribute('data-map-status', 'unsupported', { timeout: 60_000 });
    await expect(page.getByRole('status').filter({ hasText: '이 브라우저에서는 지도를 그릴 수 없습니다' })).toBeVisible();
    const picture = page.getByRole('img', { name: '천하 지도(그림만)' });
    await expect(picture).toBeVisible();
    // 개관 격자(합성 굽기)를 1px 밉 색으로 칠한 그림: 합성 조각의 빨강 · 초록이 그림 안에 있다
    const shot = await picture.screenshot();
    const counts = await page.evaluate(async ({ png, red, green }) => {
      const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
      const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      const ctx = canvas.getContext('2d')!;
      ctx.drawImage(bitmap, 0, 0);
      const data = ctx.getImageData(0, 0, bitmap.width, bitmap.height).data;
      const close = (i: number, c: number[]) => Math.abs(data[i] - c[0]) + Math.abs(data[i + 1] - c[1]) + Math.abs(data[i + 2] - c[2]) < 36;
      let r = 0;
      let g = 0;
      for (let i = 0; i < data.length; i += 4) {
        if (close(i, red)) r += 1;
        else if (close(i, green)) g += 1;
      }
      return { r, g };
    }, { png: shot.toString('base64'), red: RED, green: GREEN });
    expect(counts.r, '빨강 화소').toBeGreaterThan(0);
    expect(counts.g, '초록 화소').toBeGreaterThan(0);
    await expect(page.getByRole('button', { name: /작은 지도/ })).toHaveCount(0);
  });

  test('모바일: 탭으로 고르고 누를 것은 44px 이상', { tag: '@mobile-only' }, async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    expect(await map.evaluate((el) => getComputedStyle(el).touchAction)).toBe('none');
    await page.touchscreen.tap(box.x + box.width / 2 + 24, box.y + box.height / 2);
    await expect(page.getByTestId('map-lab-hit')).toContainText('province 1');
    for (const name of ['+', '−', '주', '군', '현', '내 위치로']) {
      const size = (await page.getByRole('button', { name, exact: true }).boundingBox())!;
      expect(Math.min(size.width, size.height), name).toBeGreaterThanOrEqual(44);
    }
  });
});
