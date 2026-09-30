// 키트 스프라이트(1칸 거점 · 깃발 천)를 세력색으로 칠해 캔버스로 만든다. 역할 1 = 주색, 2 = 그늘, 3 = 대비 테두리.
import { shadeRgb, ROOF_SHADE_LIGHTNESS } from './provinceTable';
import type { SiteKind } from './places';

export interface SpriteSheet {
  width: number;
  height: number;
  rgba: Uint8ClampedArray;
  roles: Uint8Array;
}

export const SITE_ORDER: readonly SiteKind[] = ['county', 'ferry', 'fort', 'tribe'];
export const FLAG_ORDER = ['fringe', 'swallow'] as const;
export type FlagKind = (typeof FLAG_ORDER)[number];

type Rgb = readonly [number, number, number];

export function parseHex(color: string): Rgb {
  return [Number.parseInt(color.slice(1, 3), 16), Number.parseInt(color.slice(3, 5), 16), Number.parseInt(color.slice(5, 7), 16)];
}

export function luma(rgb: Rgb): number {
  return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255;
}

/** Copies one 16×16 cell of a sheet and recolours its role pixels. `colour` null keeps the original. */
export function paintCell(sheet: SpriteSheet, index: number, colour: Rgb | null): ImageData {
  const cellsPerRow = sheet.width / 16;
  const ox = (index % cellsPerRow) * 16;
  const oy = Math.floor(index / cellsPerRow) * 16;
  const out = new ImageData(16, 16);
  const shade = colour ? shadeRgb(colour, ROOF_SHADE_LIGHTNESS) : null;
  const border: Rgb = colour && luma(colour) >= 0.62 ? [136, 68, 34] : [255, 255, 255];
  for (let y = 0; y < 16; y += 1) {
    for (let x = 0; x < 16; x += 1) {
      const src = (oy + y) * sheet.width + ox + x;
      const dst = (y * 16 + x) * 4;
      out.data.set(sheet.rgba.subarray(src * 4, src * 4 + 4), dst);
      if (!colour || out.data[dst + 3] === 0) continue;
      const role = sheet.roles[src];
      const paint = role === 1 ? colour : role === 2 ? shade : role === 3 ? border : null;
      if (paint) out.data.set(paint, dst);
    }
  }
  return out;
}

/** Cloth centre line x = a + b·y of a flag cell, fitted over the cloth rows (like the reference flag tool). */
export function clothSlant(sheet: SpriteSheet, index: number): { a: number; b: number; top: number; bottom: number } {
  const cellsPerRow = sheet.width / 16;
  const ox = (index % cellsPerRow) * 16;
  const oy = Math.floor(index / cellsPerRow) * 16;
  const ys: number[] = [];
  const xs: number[] = [];
  for (let y = 4; y < 12; y += 1) {
    let min = 16;
    let max = -1;
    for (let x = 0; x < 16; x += 1) {
      if (sheet.roles[(oy + y) * sheet.width + ox + x] === 1) {
        min = Math.min(min, x);
        max = Math.max(max, x);
      }
    }
    if (max >= 0) {
      ys.push(y);
      xs.push((min + max + 1) / 2);
    }
  }
  const n = ys.length;
  const my = ys.reduce((s, v) => s + v, 0) / n;
  const mx = xs.reduce((s, v) => s + v, 0) / n;
  let num = 0;
  let den = 0;
  for (let i = 0; i < n; i += 1) {
    num += (ys[i] - my) * (xs[i] - mx);
    den += (ys[i] - my) ** 2;
  }
  const b = den ? num / den : 0;
  return { a: mx - b * my, b, top: 4, bottom: 12 };
}

function canvas(width: number, height: number): OffscreenCanvas {
  return new OffscreenCanvas(width, height);
}

/**
 * Flag sprite at `scale` (≥ 2 so the letter is at least 32px tall in the cloth's frame): nation cloth plus the
 * first letter, drawn along the cloth slant and clipped to the cloth.
 */
export function drawFlag(
  sheet: SpriteSheet,
  kind: FlagKind,
  colourHex: string,
  letter: string,
  scale: number,
  font: string,
): OffscreenCanvas {
  const index = FLAG_ORDER.indexOf(kind);
  const colour = parseHex(colourHex);
  const cell = paintCell(sheet, index, colour);
  const size = 16 * scale;
  const base = canvas(16, 16);
  base.getContext('2d')!.putImageData(cell, 0, 0);
  const out = canvas(size, size);
  const ctx = out.getContext('2d')!;
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(base, 0, 0, size, size);

  // 글자: 천 줄 높이에 맞춰 크기를 정하고, 천 기울기대로 비스듬히 민 뒤 천 밖을 지운다
  const slant = clothSlant(sheet, index);
  const text = canvas(size, size);
  const tctx = text.getContext('2d')!;
  const clothHeight = (slant.bottom - slant.top) * scale;
  tctx.font = `${Math.round(clothHeight * 0.95)}px ${font}`;
  tctx.textAlign = 'center';
  tctx.textBaseline = 'middle';
  tctx.fillStyle = luma(colour) >= 0.5 ? 'rgb(12,15,14)' : 'rgb(255,255,255)';
  const midY = ((slant.top + slant.bottom) / 2) * scale;
  const midX = (slant.a + slant.b * (slant.top + slant.bottom) / 2) * scale;
  tctx.setTransform(1, 0, slant.b, 1, midX - slant.b * midY, midY);
  tctx.fillText(letter, 0, 0);
  tctx.setTransform(1, 0, 0, 1, 0, 0);
  const mask = canvas(size, size);
  const mctx = mask.getContext('2d')!;
  const clothMask = new ImageData(16, 16);
  const cellsPerRow = sheet.width / 16;
  const ox = (index % cellsPerRow) * 16;
  for (let y = 0; y < 16; y += 1) {
    for (let x = 0; x < 16; x += 1) {
      if (sheet.roles[y * sheet.width + ox + x] === 1) clothMask.data[(y * 16 + x) * 4 + 3] = 255;
    }
  }
  const small = canvas(16, 16);
  small.getContext('2d')!.putImageData(clothMask, 0, 0);
  mctx.imageSmoothingEnabled = false;
  mctx.drawImage(small, 0, 0, size, size);
  mctx.globalCompositeOperation = 'source-in';
  mctx.drawImage(text, 0, 0);
  ctx.drawImage(mask, 0, 0);
  return out;
}

/** Site icon (16×16) painted in the nation colour, as a canvas. */
export function drawSite(sheet: SpriteSheet, site: SiteKind, colourHex: string | null): OffscreenCanvas {
  const cell = paintCell(sheet, SITE_ORDER.indexOf(site), colourHex ? parseHex(colourHex) : null);
  const out = canvas(16, 16);
  out.getContext('2d')!.putImageData(cell, 0, 0);
  return out;
}

export function sheetFrom(bitmapRgba: ImageData, roles: Uint8Array): SpriteSheet {
  return { width: bitmapRgba.width, height: bitmapRgba.height, rgba: bitmapRgba.data, roles };
}
