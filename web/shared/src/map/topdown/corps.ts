// 부대 표지(K2-08 그리는 쪽): 자리 · 층 · 누를 영역 · 그리기 인터페이스까지.
// 표지가 어떻게 생겼는지(원작 표지 · 깃발 · 선 모양)는 corpsArt.ts 한 곳에서만 정한다.
// 깃발/유닛 · 표식 모양 승인 묶음이 정해지면 그 파일만 바꾼다.
import type { CellPoint, ScreenPoint } from './types';

export type Heading = 'left' | 'right' | 'up' | 'down';

/**
 * 군단 표지 상태(ADR-LITE-049 개정 · 원장 §1 D34, 보드 V31K2CorpsStates): 보임 = 다른 세력의 보이는 군단(병력대),
 * 첩보 = 마지막 목격(흐림 · 점선 · 「?」 · N순 전), 내 군단 = 청동 테두리(정확한 병력 · 경로). 모양은 corpsArt.ts 에 있다.
 */
export type CorpsStanding = 'seen' | 'intel' | 'own';

export interface CorpsMarker {
  id: string;
  cell: CellPoint;
  nationColor: string;
  leaderName: string;
  /** null = 멈춤. */
  heading: Heading | null;
  /** Remaining route cells (drawn as the 「부대 경로」 layer). */
  route?: readonly CellPoint[];
  /** 상태. 없으면 보임. */
  standing?: CorpsStanding;
  /** 병력 띠 글 — 내 군단은 정확한 수(서버 troops), 다른 세력은 병력대(서버 troopsBand.label). 지어내지 않는다. */
  troopsLabel?: string;
  /** 첩보 나이(「2순 전」, 서버 ageTurns). 첩보에만. */
  ageLabel?: string;
}

const STANDING_RANK: Record<CorpsStanding, number> = { intel: 0, seen: 1, own: 2 };

/** 그리는 차례(아래 → 위): 첩보 → 보이는 군단 → 내 군단(그 위에 내 위치 핀). 같은 상태는 들어온 차례 그대로. */
export function corpsDrawOrder<T extends { marker: CorpsMarker }>(items: readonly T[]): T[] {
  return items.map((item, index) => ({ item, index }))
    .sort((a, b) => STANDING_RANK[a.item.marker.standing ?? 'seen'] - STANDING_RANK[b.item.marker.standing ?? 'seen'] || a.index - b.index)
    .map(({ item }) => item);
}

/** 누르기 차례: 겹치면 내 군단 > 보임 > 첩보(모두 城 깃발 위, 내 위치 아래). */
export function corpsHitZ(standing: CorpsStanding | undefined): number {
  return CORPS_HIT_Z + STANDING_RANK[standing ?? 'seen'] / 10;
}

/** 병력 띠 글(현 보기만): 병력, 첩보는 병력 · 나이. 둘 다 없으면 null. */
export function corpsBandText(marker: CorpsMarker): string | null {
  const parts = [marker.troopsLabel, marker.standing === 'intel' ? marker.ageLabel : undefined].filter((part): part is string => !!part);
  return parts.length ? parts.join(' · ') : null;
}

/** 표지 그림이 차지하는 자리: 움직이면 몸통 + 깃발, 멈추면 깃발만. */
export function corpsMarkRect(marker: CorpsMarker, place: CorpsPlacement): Rect {
  return marker.heading ? union(place.body, place.flag) : place.flag;
}

export interface CorpsBand<T> { item: T; text: string; rect: Rect }

/**
 * 병력 띠 자리(현 보기만): 표지 바로 아래 가운데. 내 군단 → 보임 → 첩보 차례로 놓고, 먼저 놓인 띠나 다른 표지와 겹치면 뺀다
 * (못 피한 띠는 첩보 · 보임 순으로 빠진다). 내 군단 띠는 겹쳐도 남긴다.
 */
export function placeCorpsBands<T extends { marker: CorpsMarker; place: CorpsPlacement }>(
  items: readonly T[], measure: (text: string) => { width: number; height: number },
): CorpsBand<T>[] {
  const marks = items.map((item) => corpsMarkRect(item.marker, item.place));
  const order = items.map((item, index) => ({ item, index }))
    .sort((a, b) => STANDING_RANK[b.item.marker.standing ?? 'seen'] - STANDING_RANK[a.item.marker.standing ?? 'seen'] || a.index - b.index);
  const placed: CorpsBand<T>[] = [];
  for (const { item, index } of order) {
    const text = corpsBandText(item.marker);
    if (!text) continue;
    const size = measure(text);
    const mark = marks[index];
    const rect = { x: mark.x + mark.width / 2 - size.width / 2, y: mark.y + mark.height + 2, width: size.width, height: size.height };
    const own = item.marker.standing === 'own';
    const hits = placed.some((band) => overlaps(band.rect, rect)) || marks.some((other, at) => at !== index && overlaps(other, rect));
    if (hits && !own) continue;
    placed.push({ item, text, rect });
  }
  return placed;
}

