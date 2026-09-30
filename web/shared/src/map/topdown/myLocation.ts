// 내 위치 표지(M2-11): 화면 안이면 초상 핀, 밖이면 가장자리 화살표. 이름표보다 위에 그린다.
import type { CellPoint, ScreenPoint, Viewport } from './types';

export type MyLocationState = 'IN_CITY' | 'FIELD' | 'WITH_CORPS' | 'MARCHING';

export interface MyLocation {
  cell: CellPoint;
  state: MyLocationState;
  nationColor: string | null;
  /** Portrait for the pin; null draws the first letter of `name`. */
  portrait: CanvasImageSource | null;
  name: string;
  /** Remaining route cells when marching (drawn dashed under the pin). */
  route?: readonly CellPoint[];
}

export const PIN_RADIUS = 20;
export const PIN_RING = 3;
export const EDGE_MARGIN = 28;

export interface EdgePlacement {
  onScreen: boolean;
  /** Where to draw: the pin tip when on screen, otherwise the arrow centre on the viewport edge. */
  point: ScreenPoint;
  /** Arrow direction in radians (0 = right), pointing from the viewport centre towards the location. */
  angle: number;
}

/**
 * On screen when the pin (tip at `target`, head above it) fits inside the viewport; otherwise the arrow
 * sits where the ray from the viewport centre to `target` leaves the viewport inset by `margin`.
 */
export function placeMyLocation(target: ScreenPoint, viewport: Viewport, margin = EDGE_MARGIN): EdgePlacement {
  const { width, height } = viewport;
  const head = PIN_RADIUS * 2 + PIN_RING * 2;
  const inside = target.x >= PIN_RADIUS && target.x <= width - PIN_RADIUS && target.y >= head && target.y <= height;
  const cx = width / 2;
  const cy = height / 2;
  const dx = target.x - cx;
  const dy = target.y - cy;
  const angle = Math.atan2(dy, dx);
  if (inside) return { onScreen: true, point: target, angle };
  const halfW = Math.max(width / 2 - margin, 1);
  const halfH = Math.max(height / 2 - margin, 1);
  const scale = Math.min(dx === 0 ? Infinity : halfW / Math.abs(dx), dy === 0 ? Infinity : halfH / Math.abs(dy));
  return { onScreen: false, point: { x: cx + dx * scale, y: cy + dy * scale }, angle };
}

/** Screen rectangle that should answer a tap (at least 44 × 44). */
export function myLocationHitRect(placement: EdgePlacement): { x: number; y: number; width: number; height: number } {
  if (placement.onScreen) {
    const size = PIN_RADIUS * 2 + PIN_RING * 2;
    return { x: placement.point.x - size / 2, y: placement.point.y - size - 8, width: size, height: size + 8 };
  }
  return { x: placement.point.x - 22, y: placement.point.y - 22, width: 44, height: 44 };
}

function portraitCircle(ctx: CanvasRenderingContext2D, me: MyLocation, x: number, y: number, radius: number): void {
  ctx.save();
  ctx.beginPath();
  ctx.arc(x, y, radius, 0, Math.PI * 2);
  ctx.closePath();
  ctx.fillStyle = '#1b201d';
  ctx.fill();
  ctx.clip();
  if (me.portrait) {
    ctx.drawImage(me.portrait, x - radius, y - radius, radius * 2, radius * 2);
  } else {
    ctx.fillStyle = '#ece6d8';
    ctx.font = `900 ${Math.round(radius * 1.1)}px 'Noto Serif KR Variable', 'Noto Serif KR', serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText([...me.name][0] ?? '', x, y + 1);
  }
  ctx.restore();
  ctx.beginPath();
  ctx.arc(x, y, radius + PIN_RING / 2, 0, Math.PI * 2);
  ctx.lineWidth = PIN_RING;
  ctx.strokeStyle = me.nationColor ?? '#b9b2a3';
  ctx.stroke();
}

/** Draws the route (dashed), then the pin or the edge arrow. `toScreen` maps cell centres. */
export function drawMyLocation(
  ctx: CanvasRenderingContext2D,
  me: MyLocation,
  toScreen: (cell: CellPoint) => ScreenPoint,
  viewport: Viewport,
): EdgePlacement {
  if (me.route && me.route.length) {
    ctx.save();
    ctx.setLineDash([8, 6]);
    ctx.lineWidth = 3;
    ctx.strokeStyle = me.nationColor ?? '#ece6d8';
    ctx.beginPath();
    const start = toScreen(me.cell);
    ctx.moveTo(start.x, start.y);
    for (const cell of me.route) {
      const p = toScreen(cell);
      ctx.lineTo(p.x, p.y);
    }
    ctx.stroke();
    ctx.restore();
  }
  const placement = placeMyLocation(toScreen(me.cell), viewport);
  const { x, y } = placement.point;
  if (placement.onScreen) {
    const headY = y - PIN_RADIUS - PIN_RING - 8;
    ctx.beginPath();
    ctx.moveTo(x - 7, headY + PIN_RADIUS - 2);
    ctx.lineTo(x, y);
    ctx.lineTo(x + 7, headY + PIN_RADIUS - 2);
    ctx.closePath();
    ctx.fillStyle = me.nationColor ?? '#b9b2a3';
    ctx.fill();
    portraitCircle(ctx, me, x, headY, PIN_RADIUS);
  } else {
    ctx.save();
    ctx.translate(x, y);
    ctx.rotate(placement.angle);
    ctx.beginPath();
    ctx.moveTo(22, 0);
    ctx.lineTo(10, -8);
    ctx.lineTo(10, 8);
    ctx.closePath();
    ctx.fillStyle = me.nationColor ?? '#b9b2a3';
    ctx.fill();
    ctx.restore();
    portraitCircle(ctx, me, x, y, 13);
  }
  return placement;
}
