import { isOwnedNationVisual, parseNationColor } from '../../nationVisual';

/** Texels per row of the province lookup texture. */
export const TABLE_WIDTH = 4096;
/** Most owned nations a slot byte can hold (slot 0 = unowned). */
export const MAX_NATION_SLOTS = 255;
/** Roof shade lightness factor (reference renderer `shade(col, 0.55)`). */
export const ROOF_SHADE_LIGHTNESS = 0.55;

export type VisionState = 'FULL' | 'INTEL' | 'FOG';

export const VISION_BYTE: Readonly<Record<VisionState, number>> = Object.freeze({ FULL: 0, INTEL: 1, FOG: 2 });

/** B channel. 0 = no pick mode. */
export const PICK_BYTE = Object.freeze({ none: 0, available: 1, unavailable: 2, notCandidate: 3 });

/** A channel bit flags. */
export const FLAG_SELECTED = 1;
export const FLAG_HOVERED = 2;

export interface ProvinceTableInput {
  provinceCount: number;
  occupancy: ReadonlyArray<{ provinceIndex: number; nationId: number }>;
  nations: ReadonlyArray<{ id: number; color: string }>;
  /** province index → commandery number, or -1. */
  commanderyOfProvince?: Int32Array | ReadonlyArray<number>;
  /** Vision by commandery number. */
  vision?: ReadonlyMap<number, VisionState>;
  /** provinceIndex → available. */
  pick?: { candidates: ReadonlyMap<number, boolean> };
  selected?: ReadonlySet<number>;
  hovered?: number | null;
}

export interface ProvinceTable {
  width: typeof TABLE_WIDTH;
  height: number;
  /** RGBA8 row-major; texel v holds plane value v (= provinceIndex + 1). Texel 0 stays zero. */
  bytes: Uint8Array;
  /** nation id → slot (1..255). */
  nationSlots: Map<number, number>;
  /** 256 slots × 8 bytes: main RGBA then roof shade RGBA. Slot 0 is zero. */
  nationPalette: Uint8Array;
}

// Python colorsys.rgb_to_hls / hls_to_rgb 를 연산 순서까지 그대로 옮긴다(참고 렌더러와 같은 바이트).
const ONE_THIRD = 1.0 / 3.0;
const ONE_SIXTH = 1.0 / 6.0;
const TWO_THIRD = 2.0 / 3.0;

/** Python float `x % 1.0` (result has the divisor's sign). */
function pyModOne(x: number): number {
  return x - Math.floor(x);
}

export function rgbToHls(r: number, g: number, b: number): [number, number, number] {
  const maxc = Math.max(r, g, b);
  const minc = Math.min(r, g, b);
  const sumc = maxc + minc;
  const rangec = maxc - minc;
  const l = sumc / 2.0;
  if (minc === maxc) return [0.0, l, 0.0];
  const s = l <= 0.5 ? rangec / sumc : rangec / (2.0 - maxc - minc);
  const rc = (maxc - r) / rangec;
  const gc = (maxc - g) / rangec;
  const bc = (maxc - b) / rangec;
  let h: number;
  if (r === maxc) h = bc - gc;
  else if (g === maxc) h = 2.0 + rc - bc;
  else h = 4.0 + gc - rc;
  h = pyModOne(h / 6.0);
  return [h, l, s];
}

function hueChannel(m1: number, m2: number, hue: number): number {
  const h = pyModOne(hue);
  if (h < ONE_SIXTH) return m1 + (m2 - m1) * h * 6.0;
  if (h < 0.5) return m2;
  if (h < TWO_THIRD) return m1 + (m2 - m1) * (TWO_THIRD - h) * 6.0;
  return m1;
}

export function hlsToRgb(h: number, l: number, s: number): [number, number, number] {
  if (s === 0.0) return [l, l, l];
  const m2 = l <= 0.5 ? l * (1.0 + s) : l + s - (l * s);
  const m1 = 2.0 * l - m2;
  return [hueChannel(m1, m2, h + ONE_THIRD), hueChannel(m1, m2, h), hueChannel(m1, m2, h - ONE_THIRD)];
}

