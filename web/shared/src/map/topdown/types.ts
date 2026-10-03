/** Cell coordinates. Cell (c, r) covers [c, c+1) × [r, r+1); floats are allowed (camera center). */
export interface CellPoint { col: number; row: number }

/** Screen point in CSS px, origin at the viewport's top-left. */
export interface ScreenPoint { x: number; y: number }

export interface MapShape { cols: number; rows: number }

/** Tile-plane value for cells with nothing to draw (out-of-scope land, outside the map). Kit id 0 is a real tile. */
export const NO_TILE = 0xffff;

/**
 * 범위 밖 땅(격자 안의 NO_TILE · 굽기 분류 V — 북쪽 초원 · 서역 · 인도차이나 · 루손)의 흐린 땅색(D42, 사용자 10-03).
 * 새 색 토큰이 아니다: 키트 낮 팔레트 14번(땅에서 가장 흔한 들판색, 실번들 땅 화소 29%)에 시야 「안 보임」과 같은 0.45를 곱한다.
 * 격자 바깥 · 아직 안 온 칸은 바탕색 그대로다.
 */
export const OUT_OF_SCOPE_LAND = Object.freeze({ paletteIndex: 14, dim: 0.45 });

/** 16 × RGBA 낮 팔레트(바이트) → 범위 밖 땅 RGB 바이트. */
export function outOfScopeLandRgb(palette: ArrayLike<number>): [number, number, number] {
  const at = OUT_OF_SCOPE_LAND.paletteIndex * 4;
  return [0, 1, 2].map((i) => Math.round(palette[at + i] * OUT_OF_SCOPE_LAND.dim)) as [number, number, number];
}

/** The product map grid (han-tiles and design-layer axis). Never hardcode these numbers elsewhere. */
export const HAN_MAP_SHAPE: Readonly<MapShape> = Object.freeze({ cols: 3072, rows: 2676 });

/** Viewport size in CSS px. `dpr` is only applied at the GL viewport. */
export interface Viewport { width: number; height: number; dpr: number }

/** 州 / 郡 / 縣 view levels. */
export type ViewLevel = 'ju' | 'commandery' | 'county';

/** zoom = CSS px per cell. */
export interface Camera { center: CellPoint; zoom: number }

/** Integer cell rectangle, inclusive-exclusive: [col0, col1) × [row0, row1). */
export interface CellRect { col0: number; row0: number; col1: number; row1: number }

/** Exactly one of `file` (with `sha256`) or `uniform` is present. */
export interface BakeChunkEntry {
  cx: number;
  cy: number;
  file?: string;
  sha256?: string;
  uniform?: { tile: number; province: number };
}

export interface BakeManifest {
  schemaVersion: 1;
  artifactId: 'topdown-bake';
  bakeId: string;
  shape: MapShape;
  chunkSize: number;
  kitId: string;
  inputs: Record<string, string>;
  chunks: BakeChunkEntry[];
  overview: { file: string; sha256: string; cols: number; rows: number; block: number };
  places: { file: string; sha256: string };
}

/**
 * One decoded chunk, both planes of length chunkSize², row-major.
 * provinces: 0 = none, n = provinceRecords[n - 1] (plane value = provinceIndex + 1).
 */
export interface ChunkData { tiles: Uint16Array; provinces: Uint16Array }
