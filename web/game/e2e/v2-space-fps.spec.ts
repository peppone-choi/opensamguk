// OPENSAM-41 [G0-C] G0C-k — 60/30 FPS 실측 게이트.
//
// 실행 전제: V2_ENABLED=true 로 띄운 web/game (미설정이면 라우트가 404 — 그것이 격리 게이트다).
//   V2_ENABLED=true pnpm dev
//   E2E_GAME_URL=http://localhost:3001 pnpm test:e2e v2-space-fps
//
// 측정값은 브라우저 rAF 실측(components/v2/SpaceProof3D → lib/v2/terrain/telemetry)이며
// 원시 수치를 test-results/v2-space-fps.json 에 그대로 떨군다. 지어낸 수치는 없다.

import { expect, test, type Page } from '@playwright/test';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';

interface FrameTelemetry {
  frames: number;
  elapsedMs: number;
  fps: number;
  p50FrameMs: number;
  p95FrameMs: number;
  worstFrameMs: number;
}
interface SpaceProbe {
  mode: string;
  lod: string;
  placeCount: number;
  drawnMarkerCount: number;
  clusterCount: number;
  projectionVersion: string;
  telemetry: FrameTelemetry | null;
  lastPickedPlaceId: string | null;
}

// /game/** 은 AuthGate 뒤에 있다. 이 스펙은 인증이 아니라 렌더 성능을 재므로
// 게이트웨이 세션 대신 /api/auth/me만 채운다(백엔드 무접점 — 다른 레인 영역을 열지 않는다).
test.beforeEach(async ({ page }) => {
  await page.route('**/api/auth/me', (route) =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ user: { id: 1, username: 'g0c-probe', role: 'USER' } }),
    }),
  );
});

const RESULTS: Record<string, unknown> = {};
const RESULT_PATH = join(process.cwd(), 'test-results', 'v2-space-fps.json');

test.afterAll(() => {
  mkdirSync(dirname(RESULT_PATH), { recursive: true });
  writeFileSync(RESULT_PATH, JSON.stringify(RESULTS, null, 2));
});

async function readProbe(page: Page): Promise<SpaceProbe> {
  return page.evaluate(() => (window as unknown as { __v2Space: SpaceProbe }).__v2Space);
}

/** 텔레메트리 창이 충분히 찰 때까지 기다린 뒤 실측치를 읽는다. */
async function measure(page: Page, url: string, minFrames = 200): Promise<SpaceProbe> {
  await page.goto(url);
  await expect(page.getByTestId('v2-space-canvas')).toBeVisible();
  await page.waitForFunction(
    (n) => {
      const probe = (window as unknown as { __v2Space?: SpaceProbe }).__v2Space;
      return !!probe?.telemetry && probe.telemetry.frames >= n;
    },
    minFrames,
    { timeout: 60_000 },
  );
  return readProbe(page);
}

test.describe('G0C-k 60/30 FPS 실측', () => {
  test('데모 scene(도시3·route2·terrain1)은 60 FPS를 지킨다', async ({ page }) => {
    const probe = await measure(page, '/game/v2-lab/space?animate=1');
    RESULTS.demo = probe;

    expect(probe.mode, 'WebGL로 그려져야 FPS 측정이 의미 있다').toBe('WEBGL');
    expect(probe.placeCount).toBe(3);
    // 60 FPS = 프레임 시간 16.67ms. 평균 FPS는 단발 히치 하나에 흔들리므로 프레임 시간
    // 백분위로 판정한다(rAF는 주사율에 묶여 60을 넘지 못하므로 평균은 상한이 곧 목표다).
    expect(probe.telemetry!.p50FrameMs).toBeLessThanOrEqual(16.9);
    expect(probe.telemetry!.p95FrameMs).toBeLessThanOrEqual(17.5);
    expect(probe.telemetry!.fps).toBeGreaterThanOrEqual(55);
  });

  test('synthetic 2,000 거점은 30 FPS를 지킨다', async ({ page }) => {
    const probe = await measure(page, '/game/v2-lab/space?scene=synthetic&animate=1&lod=SYMBOL');
    RESULTS.synthetic2000 = probe;

    expect(probe.mode).toBe('WEBGL');
    expect(probe.placeCount).toBe(2000);
    expect(probe.drawnMarkerCount).toBe(2000);
    // 30 FPS = 33.3ms.
    expect(probe.telemetry!.p95FrameMs).toBeLessThanOrEqual(33.3);
    expect(probe.telemetry!.fps).toBeGreaterThanOrEqual(30);
  });

  test('CLUSTER LOD에서도 2,000 거점 전량이 표현된다', async ({ page }) => {
    const probe = await measure(page, '/game/v2-lab/space?scene=synthetic&animate=1&lod=CLUSTER', 120);
    RESULTS.synthetic2000Cluster = probe;

    expect(probe.placeCount).toBe(2000);
    expect(probe.clusterCount).toBeGreaterThan(0);
    expect(probe.telemetry!.p95FrameMs).toBeLessThanOrEqual(33.3);
    expect(probe.telemetry!.fps).toBeGreaterThanOrEqual(30);
  });
});

