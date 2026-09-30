// 탑다운 지도 엔진(기능 플래그 뒤 /map-lab) — 합성 키트 · 굽기(e2e/fixtures/topdown, 원작 그림 없음)로
// 「그려졌다」와 「조작된다」를 따로 본다. 데스크톱과 모바일(390×844, 터치)에서 같은 흐름.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { expect, test, type Page } from '@playwright/test';

const FIXTURE = join(__dirname, 'fixtures', 'topdown');
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
async function colourAt(page: Page, x: number, y: number): Promise<number[]> {
  const map = page.locator('[data-map-renderer="topdown"]');
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
  await expect(map).toHaveAttribute('data-map-status', 'ready');
  await page.waitForTimeout(300);
  return map;
}

test.describe('탑다운 지도 시험 화면 — 데스크톱', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('그려진다: 타일 색 · 캔버스가 맨 위', async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    const cx = box.width / 2;
    const cy = box.height / 2;
    near(await colourAt(page, cx - 40, cy), RED); // 칸 1405.5 → 조각 (5,3) 왼쪽 반
    near(await colourAt(page, cx + 40, cy), GREEN); // 칸 1410.5 → 오른쪽 반
    const top = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.tagName, { x: box.x + cx, y: box.y + cy });
    expect(top).toBe('CANVAS');
  });

  test('조작된다: 휠 · 끌기 · 누르기 · 키보드', async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    const cx = box.x + box.width / 2;
    const cy = box.y + box.height / 2;
    await page.mouse.move(cx, cy);
    await page.mouse.wheel(0, 200);
    await expect(map).not.toHaveAttribute('data-map-zoom', '16.000');
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom'))).toBe(8); // 멈추면 가까운 멈춤 자리
    const before = await map.getAttribute('data-map-center');
    await page.mouse.move(cx, cy);
    await page.mouse.down();
    await page.mouse.move(cx - 120, cy - 60, { steps: 6 });
    await page.mouse.up();
    await expect(map).not.toHaveAttribute('data-map-center', before!);
    await page.mouse.click(cx, cy);
    await expect(page.getByTestId('map-lab-hit')).toContainText('province');
    await map.focus();
    const zoomBefore = Number(await map.getAttribute('data-map-zoom'));
    await page.keyboard.press('+');
    await expect.poll(async () => Number(await map.getAttribute('data-map-zoom'))).toBeGreaterThan(zoomBefore);
  });

  test('보기 수준 단추: 주 · 군 · 현', async ({ page }) => {
    const map = await openLab(page);
    await page.getByRole('button', { name: '주' }).click();
    await expect(map).toHaveAttribute('data-map-level', 'ju');
    await page.getByRole('button', { name: '군' }).click();
    await expect(map).toHaveAttribute('data-map-level', 'commandery');
    await expect(map).toHaveAttribute('data-map-zoom', '4.000');
    await page.getByRole('button', { name: '현' }).click();
    await expect(map).toHaveAttribute('data-map-zoom', '16.000');
  });
});

test.describe('탑다운 지도 시험 화면 — 모바일', () => {
  test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

  test('그려지고 탭으로 고른다', async ({ page }) => {
    const map = await openLab(page);
    const box = (await map.boundingBox())!;
    near(await colourAt(page, box.width / 2 - 24, box.height / 2), RED);
    near(await colourAt(page, box.width / 2 + 24, box.height / 2), GREEN);
    expect(await map.evaluate((el) => getComputedStyle(el).touchAction)).toBe('none');
    await page.touchscreen.tap(box.x + box.width / 2 + 24, box.y + box.height / 2);
    await expect(page.getByTestId('map-lab-hit')).toContainText('province 1');
    const plus = page.getByRole('button', { name: '+' });
    const size = (await plus.boundingBox())!;
    expect(Math.min(size.width, size.height)).toBeGreaterThanOrEqual(44);
  });
});
