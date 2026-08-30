export interface IsoView {
  scale: number;
  ox: number;
  oy: number;
}

export interface GridSize {
  cols: number;
  rows: number;
}

export interface PointerPosition {
  x: number;
  y: number;
}

export function pinchGesture(
  previous: readonly [PointerPosition, PointerPosition],
  current: readonly [PointerPosition, PointerPosition],
): { factor: number; anchor: PointerPosition } {
  const previousDistance = Math.hypot(
    previous[1].x - previous[0].x,
    previous[1].y - previous[0].y,
  );
  const currentDistance = Math.hypot(
    current[1].x - current[0].x,
    current[1].y - current[0].y,
  );
  return {
    factor: previousDistance === 0 ? 1 : currentDistance / previousDistance,
    anchor: {
      x: (current[0].x + current[1].x) / 2,
      y: (current[0].y + current[1].y) / 2,
    },
  };
}

export function cellToScreen(col: number, row: number, view: IsoView): [number, number] {
  return [
    (col - row) * view.scale + view.ox,
    (col + row) * 0.5 * view.scale + view.oy,
  ];
}

export function screenToCell(sx: number, sy: number, view: IsoView): [number, number] {
  const ix = (sx - view.ox) / view.scale;
  const iy = (sy - view.oy) / view.scale;
  return [(ix + 2 * iy) / 2, (2 * iy - ix) / 2];
}

export function visibleCells(width: number, height: number, view: IsoView, grid: GridSize) {
  const corners: [number, number][] = [
    screenToCell(0, 0, view),
    screenToCell(width, 0, view),
    screenToCell(0, height, view),
    screenToCell(width, height, view),
  ];
  const cols = corners.map((cell) => cell[0]);
  const rows = corners.map((cell) => cell[1]);
  return {
    col0: Math.max(0, Math.floor(Math.min(...cols)) - 1),
    col1: Math.min(grid.cols - 1, Math.ceil(Math.max(...cols)) + 1),
    row0: Math.max(0, Math.floor(Math.min(...rows)) - 1),
    row1: Math.min(grid.rows - 1, Math.ceil(Math.max(...rows)) + 1),
  };
}

export function fitScale(width: number, height: number, grid: GridSize): number {
  const spanX = grid.cols + grid.rows;
  const spanY = spanX / 2;
  return Math.min(width / spanX, height / spanY);
}

export const MAX_CSS_SCALE = 32;
export const MAX_SCALE = MAX_CSS_SCALE;

export function effectiveDpr(dpr: number): number {
  return Number.isFinite(dpr) && dpr > 0 ? dpr : 1;
}

export function maxScaleForDpr(dpr: number): number {
  return MAX_CSS_SCALE * effectiveDpr(dpr);
}

export function zoomAt(
  view: IsoView,
  sx: number,
  sy: number,
  factor: number,
  min: number,
  max = MAX_CSS_SCALE,
): IsoView {
  const scale = Math.min(max, Math.max(min, view.scale * factor));
  const ratio = scale / view.scale;
  return {
    scale,
    ox: sx - (sx - view.ox) * ratio,
    oy: sy - (sy - view.oy) * ratio,
  };
}

export function clampView(
  view: IsoView,
  width: number,
  height: number,
  grid: GridSize,
): IsoView {
  const [col, row] = screenToCell(width / 2, height / 2, view);
  if (col >= 0 && col <= grid.cols - 1 && row >= 0 && row <= grid.rows - 1) return view;
  return viewAt(
    width,
    height,
    Math.min(grid.cols - 1, Math.max(0, col)),
    Math.min(grid.rows - 1, Math.max(0, row)),
    view.scale,
  );
}

export function centeredView(width: number, height: number, grid: GridSize): IsoView {
  return viewAt(
    width,
    height,
    (grid.cols - 1) / 2,
    (grid.rows - 1) / 2,
    fitScale(width, height, grid),
  );
}

export function viewAt(
  width: number,
  height: number,
  col: number,
  row: number,
  scale: number,
): IsoView {
  const [cx, cy] = cellToScreen(col, row, { scale, ox: 0, oy: 0 });
  return { scale, ox: width / 2 - cx, oy: height / 2 - cy };
}

export function scaleForSpan(width: number, height: number, span: number, max = MAX_CSS_SCALE): number {
  return Math.min(max, Math.min(width / (2 * span), height / span));
}

export function junSpanCells(juns: { col: number; row: number }[]): number {
  if (juns.length < 2) return 24;
  const nearest: number[] = [];
  for (let i = 0; i < juns.length; i += 1) {
    let best = Infinity;
    for (let j = 0; j < juns.length; j += 1) {
      if (i === j) continue;
      const dx = juns[i].col - juns[j].col;
      const dy = juns[i].row - juns[j].row;
      best = Math.min(best, dx * dx + dy * dy);
    }
    if (best < Infinity) nearest.push(Math.sqrt(best));
  }
  nearest.sort((a, b) => a - b);
  return nearest[nearest.length >> 1];
}
