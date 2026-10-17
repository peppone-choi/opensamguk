import {
  HAN_MAP_SHAPE,
  type Camera,
  type CellPoint,
  type CellRect,
  type MapShape,
  type ScreenPoint,
  type ViewLevel,
  type Viewport,
} from './types';
import { TOPDOWN_PROJECTION, type Projection } from './projection';

/** Resting zoom levels in CSS px per cell (16 = original art 1×, 32 = nearest 2×). */
export const ZOOM_STOPS = [0.5, 1, 2, 4, 8, 16, 32] as const;
export const DEFAULT_ZOOM = 16;
export const MAX_ZOOM = 32;

/** Largest zoom at which the whole map fits in the viewport. */
export function fitZoom(viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): number {
  const extent = projection.extent(shape);
  return Math.min(viewport.width / extent.width, viewport.height / extent.height);
}

/** 화면을 빈 띠 없이 채우는 배율(긴 쪽이 넘친다). 배경 · 썸네일 지도용 첫 맞춤 — 멈춤 자리는 아니다. */
export function coverZoom(viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): number {
  const extent = projection.extent(shape);
  return Math.min(MAX_ZOOM, Math.max(viewport.width / extent.width, viewport.height / extent.height));
}

/** Sorted resting zooms for this viewport: [fit, ...ZOOM_STOPS above fit]. */
export function zoomStops(viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): number[] {
  const fit = fitZoom(viewport, shape, projection);
  // 크기 0 인 뷰포트(숨은 컨테이너)는 맞춤 확대가 0 이 되므로 고정 멈춤 자리만 쓴다.
  if (!Number.isFinite(fit) || fit <= 0) return [...ZOOM_STOPS];
  if (fit > MAX_ZOOM) return [MAX_ZOOM];
  return [fit, ...ZOOM_STOPS.filter((stop) => stop > fit)];
}

/** Stop closest to `zoom` (linear distance); ties go to the larger stop. */
export function nearestStop(zoom: number, stops: readonly number[]): number {
  if (stops.length === 0) throw new Error('nearestStop needs at least one stop');
  let best = stops[0];
  let bestDistance = Math.abs(zoom - best);
  for (const stop of stops) {
    const distance = Math.abs(zoom - stop);
    if (distance < bestDistance || (distance === bestDistance && stop > best)) {
      best = stop;
      bestDistance = distance;
    }
  }
  return best;
}

/** Next stop strictly above (dir 1) or below (dir -1) `zoom`; clamps at the ends. */
export function stepStop(zoom: number, stops: readonly number[], dir: 1 | -1): number {
  if (stops.length === 0) throw new Error('stepStop needs at least one stop');
  const sorted = [...stops].sort((a, b) => a - b);
  // 부동소수 오차로 멈춤 자리 바로 아래에 있을 때 같은 자리로 「한 칸」 가지 않게 한다.
  const epsilon = Math.abs(zoom) * 1e-9;
  if (dir === 1) {
    return sorted.find((stop) => stop > zoom + epsilon) ?? sorted[sorted.length - 1];
  }
  for (let i = sorted.length - 1; i >= 0; i -= 1) {
    if (sorted[i] < zoom - epsilon) return sorted[i];
  }
  return sorted[0];
}

/**
 * Where a wheel burst comes to rest: the stop at or beyond `zoom` in the direction the burst moved, so one notch
 * out of the fit view never snaps back to it (fit 0.127 → 0.19 is nearer fit than 0.5 on a phone-wide window).
 * `dir` 0 (no net change) rests on the nearest stop.
 */
export function restingStop(zoom: number, stops: readonly number[], dir: 1 | -1 | 0): number {
  const near = nearestStop(zoom, stops);
  if (dir === 0 || Math.abs(near - zoom) <= 1e-9 * Math.max(1, zoom)) return near;
  return stepStop(zoom, stops, dir);
}

export function viewLevel(zoom: number): ViewLevel {
  if (zoom < 2) return 'ju';
  if (zoom < 8) return 'commandery';
  return 'county';
}

/** Representative zoom for the [주|군|현] buttons, clamped to this viewport's stop range. */
export function levelZoom(level: ViewLevel, viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): number {
  const stops = zoomStops(viewport, shape, projection);
  const min = stops[0];
  if (level === 'ju') return min;
  const target = level === 'commandery' ? 4 : 16;
  return Math.min(MAX_ZOOM, Math.max(min, target));
}

/** 칸 → 화면. 변환식은 projection.ts 한곳에 있다. */
export function cellToScreen(cell: CellPoint, cam: Camera, viewport: Viewport, projection: Projection = TOPDOWN_PROJECTION): ScreenPoint {
  return projection.cellToScreen(cell, cam, viewport);
}

/** 화면 → 칸. 변환식은 projection.ts 한곳에 있다. */
export function screenToCell(point: ScreenPoint, cam: Camera, viewport: Viewport, projection: Projection = TOPDOWN_PROJECTION): CellPoint {
  return projection.screenToCell(point, cam, viewport);
}

/**
 * 칸 여럿(봉토 현 등 정수 칸)이 모두 들어오는 첫 보기. 가장자리에 `pad`(CSS px)를 두고, 그 안에 드는 가장 큰 멈춤 자리로 내린다
 * — 맞춤(fit)보다 작아지지 않고, 가까이 모여 있어도 현 보기(DEFAULT_ZOOM)보다 당기지 않는다(그림을 키우지 않는다). 칸이 없으면 null.
 */
