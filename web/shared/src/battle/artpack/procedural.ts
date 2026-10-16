// 임시 절차 타일 팩 — 실제 아트(B2)가 오기 전, 렌더러 v2 를 같은 계약(manifest v1)으로 돌려 보는 색 · 음영 그림.
// 이미지 입력 없이 코드로만 칠한다(같은 입력 → 같은 바이트). 유닛 그림은 없다(units = {} → 마름모로 그린다).
// 빛은 왼쪽 위, 그림자는 오른쪽 아래 반투명으로 프레임에 굽는다. 배율 2(바닥 64 × 32).
import {
  ARTPACK_ARTIFACT_ID,
  ARTPACK_ATLAS_MAX,
  EDGE_NE,
  EDGE_NW,
  EDGE_SE,
  EDGE_SW,
  TERRAIN_LETTERS,
  TRANSITION_LETTERS,
  type ArtpackFrame,
  type ArtpackManifest,
  type TerrainLetter,
} from './manifest';

export const PROCEDURAL_PACK_ID = 'procedural-v1';
const PPB = 2;
const TILE_W = 32 * PPB;
const TILE_H = 16 * PPB;
const VARIANTS = 4;

export interface RgbaAtlas {
  width: number;
  height: number;
  rgba: Uint8ClampedArray;
}

export interface ProceduralArtpack {
  manifest: ArtpackManifest;
  atlases: RgbaAtlas[];
}

type Rgb = readonly [number, number, number];

const GROUND: Record<TerrainLetter, readonly Rgb[]> = {
  P: [[118, 132, 84], [126, 136, 88], [138, 128, 94], [112, 126, 80]],
  F: [[84, 102, 64], [80, 98, 60], [90, 104, 66], [86, 100, 62]],
  M: [[124, 116, 100], [118, 112, 98], [130, 120, 102], [120, 114, 96]],
  R: [[76, 110, 126], [72, 106, 122], [80, 114, 128], [74, 108, 124]],
  W: [[142, 136, 120], [138, 132, 116], [146, 138, 122], [140, 134, 118]],
};

/** Deterministic 0..1 noise for (seed, x, y). */
function noise(seed: number, x: number, y: number): number {
  let h = (seed * 374761393 + x * 668265263 + y * 2246822519) >>> 0;
  h = Math.imul(h ^ (h >>> 13), 1274126177) >>> 0;
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296;
}

class Sprite {
  readonly rgba: Uint8ClampedArray;
  constructor(readonly w: number, readonly h: number) {
    this.rgba = new Uint8ClampedArray(w * h * 4);
  }
  /** Source-over blend of one pixel. */
  put(x: number, y: number, rgb: Rgb, alpha = 1, light = 1): void {
    if (x < 0 || y < 0 || x >= this.w || y >= this.h || alpha <= 0) return;
    const at = (y * this.w + x) * 4;
    const a0 = this.rgba[at + 3] / 255;
    const a = alpha + a0 * (1 - alpha);
    for (let k = 0; k < 3; k += 1) {
      const src = Math.min(255, Math.max(0, rgb[k] * light));
      this.rgba[at + k] = Math.round((src * alpha + this.rgba[at + k] * a0 * (1 - alpha)) / a);
    }
    this.rgba[at + 3] = Math.round(a * 255);
  }
}

/** (u, v) of a pixel centre in a diamond centred at (cx, cy) with half sizes (hw, hh); inside when |u| + |v| ≤ 1. */
function diamondUv(x: number, y: number, cx: number, cy: number, hw: number, hh: number): [number, number] {
  return [(x + 0.5 - cx) / hw, (y + 0.5 - cy) / hh];
}

function groundTile(letter: TerrainLetter, variant: number): Sprite {
  const s = new Sprite(TILE_W, TILE_H);
  const base = GROUND[letter][variant];
  const seed = TERRAIN_LETTERS.indexOf(letter) * 16 + variant;
  for (let y = 0; y < TILE_H; y += 1) {
    for (let x = 0; x < TILE_W; x += 1) {
      const [u, v] = diamondUv(x, y, TILE_W / 2, TILE_H / 2, TILE_W / 2, TILE_H / 2);
      if (Math.abs(u) + Math.abs(v) > 1) continue;
      // 왼쪽 위가 밝다 + 결 무늬(물은 가로 물결).
      let light = 1 - 0.06 * (u + v) + (noise(seed, x, y) - 0.5) * 0.08;
      if (letter === 'R') light += 0.05 * Math.sin((y + variant * 3) * 0.9 + x * 0.12);
      s.put(x, y, base, 1, light);
    }
  }
  return s;
}

