// 「보급선」 층 그리기(작전실, 계약판 K4-06): 城 발자국 가운데를 잇는 곧은 선.
// 길 궤적(edgeId)은 계약판 K2-06 대기라, 보드 00c 개략도처럼 城 사이를 곧게 잇는다.
// 모양은 보드 00c(boards_core mapsvg supply · 범례): 이어짐 = 토큰 --moss-2 실선 2.5, 끊김 = 토큰 --rust-2 점선(6 5) 2.5 + 가운데 ×(±7, 굵기 3).

export interface SupplyMapLine {
  readonly fromCityId: number;
  readonly toCityId: number;
  readonly state: 'OPEN' | 'CUT';
}

interface Point { readonly x: number; readonly y: number }

export interface SupplySegment {
  readonly from: Point;
  readonly to: Point;
  readonly cut: boolean;
}

export const SUPPLY_STYLE = Object.freeze({ width: 2.5, dash: [6, 5] as readonly number[], crossHalf: 7, crossWidth: 3 });

/** 보드 범례 값 — 토큰을 못 읽을 때만 쓴다(tokens.css 와 같은 값). */
export const SUPPLY_TOKENS = Object.freeze({ open: ['--moss-2', '#8fa77a'], cut: ['--rust-2', '#e08a7c'] } as const);

/**
 * 선 → 화면 선분. 끝 城을 모르면(장소 표에 없음) 그 선은 뺀다. 두 끝을 담은 상자가 화면(+ 여백) 밖이면 뺀다.
 */
export function supplySegments(
  lines: readonly SupplyMapLine[],
  centerOf: (cityId: number) => { col: number; row: number } | null,
  toScreen: (cell: { col: number; row: number }) => Point,
  view: { width: number; height: number },
  margin = 16,
): SupplySegment[] {
  const out: SupplySegment[] = [];
  for (const line of lines) {
    const a = centerOf(line.fromCityId);
    const b = centerOf(line.toCityId);
    if (!a || !b) continue;
    const from = toScreen(a);
    const to = toScreen(b);
    if (Math.max(from.x, to.x) < -margin || Math.min(from.x, to.x) > view.width + margin
      || Math.max(from.y, to.y) < -margin || Math.min(from.y, to.y) > view.height + margin) continue;
    out.push({ from, to, cut: line.state === 'CUT' });
  }
  return out;
}

/** 이어진 선을 먼저, 끊긴 선(점선 + 가운데 ×)을 위에 그린다. */
export function drawSupply(ctx: CanvasRenderingContext2D, segments: readonly SupplySegment[], colors: { open: string; cut: string }): void {
  if (!segments.length) return;
  ctx.save();
  ctx.lineWidth = SUPPLY_STYLE.width;
  ctx.lineCap = 'butt';
  for (const cut of [false, true]) {
    ctx.strokeStyle = cut ? colors.cut : colors.open;
    ctx.setLineDash(cut ? [...SUPPLY_STYLE.dash] : []);
    ctx.beginPath();
    for (const segment of segments) {
      if (segment.cut !== cut) continue;
      ctx.moveTo(segment.from.x, segment.from.y);
      ctx.lineTo(segment.to.x, segment.to.y);
    }
    ctx.stroke();
  }
  ctx.setLineDash([]);
  ctx.lineWidth = SUPPLY_STYLE.crossWidth;
  ctx.strokeStyle = colors.cut;
  ctx.beginPath();
  const h = SUPPLY_STYLE.crossHalf;
  for (const segment of segments) {
    if (!segment.cut) continue;
    const x = (segment.from.x + segment.to.x) / 2;
    const y = (segment.from.y + segment.to.y) / 2;
    ctx.moveTo(x - h, y - h); ctx.lineTo(x + h, y + h);
    ctx.moveTo(x + h, y - h); ctx.lineTo(x - h, y + h);
  }
  ctx.stroke();
  ctx.restore();
}

/** 토큰 이름의 값(문서 뿌리 계산 스타일). 못 읽으면 같은 값의 기본색. */
export function tokenColor([name, fallback]: readonly [string, string]): string {
  if (typeof document === 'undefined') return fallback;
  const value = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return value || fallback;
}
