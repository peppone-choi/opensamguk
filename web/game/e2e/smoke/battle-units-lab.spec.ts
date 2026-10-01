// 전투 판 유닛(분대 표기 B안, 기능 플래그 뒤 /map-lab/battle). 앱이 이미 싣는 전장 키트 export
// (public/battle/waryong/2c8a1a5)를 그대로 쓴다. 백엔드 없이 돈다(@both = 데스크톱 · 모바일 두 프로필).
// 「그려졌다」(실제 스크린샷 화소)와 「조작된다」(누르기 결과 · 배율 단추)를 따로 본다.
import { expect, test, type Locator, type Page } from '@playwright/test';

interface Rect { x: number; y: number; width: number; height: number }
interface Drawn { id: string; hit: Rect; flag: Rect }
const BLUE = [0x4f, 0x7f, 0xbf];
const BACKGROUND = [0x0c, 0x0f, 0x0e];

async function open(page: Page): Promise<Locator> {
  await page.goto('/map-lab/battle');
  const canvas = page.getByTestId('battle-lab-canvas');
  await expect(canvas).toHaveAttribute('data-units', /heo/, { timeout: 60_000 });
  return canvas;
}

async function drawn(canvas: Locator): Promise<Drawn[]> {
  return JSON.parse((await canvas.getAttribute('data-units')) ?? '[]') as Drawn[];
}

async function colourAt(page: Page, box: Locator, x: number, y: number): Promise<number[]> {
  const shot = await box.screenshot();
  const bounds = (await box.boundingBox())!;
  return page.evaluate(async ({ png, x, y, width }) => {
    const bytes = Uint8Array.from(atob(png), (c) => c.charCodeAt(0));
    const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
    const scale = bitmap.width / width;
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const ctx = canvas.getContext('2d')!;
    ctx.drawImage(bitmap, 0, 0);
    return Array.from(ctx.getImageData(Math.round(x * scale), Math.round(y * scale), 1, 1).data.slice(0, 3));
  }, { png: shot.toString('base64'), x, y, width: bounds.width });
}

function near(actual: number[], expected: number[], tolerance = 14) {
  expect(actual.map((v, i) => Math.abs(v - expected[i])).every((d) => d <= tolerance), `${actual} ≈ ${expected}`).toBe(true);
}

test.describe('전투 판 유닛 시험 화면', () => {
  test('그려진다: 판 그림 · 세력색 깃발 · 가운데가 전투 캔버스', { tag: '@both' }, async ({ page }) => {
    const canvas = await open(page);
    const box = (await canvas.boundingBox())!;
    const centre = await colourAt(page, canvas, box.width / 2, box.height / 2);
    expect(centre.some((v, i) => Math.abs(v - BACKGROUND[i]) > 20), `가운데 ${centre}`).toBe(true);
    const topId = await page.evaluate(({ x, y }) => document.elementFromPoint(x, y)?.getAttribute('data-testid'), { x: box.x + box.width / 2, y: box.y + box.height / 2 });
    expect(topId).toBe('battle-lab-canvas');
    // 글자 없는 깃발(cav)의 천 가운데 = 세력색(설계 viewBox 20 × 14에서 (9, 6))
    const cav = (await drawn(canvas)).find((u) => u.id === 'cav')!;
    near(await colourAt(page, canvas, cav.flag.x + (9 / 20) * cav.flag.width, cav.flag.y + (6 / 14) * cav.flag.height), BLUE);
  });

  test('조작된다: 분대를 누르면 잡히고 1배로 줄어든다', { tag: '@both' }, async ({ page }) => {
    const canvas = await open(page);
    const units = await drawn(canvas);
    const heo = units.find((u) => u.id === 'heo')!;
    expect(heo.hit.width).toBeGreaterThanOrEqual(44);
    expect(heo.hit.height).toBeGreaterThanOrEqual(44);
    await canvas.click({ position: { x: heo.hit.x + heo.hit.width / 2, y: heo.hit.y + heo.hit.height / 2 } });
    await expect(page.getByTestId('battle-lab-hit')).toHaveText('분대 heo');
    await canvas.click({ position: { x: 4, y: 4 } });
    await expect(page.getByTestId('battle-lab-hit')).toHaveText('누른 분대 없음');
    await page.getByRole('button', { name: '1배' }).click();
    await expect(canvas).toHaveAttribute('data-scale', '1');
    const small = (await drawn(canvas)).find((u) => u.id === 'heo')!;
    expect(small.flag.width).toBeCloseTo(heo.flag.width / 2, 5);
    expect(small.hit.width).toBeGreaterThanOrEqual(44);
  });
});