test.describe('G0C-a/b canvas 픽셀 + 브라우저 picking 왕복', () => {
  test('canvas가 실제로 무언가를 그린다 (빈 화면 통과 방지)', async ({ page }) => {
    await page.goto('/game/v2-lab/space');
    const canvas = page.getByTestId('v2-space-canvas');
    await expect(canvas).toBeVisible();

    // WebGL 캔버스는 preserveDrawingBuffer 없이 합성 후 지워지므로 drawImage로는 읽을 수 없다.
    // 합성 결과를 보는 유일한 정직한 창은 스크린샷이다(티켓이 요구하는 그 검사).
    const shotPath = join(process.cwd(), 'test-results', 'v2-space-demo.png');
    const shot = await canvas.screenshot({ path: shotPath });

    const histogram = await page.evaluate(async (b64) => {
      const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
      const bitmap = await createImageBitmap(new Blob([bytes], { type: 'image/png' }));
      const cv = document.createElement('canvas');
      cv.width = bitmap.width;
      cv.height = bitmap.height;
      const ctx = cv.getContext('2d')!;
      ctx.drawImage(bitmap, 0, 0);
      const data = ctx.getImageData(0, 0, cv.width, cv.height).data;
      const counts = new Map<number, number>();
      for (let i = 0; i < data.length; i += 4) {
        const rgb = (data[i] << 16) | (data[i + 1] << 8) | data[i + 2];
        counts.set(rgb, (counts.get(rgb) ?? 0) + 1);
      }
      const total = data.length / 4;
      const sorted = [...counts.values()].sort((a, b) => b - a);
      return { distinct: counts.size, total, backgroundShare: sorted[0] / total };
    }, shot.toString('base64'));

    RESULTS.demoPixels = histogram;
    // 단색이면 배경만 칠해진 것 — 마커·경로·지형 윤곽이 그려졌다면 색이 여러 개다.
    expect(histogram.distinct).toBeGreaterThan(1);
    // 그렇다고 화면이 전부 도형이어서도 안 된다(배경이 대다수인 정상 지도).
    expect(histogram.backgroundShare).toBeGreaterThan(0.5);
    expect(histogram.backgroundShare).toBeLessThan(1);
  });

  test('canvas 클릭이 같은 PhysicalPlace identity로 되돌아온다', async ({ page }) => {
    await page.goto('/game/v2-lab/space');
    const canvas = page.getByTestId('v2-space-canvas');
    await expect(canvas).toBeVisible();

    // 렌더러와 picking이 같은 project()를 쓰므로, 브라우저에서 투영 좌표를 계산해 그 점을 클릭한다.
    const target = await page.evaluate(() => {
      const probe = (window as unknown as { __v2Space: SpaceProbe }).__v2Space;
      return probe.projectionVersion;
    });
    expect(target).toBe('ortho-1');

    const box = (await canvas.boundingBox())!;
    // 데모 카메라에서 낙양(place:luoyang)의 투영 좌표 — lib/v2/terrain/projection.ts와 동일 수식.
    const cam = { tx: -70, ty: 0, tz: 0, zoom: 2.2, az: Math.PI / 6, pitch: Math.PI / 5 };
    const dx = 0 - cam.tx;
    const dy = 12 - cam.ty;
    const dz = 0 - cam.tz;
    const ex = dx * Math.cos(cam.az) + dz * Math.sin(cam.az);
    const ez = -dx * Math.sin(cam.az) + dz * Math.cos(cam.az);
    const up = ez * Math.cos(cam.pitch) - dy * Math.sin(cam.pitch);
    const sx = 1024 / 2 + ex * cam.zoom;
    const sy = 768 / 2 + up * cam.zoom;

    await page.mouse.click(box.x + sx, box.y + sy);
    await expect(page.getByTestId('v2-space-picked')).toHaveText('place:luoyang');
    expect((await readProbe(page)).lastPickedPlaceId).toBe('place:luoyang');
  });
});

test.describe('G0C-f WebGL 불가 fallback (브라우저)', () => {
  test('fallback은 같은 read model을 표로 낸다', async ({ page }) => {
    await page.goto('/game/v2-lab/space?fallback=1');
    await expect(page.getByTestId('v2-space-fallback')).toBeVisible();
    await expect(page.getByTestId('v2-space-canvas')).toHaveCount(0);
    await expect(page.locator('[data-place-id]')).toHaveCount(3);
  });
});
