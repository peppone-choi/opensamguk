// 세력색 정규화.
//
// 배포본 지도가 삼국지로 안 보이는 원인이 여기 있다. `nation.color` 는 DB 자유 hex 이고
// 시드값이 #0000ff · #00ff00 · #ff0000 같은 순색이다. 그 값을 그대로 불투명하게 덮으면
// 순노랑·형광 청록이 땅 전체를 칠한다(reports/opensamguk/tasks/2026-09-09-design-re-review.md).
//
// DB 값은 건드리지 않는다. 렌더 시점에 채도·명도만 대역 안으로 눌러서, 야전 사령부 팔레트와
// 같은 공기 안에 두고 **곱하기**로 합성한다. 색상(hue)은 그대로 두므로 국가 구분은 남는다.

/** 정규화 대역. 팔레트의 --bronze #d3b064(S 0.55 L 0.61)·--moss #697e58(S 0.18 L 0.42) 사이. */
const SATURATION = 0.42;
const LIGHT_MIN = 0.44;
const LIGHT_MAX = 0.68;

export interface Rgb {
  r: number;
  g: number;
  b: number;
}

function hueToChannel(p: number, q: number, input: number): number {
  let t = input;
  if (t < 0) t += 1;
  if (t > 1) t -= 1;
  if (t < 1 / 6) return p + (q - p) * 6 * t;
  if (t < 1 / 2) return q;
  if (t < 2 / 3) return p + (q - p) * (2 / 3 - t) * 6;
  return p;
}

/** HSL(0..1) → 선형이 아닌 sRGB 0..1. */
export function hslToRgb(h: number, s: number, l: number): Rgb {
  if (s === 0) return { r: l, g: l, b: l };
  const q = l < 0.5 ? l * (1 + s) : l + s - l * s;
  const p = 2 * l - q;
  return {
    r: hueToChannel(p, q, h + 1 / 3),
    g: hueToChannel(p, q, h),
    b: hueToChannel(p, q, h - 1 / 3),
  };
}

/** #rgb · #rrggbb → 0..1 채널. 못 읽으면 null. */
export function parseHex(hex: string): Rgb | null {
  const text = hex.trim().replace(/^#/, '');
  const full = text.length === 3 ? text.replace(/./g, (c) => c + c) : text;
  if (!/^[0-9a-fA-F]{6}$/.test(full)) return null;
  return {
    r: parseInt(full.slice(0, 2), 16) / 255,
    g: parseInt(full.slice(2, 4), 16) / 255,
    b: parseInt(full.slice(4, 6), 16) / 255,
  };
}

function rgbToHsl({ r, g, b }: Rgb): [number, number, number] {
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const l = (max + min) / 2;
  if (max === min) return [0, 0, l];
  const d = max - min;
  const s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
  let h: number;
  if (max === r) h = (g - b) / d + (g < b ? 6 : 0);
  else if (max === g) h = (b - r) / d + 2;
  else h = (r - g) / d + 4;
  return [h / 6, s, l];
}

/**
 * 자유 hex 를 지형 위에 곱해도 되는 색으로 누른다.
 * 색상은 보존하고 채도·명도만 대역으로 옮긴다. 무채색(회색·검정·흰색)은 명도만 맞춘다.
 */
export function normaliseNationColor(hex: string): Rgb {
  const rgb = parseHex(hex);
  if (!rgb) return hslToRgb(0, 0, LIGHT_MAX);
  const [h, s, l] = rgbToHsl(rgb);
  const light = LIGHT_MIN + (LIGHT_MAX - LIGHT_MIN) * l;
  return hslToRgb(h, s === 0 ? 0 : SATURATION, light);
}

/**
 * 실제 국가색이 없는 화면(연구용 랩)에서 소속을 구분해 보이려고 쓰는 색.
 * 황금각으로 색상만 돌린다 — 게임 데이터가 아니고, 그렇게 표시한다.
 */
export function indexTint(index: number): Rgb {
  if (index < 0) return { r: 1, g: 1, b: 1 };
  const h = (index * 0.381966) % 1;
  return hslToRgb(h, SATURATION, (LIGHT_MIN + LIGHT_MAX) / 2);
}

/** 곱하기 합성 세기. 1 이면 정규화색 그대로, 0 이면 지형만 남는다. */
export function mixToward(color: Rgb, strength: number): Rgb {
  const k = Math.max(0, Math.min(1, strength));
  return {
    r: 1 + (color.r - 1) * k,
    g: 1 + (color.g - 1) * k,
    b: 1 + (color.b - 1) * k,
  };
}

/**
 * 세력색을 무엇으로 칠할지.
 *   none       — 지형만.
 *   nation     — 縣(owner) 인덱스로 실제 국가색을 찾는다. 게임창·로비가 쓴다.
 *   commandery — 郡(parentOwner) 인덱스. 국가색 표가 없을 때 합성 방식만 보여 주는 랩용이다.
 */
export type TintMode = 'none' | 'nation' | 'commandery';