/** Overlay of `letter`'s colour along the edges in `edges` (EDGE_* bits), fading inward. */
function transitionTile(letter: TerrainLetter, edges: number): Sprite {
  const s = new Sprite(TILE_W, TILE_H);
  const base = GROUND[letter][0];
  const seed = 100 + TERRAIN_LETTERS.indexOf(letter) * 16 + edges;
  for (let y = 0; y < TILE_H; y += 1) {
    for (let x = 0; x < TILE_W; x += 1) {
      const [u, v] = diamondUv(x, y, TILE_W / 2, TILE_H / 2, TILE_W / 2, TILE_H / 2);
      if (Math.abs(u) + Math.abs(v) > 1) continue;
      let near = 0;
      if (edges & EDGE_NE) near = Math.max(near, 1 - (1 - (u - v)) / 0.4);
      if (edges & EDGE_SE) near = Math.max(near, 1 - (1 - (u + v)) / 0.4);
      if (edges & EDGE_SW) near = Math.max(near, 1 - (1 - (v - u)) / 0.4);
      if (edges & EDGE_NW) near = Math.max(near, 1 - (1 + u + v) / 0.4);
      const ragged = near - (noise(seed, x, y) - 0.5) * 0.3;
      if (ragged <= 0) continue;
      s.put(x, y, base, Math.min(0.9, ragged * 0.9), 1 - 0.06 * (u + v));
    }
  }
  return s;
}

function shadow(s: Sprite, cx: number, cy: number, rx: number, ry: number): void {
  for (let y = Math.floor(cy - ry); y <= Math.ceil(cy + ry); y += 1) {
    for (let x = Math.floor(cx - rx); x <= Math.ceil(cx + rx); x += 1) {
      const d = ((x + 0.5 - cx) / rx) ** 2 + ((y + 0.5 - cy) / ry) ** 2;
      if (d <= 1) s.put(x, y, [16, 18, 14], 0.28 * (1 - d * 0.5));
    }
  }
}

/** Tree 48 × 64, foot (24, 58). */
function tree(variant: number): { sprite: Sprite; ax: number; ay: number } {
  const s = new Sprite(48, 64);
  shadow(s, 31, 58, 14, 4);
  for (let y = 42; y < 59; y += 1) for (let x = 22; x < 26; x += 1) s.put(x, y, [92, 74, 52], 1, x < 24 ? 1.1 : 0.9);
  const cx = 24 + (variant % 2 ? 1 : -1);
  const cy = 28 + variant;
  const rx = 15 + (variant % 3);
  const ry = 18 - (variant % 2) * 2;
  for (let y = 0; y < 64; y += 1) {
    for (let x = 0; x < 48; x += 1) {
      const du = (x + 0.5 - cx) / rx;
      const dv = (y + 0.5 - cy) / ry;
      const d = du * du + dv * dv + (noise(200 + variant, x >> 1, y >> 1) - 0.5) * 0.25;
      if (d > 1) continue;
      s.put(x, y, [70, 94, 56], 1, 1.12 - 0.18 * (du + dv) - d * 0.1);
    }
  }
  return { sprite: s, ax: 24, ay: 58 };
}

/** Mound 64 × 64 whose base is the cell diamond (centre (32, 48)). */
function mound(variant: number): { sprite: Sprite; ax: number; ay: number } {
  const s = new Sprite(64, 64);
  shadow(s, 44, 52, 18, 7);
  const peak = 48 - (34 + variant * 3);
  const px = 32 + (variant - 1.5) * 2;
  for (let y = 0; y < 64; y += 1) {
    for (let x = 0; x < 64; x += 1) {
      const dx = x + 0.5 - px;
      const bottom = 48 + 16 * (1 - Math.abs(x + 0.5 - 32) / 32);
      const slope = peak + Math.abs(dx) * ((48 - peak) / 32) + (noise(300 + variant, x >> 1, y >> 2) - 0.5) * 3;
      if (y + 0.5 < slope || y + 0.5 > bottom) continue;
      const lit = dx < 0 ? 1.12 : 0.82;
      s.put(x, y, [126, 118, 102], 1, lit + (noise(320 + variant, x, y) - 0.5) * 0.08);
    }
  }
  return { sprite: s, ax: 32, ay: 48 };
}