function overlaps(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.width && b.x < a.x + a.width && a.y < b.y + b.height && b.y < a.y + a.height;
}

export interface Rect { x: number; y: number; width: number; height: number }

/**
 * Overlay order (bottom → top): site · city flag → corps route → corps body → corps flag → labels → my location.
 * Hit z: city flag 1 < corps 5 < my location 10.
 */
export const CORPS_HIT_Z = 5;
/** Touch target: at least 44 × 44 CSS px (K3 rule). */
export const CORPS_MIN_HIT_PX = 44;
export const CORPS_FLAG_PX = 32;

/** Body size on screen: the original 16 px map marker at a whole scale (2× below 48 px/cell, 3× from there). */
export function corpsMarkerSize(zoom: number): number {
  return zoom >= 48 ? 48 : 32;
}

/** Heading from the first route step (4-neighbour dominant axis); null when there is no route. */
export function headingOf(cell: CellPoint, route?: readonly CellPoint[]): Heading | null {
  const next = route?.[0];
  if (!next) return null;
  const dx = next.col - cell.col;
  const dy = next.row - cell.row;
  if (dx === 0 && dy === 0) return null;
  if (Math.abs(dx) >= Math.abs(dy)) return dx > 0 ? 'right' : 'left';
  return dy > 0 ? 'down' : 'up';
}

export interface CorpsPlacement {
  /** Screen point of the cell centre. */
  at: ScreenPoint;
  /** Direction body (drawn only while moving). */
  body: Rect;
  /** Leader flag, planted at the body's upper left. */
  flag: Rect;
  /** Union of body and flag, grown to the minimum touch size around its centre. */
  hit: Rect;
  /** Screen points from the cell through the remaining route; empty when stopped. */
  route: ScreenPoint[];
}

/** Where a marker sits on screen. `toScreen` maps a cell to the screen point of its centre. */
export function corpsPlacement(marker: CorpsMarker, zoom: number, toScreen: (cell: CellPoint) => ScreenPoint): CorpsPlacement {
  const at = toScreen(marker.cell);
  const size = corpsMarkerSize(zoom);
  const body = { x: at.x - size / 2, y: at.y - size / 2, width: size, height: size };
  const flag = { x: body.x - CORPS_FLAG_PX * 0.08, y: body.y - CORPS_FLAG_PX * 0.72, width: CORPS_FLAG_PX, height: CORPS_FLAG_PX };
  const route = marker.route?.length ? [at, ...marker.route.map(toScreen)] : [];
  return { at, body, flag, hit: touchRect(union(body, flag)), route };
}

function union(a: Rect, b: Rect): Rect {
  const x = Math.min(a.x, b.x);
  const y = Math.min(a.y, b.y);
  return { x, y, width: Math.max(a.x + a.width, b.x + b.width) - x, height: Math.max(a.y + a.height, b.y + b.height) - y };
}

function touchRect(rect: Rect): Rect {
  const width = Math.max(CORPS_MIN_HIT_PX, rect.width);
  const height = Math.max(CORPS_MIN_HIT_PX, rect.height);
  return { x: rect.x + (rect.width - width) / 2, y: rect.y + (rect.height - height) / 2, width, height };
}

/**
 * How a marker looks. The renderer only decides where and in which order; every picture choice
 * lives behind this interface. Each call may skip drawing (e.g. its sheet is not loaded yet) —
 * the hit area stays in place either way.
 */
export interface CorpsArt {
  drawRoute(ctx: CanvasRenderingContext2D, marker: CorpsMarker, points: readonly ScreenPoint[]): void;
  /** Called only while moving (`marker.heading` is set). */
  drawBody(ctx: CanvasRenderingContext2D, marker: CorpsMarker & { heading: Heading }, rect: Rect): void;
  drawFlag(ctx: CanvasRenderingContext2D, marker: CorpsMarker, rect: Rect): void;
  /** 상태 표(내 군단 청동 테두리 · 첩보 점선 + 「?」). `rect` = 표지 그림 자리(`corpsMarkRect`). 보임은 아무것도 안 그린다. */
  drawStanding(ctx: CanvasRenderingContext2D, marker: CorpsMarker, rect: Rect): void;
  /** 병력 띠(현 보기만, 자리는 `placeCorpsBands`). */
  drawBand(ctx: CanvasRenderingContext2D, marker: CorpsMarker, text: string, rect: Rect): void;
}