/** numpy `round()` — half to even. */
function roundHalfEven(x: number): number {
  const floor = Math.floor(x);
  const diff = x - floor;
  if (diff > 0.5) return floor + 1;
  if (diff < 0.5) return floor;
  return floor % 2 === 0 ? floor : floor + 1;
}

/** HLS lightness × k, as the reference renderer's `shade(c, k)`. */
export function shadeRgb(rgb: readonly [number, number, number], k: number): [number, number, number] {
  const [h, l, s] = rgbToHls(rgb[0] / 255, rgb[1] / 255, rgb[2] / 255);
  const [r, g, b] = hlsToRgb(h, l * k, s);
  return [roundHalfEven(r * 255), roundHalfEven(g * 255), roundHalfEven(b * 255)];
}

function assignNationSlots(nations: ProvinceTableInput['nations']): Map<number, string> {
  const colorById = new Map<number, string>();
  for (const nation of nations) {
    if (!isOwnedNationVisual(nation.id, nation.color)) continue;
    if (!colorById.has(nation.id)) colorById.set(nation.id, nation.color);
  }
  return colorById;
}

export function buildProvinceTable(input: ProvinceTableInput): ProvinceTable {
  const { provinceCount } = input;
  if (!Number.isInteger(provinceCount) || provinceCount < 0) {
    throw new Error(`provinceCount must be a non-negative integer, got ${provinceCount}`);
  }
  const height = Math.max(1, Math.ceil((provinceCount + 1) / TABLE_WIDTH));
  const bytes = new Uint8Array(TABLE_WIDTH * height * 4);

  const colorById = assignNationSlots(input.nations);
  const ids = [...colorById.keys()].sort((a, b) => a - b);
  if (ids.length > MAX_NATION_SLOTS) {
    throw new Error(`${ids.length} owned nations exceed the ${MAX_NATION_SLOTS} palette slots`);
  }
  const nationSlots = new Map<number, number>();
  const nationPalette = new Uint8Array(256 * 8);
  ids.forEach((id, i) => {
    const slot = i + 1;
    nationSlots.set(id, slot);
    const main = parseNationColor(colorById.get(id) as string);
    const shade = shadeRgb(main, ROOF_SHADE_LIGHTNESS);
    nationPalette.set([main[0], main[1], main[2], 255, shade[0], shade[1], shade[2], 255], slot * 8);
  });

  const inRange = (index: number) => Number.isInteger(index) && index >= 0 && index < provinceCount;
  // 텍셀 번호 = 구역 평면 값 = provinceIndex + 1. 0 은 「구역 없음」이라 비워 둔다.
  const texel = (provinceIndex: number) => (provinceIndex + 1) * 4;

  // 범위 밖 구역 번호(굽기와 월드 판이 어긋난 입력)는 무시한다.
  for (const { provinceIndex, nationId } of input.occupancy) {
    if (!inRange(provinceIndex)) continue;
    bytes[texel(provinceIndex)] = nationSlots.get(nationId) ?? 0;
  }

  const { commanderyOfProvince, vision } = input;
  if (commanderyOfProvince && vision) {
    const limit = Math.min(provinceCount, commanderyOfProvince.length);
    for (let p = 0; p < limit; p += 1) {
      const commandery = commanderyOfProvince[p];
      if (commandery < 0) continue;
      const state = vision.get(commandery);
      if (state) bytes[texel(p) + 1] = VISION_BYTE[state];
    }
  }

  if (input.pick) {
    const { candidates } = input.pick;
    for (let p = 0; p < provinceCount; p += 1) {
      const available = candidates.get(p);
      bytes[texel(p) + 2] = available === undefined
        ? PICK_BYTE.notCandidate
        : available ? PICK_BYTE.available : PICK_BYTE.unavailable;
    }
  }

  for (const p of input.selected ?? []) {
    if (inRange(p)) bytes[texel(p) + 3] |= FLAG_SELECTED;
  }
  if (input.hovered != null && inRange(input.hovered)) {
    bytes[texel(input.hovered) + 3] |= FLAG_HOVERED;
  }

  return { width: TABLE_WIDTH, height, bytes, nationSlots, nationPalette };
}
