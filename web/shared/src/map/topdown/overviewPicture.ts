// 천하 그림 한 장(개관 격자 한 칸 = 1px, 색은 키트 1px 밉): 작은 지도와 WebGL이 없을 때의 대체 화면이 쓴다.
import { fetchJson, fetchOverview, joinUrl, loadBitmap } from './loaders';
import { NO_TILE, outOfScopeLandRgb, type BakeManifest, type ChunkData } from './types';

const BACKGROUND = [12, 15, 14, 255] as const;

/**
 * RGBA pixels, one per overview entry; `mip1` is the kit's 1-px atlas (one pixel per tile, `mip1Width` wide).
 * 그리지 않는 칸(범위 밖 땅)은 `outOfScope`(흐린 땅색, 지도와 같은 색 — D42)로, 없으면 바탕색으로 칠한다.
 */
export function overviewPixels(overview: ChunkData, cols: number, rows: number, mip1: Uint8ClampedArray, mip1Width: number,
  outOfScope?: readonly [number, number, number]): Uint8ClampedArray {
  const out = new Uint8ClampedArray(cols * rows * 4);
  const undrawn = outOfScope ? [...outOfScope, 255] : BACKGROUND;
  for (let i = 0; i < cols * rows; i += 1) {
    const tile = overview.tiles[i];
    const at = i * 4;
    if (tile === NO_TILE) {
      out.set(undrawn, at);
      continue;
    }
    const src = (Math.floor(tile / mip1Width) * mip1Width + (tile % mip1Width)) * 4;
    out[at] = mip1[src];
    out[at + 1] = mip1[src + 1];
    out[at + 2] = mip1[src + 2];
    out[at + 3] = 255;
  }
  return out;
}

export function pixelsToCanvas(pixels: Uint8ClampedArray, cols: number, rows: number): OffscreenCanvas {
  const canvas = new OffscreenCanvas(cols, rows);
  canvas.getContext('2d')!.putImageData(new ImageData(pixels, cols, rows), 0, 0);
  return canvas;
}

export function bitmapPixels(bitmap: ImageBitmap): Uint8ClampedArray {
  const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
  const ctx = canvas.getContext('2d', { willReadFrequently: true })!;
  ctx.drawImage(bitmap, 0, 0);
  return ctx.getImageData(0, 0, bitmap.width, bitmap.height).data;
}

/** 키트 palettes.json 의 낮 팔레트 → 16 × RGBA 바이트(렌더러 · 대체 그림이 같은 주소 · 같은 캐시로 받는다). */
export function dayPalette(palettes: { dayBank: number; banks: number[][][] }): Uint8Array {
  const palette = new Uint8Array(16 * 4);
  palettes.banks[palettes.dayBank].forEach(([r, g, b], i) => palette.set([r, g, b, 255], i * 4));
  return palette;
}

/** Whole-map picture without WebGL (fallback screen): manifest + overview grid + 1-px mip (+ day palette for out-of-scope land). */
export async function loadOverviewPicture(bakeUrl: string, kitUrl: string): Promise<OffscreenCanvas> {
  const manifest = await fetchJson<BakeManifest>(joinUrl(bakeUrl, 'manifest.json'));
  const [overview, mip1, palettes] = await Promise.all([
    fetchOverview(bakeUrl, manifest),
    loadBitmap(joinUrl(kitUrl, 'kit-mip1.png')),
    fetchJson<{ dayBank: number; banks: number[][][] }>(joinUrl(kitUrl, 'palettes.json')),
  ]);
  const { cols, rows } = manifest.overview;
  return pixelsToCanvas(overviewPixels(overview, cols, rows, bitmapPixels(mip1), mip1.width, outOfScopeLandRgb(dayPalette(palettes))), cols, rows);
}
