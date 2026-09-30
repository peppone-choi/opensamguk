// 부대 표지(K2-08 그리는 쪽): 자리 · 층 · 누를 영역 · 그리기 인터페이스까지.
// 표지가 어떻게 생겼는지(원작 표지 · 깃발 · 선 모양)는 corpsArt.ts 한 곳에서만 정한다.
// 깃발/유닛 · 표식 모양 승인 묶음이 정해지면 그 파일만 바꾼다.
import type { CellPoint, ScreenPoint } from './types';

export type Heading = 'left' | 'right' | 'up' | 'down';

export interface CorpsMarker {
  id: string;
  cell: CellPoint;
  nationColor: string;
  leaderName: string;
  /** null = 멈춤. */
  heading: Heading | null;
  /** Remaining route cells (drawn as the 「부대 경로」 layer). */
  route?: readonly CellPoint[];
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
}
