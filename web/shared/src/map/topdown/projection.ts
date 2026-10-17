// 칸 ↔ 화면 변환 한곳(A3). 카메라 · 누르기 · 입력 · 이름표 · 작은 지도 · 지형 셰이더가 모두 이것만 쓴다.
// 지금은 탑다운(칸 하나 = zoom CSS px 정사각형) 하나뿐이다. 식의 연산 순서는 옮기기 전과 같다 — 화면 · 누르기 결과가 비트 단위로 같아야 한다.
import type { Camera, CellPoint, MapShape, ScreenPoint, Viewport } from './types';

export interface Projection {
  readonly kind: 'topdown';
  /** 칸 좌표(연속) → 지도 상자 기준 CSS px. */
  cellToScreen(cell: CellPoint, cam: Camera, viewport: Viewport): ScreenPoint;
  /** 지도 상자 기준 CSS px → 칸 좌표(연속). cellToScreen 의 역. */
  screenToCell(point: ScreenPoint, cam: Camera, viewport: Viewport): CellPoint;
  /** 화면 이동(CSS px) → 칸 이동. 위치가 아니라 차이라서 카메라 가운데 · 화면 크기와 상관없다. */
  screenDeltaToCell(dx: number, dy: number, zoom: number): CellPoint;
  /** 칸 길이 → 화면 길이(CSS px). */
  cellLengthToScreen(cells: number, zoom: number): number;
  /** 화면 길이(CSS px) → 칸 길이. */
  screenLengthToCells(px: number, zoom: number): number;
  /** cols × rows 칸 덩이가 zoom 1 에서 차지하는 화면 크기(CSS px). 맞춤 배율 계산용. */
  extent(shape: MapShape): { width: number; height: number };
  /**
   * 셰이더용 screenToCell 식. 인자는 GLSL 식 이름(화면 CSS px vec2 · 가운데 vec2 · 화면 크기 vec2 · 배율 float).
   * 셰이더 원문이 옮기기 전과 글자까지 같도록 식을 그대로 돌려준다.
   */
  glslScreenToCell(screen: string, center: string, viewport: string, zoom: string): string;
}

export const TOPDOWN_PROJECTION: Projection = Object.freeze({
  kind: 'topdown' as const,
  cellToScreen(cell: CellPoint, cam: Camera, viewport: Viewport): ScreenPoint {
    return {
      x: (cell.col - cam.center.col) * cam.zoom + viewport.width / 2,
      y: (cell.row - cam.center.row) * cam.zoom + viewport.height / 2,
    };
  },
  screenToCell(point: ScreenPoint, cam: Camera, viewport: Viewport): CellPoint {
    return {
      col: (point.x - viewport.width / 2) / cam.zoom + cam.center.col,
      row: (point.y - viewport.height / 2) / cam.zoom + cam.center.row,
    };
  },
  screenDeltaToCell(dx: number, dy: number, zoom: number): CellPoint {
    return { col: dx / zoom, row: dy / zoom };
  },
  cellLengthToScreen(cells: number, zoom: number): number {
    return cells * zoom;
  },
  screenLengthToCells(px: number, zoom: number): number {
    return px / zoom;
  },
  extent(shape: MapShape): { width: number; height: number } {
    return { width: shape.cols, height: shape.rows };
  },
  glslScreenToCell(screen: string, center: string, viewport: string, zoom: string): string {
    return `${center} + (${screen} - ${viewport} * 0.5) / ${zoom}`;
  },
});
