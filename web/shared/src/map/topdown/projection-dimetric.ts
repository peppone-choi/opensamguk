// 2:1 다이메트릭 투영(B4, 기능 플래그 뒤 시범). 칸 좌표 · 이동 · 판정 계약은 그대로이고 칸 → 화면 그리기만 바꾼다.
// 칸 하나는 가로 2·zoom, 세로 zoom 인 마름모다. 칸 (c, r) 의 위 꼭짓점이 (c − r, (c + r) / 2) · zoom 자리이고
// 열이 늘면 오른쪽 아래, 행이 늘면 왼쪽 아래로 간다. 그래서 화면에서 더 아래(앞)에 있는 칸일수록 c + r 이 크다.
import { TOPDOWN_PROJECTION, type Projection } from './projection';
import type { Camera, CellPoint, MapShape, ScreenPoint, Viewport } from './types';

export const DIMETRIC_PROJECTION: Projection = Object.freeze({
  kind: 'dimetric' as const,
  cellToScreen(cell: CellPoint, cam: Camera, viewport: Viewport): ScreenPoint {
    const dc = cell.col - cam.center.col;
    const dr = cell.row - cam.center.row;
    return {
      x: (dc - dr) * cam.zoom + viewport.width / 2,
      y: (dc + dr) * (cam.zoom / 2) + viewport.height / 2,
    };
  },
  screenToCell(point: ScreenPoint, cam: Camera, viewport: Viewport): CellPoint {
    // 셰이더 식(glslScreenToCell)과 같은 연산 순서: d = (화면 − 가운데) / zoom, 칸 = 가운데 + (0.5·d.x + d.y, d.y − 0.5·d.x)
    const dx = (point.x - viewport.width / 2) / cam.zoom;
    const dy = (point.y - viewport.height / 2) / cam.zoom;
    return {
      col: cam.center.col + (0.5 * dx + dy),
      row: cam.center.row + (dy - 0.5 * dx),
    };
  },
  screenDeltaToCell(dx: number, dy: number, zoom: number): CellPoint {
    const x = dx / zoom;
    const y = dy / zoom;
    return { col: 0.5 * x + y, row: y - 0.5 * x };
  },
  // 길이는 마름모의 세로(칸 하나 = zoom)로 잰다. 가로는 그 두 배다.
  cellLengthToScreen(cells: number, zoom: number): number {
    return cells * zoom;
  },
  screenLengthToCells(px: number, zoom: number): number {
    return px / zoom;
  },
  extent(shape: MapShape): { width: number; height: number } {
    return { width: shape.cols + shape.rows, height: (shape.cols + shape.rows) / 2 };
  },
  glslScreenToCell(screen: string, center: string, viewport: string, zoom: string): string {
    // GLSL mat2 는 열 우선: mat2(a, b, c, d) · v = (a·v.x + c·v.y, b·v.x + d·v.y)
    return `${center} + mat2(0.5, -0.5, 1.0, 1.0) * ((${screen} - ${viewport} * 0.5) / ${zoom})`;
  },
});

/** 빌드 플래그 NEXT_PUBLIC_STRATEGIC_PROJECTION 값 → 투영. 'dimetric' 만 2:1 이고 빈 값 · 그 밖의 값은 모두 탑다운이다. */
export function strategicProjection(flag: string | undefined | null): Projection {
  return flag?.trim() === 'dimetric' ? DIMETRIC_PROJECTION : TOPDOWN_PROJECTION;
}

/** 다이메트릭 깊이: 화면에서 앞(아래)일수록 크다. 같은 깊이는 열이 작은 쪽(왼쪽)을 먼저 그린다. */
export function dimetricDepth(cell: CellPoint): number {
  return cell.col + cell.row;
}

/**
 * 뒤 → 앞 차례로 고쳐 준다(같은 깊이는 원래 차례). 탑다운이면 원래 배열을 그대로 돌려준다 — OFF 화면은 그리는 차례도 같다.
 */
export function depthOrder<T>(projection: Projection, items: readonly T[], cellOf: (item: T) => CellPoint): readonly T[] {
  if (projection.kind !== 'dimetric') return items;
  return items.map((item, index) => ({ item, index, depth: dimetricDepth(cellOf(item)) }))
    .sort((a, b) => a.depth - b.depth || a.index - b.index)
    .map(({ item }) => item);
}

/** 칸 덩이 [col, col + span) × [row, row + span) 의 화면 네 꼭짓점: 위 · 오른쪽 · 아래 · 왼쪽(다이메트릭), 탑다운이면 사각형 네 귀. */
export function cellQuad(projection: Projection, col: number, row: number, span: number, cam: Camera, viewport: Viewport): ScreenPoint[] {
  return [
    projection.cellToScreen({ col, row }, cam, viewport),
    projection.cellToScreen({ col: col + span, row }, cam, viewport),
    projection.cellToScreen({ col: col + span, row: row + span }, cam, viewport),
    projection.cellToScreen({ col, row: row + span }, cam, viewport),
  ];
}