export function fitCellsView(
  cells: readonly CellPoint[],
  viewport: Viewport,
  pad = 32,
  shape: MapShape = HAN_MAP_SHAPE,
  projection: Projection = TOPDOWN_PROJECTION,
): Camera | null {
  if (cells.length === 0) return null;
  const cols = cells.map((cell) => cell.col);
  const rows = cells.map((cell) => cell.row);
  const minCol = Math.min(...cols);
  const minRow = Math.min(...rows);
  const width = Math.max(...cols) - minCol + 1;
  const height = Math.max(...rows) - minRow + 1;
  const extent = projection.extent({ cols: width, rows: height });
  const room = Math.min((viewport.width - pad * 2) / extent.width, (viewport.height - pad * 2) / extent.height);
  const stops = zoomStops(viewport, shape, projection).filter((stop) => stop <= DEFAULT_ZOOM);
  const zoom = [...stops].reverse().find((stop) => stop <= room) ?? stops[0] ?? DEFAULT_ZOOM;
  return { center: { col: minCol + width / 2, row: minRow + height / 2 }, zoom };
}

/** Clamp a zoom to [smallest stop, MAX_ZOOM] for this viewport. */
export function clampZoom(
  zoom: number,
  viewport: Viewport,
  shape: MapShape = HAN_MAP_SHAPE,
  projection: Projection = TOPDOWN_PROJECTION,
): number {
  const min = zoomStops(viewport, shape, projection)[0];
  return Math.min(MAX_ZOOM, Math.max(min, zoom));
}

/**
 * Zoom to `newZoom` (clamped) keeping the cell under `anchor` under `anchor`.
 * `shape` gives the minimum zoom (fit); it defaults to the product map.
 */
export function zoomAt(
  cam: Camera,
  anchor: ScreenPoint,
  newZoom: number,
  viewport: Viewport,
  shape: MapShape = HAN_MAP_SHAPE,
  projection: Projection = TOPDOWN_PROJECTION,
): Camera {
  const zoom = clampZoom(newZoom, viewport, shape, projection);
  const cell = projection.screenToCell(anchor, cam, viewport);
  const offset = projection.screenDeltaToCell(anchor.x - viewport.width / 2, anchor.y - viewport.height / 2, zoom);
  return {
    zoom,
    center: {
      col: cell.col - offset.col,
      row: cell.row - offset.row,
    },
  };
}

function clampAxis(center: number, cells: number, screenPx: number, zoom: number): number {
  // 맞춤 확대에서는 cells × zoom 이 부동소수로 화면 폭을 아주 조금 넘을 수 있다.
  if (TOPDOWN_PROJECTION.cellLengthToScreen(cells, zoom) <= screenPx + 1e-6) return cells / 2;
  return Math.min(cells, Math.max(0, center));
}

/**
 * Keep the center inside the map; an axis smaller than the viewport is centered.
 * 다이메트릭은 화면 축과 칸 축이 어긋나서 축마다 가운데에 둘 수 없다: 지도 전체가 화면에 들면 지도 가운데, 아니면 가운데 칸을 지도 안으로 자른다.
 */
export function clampCamera(cam: Camera, viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): Camera {
  if (projection.kind !== 'topdown') {
    const extent = projection.extent(shape);
    const fits = extent.width * cam.zoom <= viewport.width + 1e-6 && extent.height * cam.zoom <= viewport.height + 1e-6;
    return {
      zoom: cam.zoom,
      center: fits
        ? { col: shape.cols / 2, row: shape.rows / 2 }
        : { col: Math.min(shape.cols, Math.max(0, cam.center.col)), row: Math.min(shape.rows, Math.max(0, cam.center.row)) },
    };
  }
  return {
    zoom: cam.zoom,
    center: {
      col: clampAxis(cam.center.col, shape.cols, viewport.width, cam.zoom),
      row: clampAxis(cam.center.row, shape.rows, viewport.height, cam.zoom),
    },
  };
}

/**
 * Cells touched by the viewport, clipped to the map (inclusive-exclusive, col0 ≤ col1).
 * 다이메트릭이면 화면 네 귀가 칸 좌표에서 마름모라 그 네 점을 감싸는 칸 사각형이다(탑다운은 왼쪽 위 · 오른쪽 아래 두 귀와 같다).
 */
export function visibleCellRect(cam: Camera, viewport: Viewport, shape: MapShape, projection: Projection = TOPDOWN_PROJECTION): CellRect {
  const clip = (value: number, max: number) => Math.min(max, Math.max(0, value));
  if (projection.kind !== 'topdown') {
    const corners = [
      projection.screenToCell({ x: 0, y: 0 }, cam, viewport),
      projection.screenToCell({ x: viewport.width, y: 0 }, cam, viewport),
      projection.screenToCell({ x: viewport.width, y: viewport.height }, cam, viewport),
      projection.screenToCell({ x: 0, y: viewport.height }, cam, viewport),
    ];
    const cols = corners.map((corner) => corner.col);
    const rows = corners.map((corner) => corner.row);
    const col0 = clip(Math.floor(Math.min(...cols)), shape.cols);
    const row0 = clip(Math.floor(Math.min(...rows)), shape.rows);
    return {
      col0,
      row0,
      col1: Math.max(col0, clip(Math.ceil(Math.max(...cols)), shape.cols)),
      row1: Math.max(row0, clip(Math.ceil(Math.max(...rows)), shape.rows)),
    };
  }
  const topLeft = projection.screenToCell({ x: 0, y: 0 }, cam, viewport);
  const bottomRight = projection.screenToCell({ x: viewport.width, y: viewport.height }, cam, viewport);
  const col0 = clip(Math.floor(topLeft.col), shape.cols);
  const row0 = clip(Math.floor(topLeft.row), shape.rows);
  const col1 = Math.max(col0, clip(Math.ceil(bottomRight.col), shape.cols));
  const row1 = Math.max(row0, clip(Math.ceil(bottomRight.row), shape.rows));
  return { col0, row0, col1, row1 };
}