/** Wall block 64 × 56: top diamond (centre (32, 16)) raised 24 above the cell diamond (foot (32, 40)). */
function wallBlock(): { sprite: Sprite; ax: number; ay: number } {
  const s = new Sprite(64, 56);
  const H = 24;
  for (let y = 0; y < 56; y += 1) {
    for (let x = 0; x < 64; x += 1) {
      const [u, v] = diamondUv(x, y, 32, 16, 32, 16);
      if (Math.abs(u) + Math.abs(v) <= 1) {
        s.put(x, y, [156, 148, 130], 1, 1.04 - 0.05 * (u + v) + (noise(400, x >> 2, y >> 1) - 0.5) * 0.06);
        continue;
      }
      // 옆면: 윗면 아래 가장자리(edge)부터 H 만큼 내린 띠. 왼쪽(빛 받음)이 밝고, 6 px 마다 돌 줄눈.
      const edge = 16 + 16 * (1 - Math.abs(u));
      if (v <= 0 || y + 0.5 < edge || y + 0.5 > edge + H) continue;
      const course = Math.floor(y + 0.5 - edge) % 6 === 5 ? 0.86 : 1;
      s.put(x, y, x < 32 ? [140, 132, 114] : [104, 98, 86], 1, course);
    }
  }
  return { sprite: s, ax: 32, ay: 40 };
}

interface Placed {
  sprite: Sprite;
  ax: number;
  ay: number;
  frame?: ArtpackFrame;
}

/** Shelf packing into one atlas no wider than ARTPACK_ATLAS_MAX. */
function packAtlas(items: Placed[]): RgbaAtlas {
  let x = 0;
  let y = 0;
  let row = 0;
  let width = 0;
  for (const item of items) {
    if (x + item.sprite.w > ARTPACK_ATLAS_MAX) {
      x = 0;
      y += row;
      row = 0;
    }
    item.frame = { atlas: 0, x, y, w: item.sprite.w, h: item.sprite.h, ax: item.ax, ay: item.ay };
    x += item.sprite.w;
    row = Math.max(row, item.sprite.h);
    width = Math.max(width, x);
  }
  const height = y + row;
  if (height > ARTPACK_ATLAS_MAX) throw new Error('procedural artpack: atlas too tall');
  const rgba = new Uint8ClampedArray(width * height * 4);
  for (const { sprite, frame } of items) {
    for (let sy = 0; sy < sprite.h; sy += 1) {
      rgba.set(sprite.rgba.subarray(sy * sprite.w * 4, (sy + 1) * sprite.w * 4), ((frame!.y + sy) * width + frame!.x) * 4);
    }
  }
  return { width, height, rgba };
}

let cached: ProceduralArtpack | null = null;

/** The temporary procedural pack (built once). */
export function proceduralArtpack(): ProceduralArtpack {
  cached ??= buildProceduralArtpack();
  return cached;
}

/** Builds the procedural pack from scratch (same bytes every time). */
export function buildProceduralArtpack(): ProceduralArtpack {
  const centre = { ax: TILE_W / 2, ay: TILE_H / 2 };
  const ground = new Map<TerrainLetter, Placed[]>();
  const items: Placed[] = [];
  for (const letter of TERRAIN_LETTERS) {
    const list = Array.from({ length: VARIANTS }, (_, v) => ({ sprite: groundTile(letter, v), ...centre }));
    ground.set(letter, list);
    items.push(...list);
  }
  const transitions = new Map<string, Placed[]>();
  for (const letter of TRANSITION_LETTERS) {
    const list = Array.from({ length: 15 }, (_, i) => ({ sprite: transitionTile(letter, i + 1), ...centre }));
    transitions.set(letter, list);
    items.push(...list);
  }
  const trees = Array.from({ length: VARIANTS }, (_, v) => tree(v));
  const mounds = Array.from({ length: VARIANTS }, (_, v) => mound(v));
  const wall = [wallBlock()];
  items.push(...trees, ...mounds, ...wall);
  const atlas = packAtlas(items);
  const frames = (list: Placed[]) => list.map((p) => p.frame!);
  const terrain = Object.fromEntries(TERRAIN_LETTERS.map((l) => [l, { ground: frames(ground.get(l)!) }])) as ArtpackManifest['terrain'];
  terrain.F.tall = frames(trees);
  terrain.M.tall = frames(mounds);
  terrain.W.tall = frames(wall);
  const manifest: ArtpackManifest = {
    schemaVersion: 1,
    artifactId: ARTPACK_ARTIFACT_ID,
    packId: PROCEDURAL_PACK_ID,
    pixelsPerBoardPx: PPB,
    shadow: 'baked',
    atlases: [{ file: 'procedural-0.png', width: atlas.width, height: atlas.height }],
    terrain,
    transitions: Object.fromEntries(TRANSITION_LETTERS.map((l) => [l, [null, ...frames(transitions.get(l)!)]])),
    units: {},
    provenance: [{ source: 'procedural', note: 'Temporary colour/shade tiles drawn in code (artpack/procedural.ts); no image input.' }],
  };
  return { manifest, atlases: [atlas] };
}
