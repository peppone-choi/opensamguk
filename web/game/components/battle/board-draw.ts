// 전투 판 그리기(BattleBoardCanvas 몫을 뗀 것) — 판 그림 · 배치 구역 · 내 부곡 · 묶음 깃발 · 끄는 사각형 · 두 점 고르기 첫 점.
// 그린 화면 좌표(칸 · 부곡 · 묶음)와 view 를 돌려준다 — 누르기 판단 · e2e data-* 가 같은 값을 쓴다.
import { cellPolygon, centerView, fitView, pieceOrigin, type BattleKit, type BoardPicture, type BoardView } from '@opensamguk/ui/battle';
import type { Cell } from '@/lib/battle/protocol';
import { CLUSTER_RADIUS, clusterMarks, type MarkCluster, type ScreenMark, type ScreenPoint } from '@/lib/battle/board-select';

/** 보드 그림이 쓴 판 그림 너비(boards_v31_k6 MAP battle_field — 원작 판을 절반으로 줄인 1024). 보드 배율을 렌더러 배율로 바꿀 때 쓴다. */
const BOARD_ART_WIDTH = 1024;

/** 판 배율 — 보드 배율(절반 크기 그림 기준, D29 배치) · 렌더러 배율(원본 기준, 실시간 「원작 2배」 = 2) · 판 전체 맞춤(「전체」). */
export type BoardScale = { readonly kind: 'board'; readonly value: number } | { readonly kind: 'renderer'; readonly value: number } | { readonly kind: 'fit' };

/** 판에 그릴 내 부곡 하나 — 칸 · 차례 번호 · (AUTHORITY 가 온 경우) AI 표지. */
export interface BoardUnitMark {
    readonly id: string;
    readonly cell: Cell;
    readonly index: number;
    readonly ai?: boolean;
    /** 장수 차례(1부터, 목록 「장수 n」) — 축소했을 때 묶음 깃발 글자. */
    readonly group?: number;
}

export interface BoardDrawInput {
    readonly assets: { readonly kit: BattleKit; readonly picture: BoardPicture };
    readonly width: number;
    readonly height: number;
    readonly units: readonly BoardUnitMark[];
    readonly allowedCells: readonly Cell[];
    readonly selectedIds: ReadonlySet<string>;
    readonly scale: BoardScale;
    /** 실시간 판 — 가운데를 따로 두고(center, 없으면 내 부곡 가운데), clusterBelow 보다 작게 보면 묶는다. */
    readonly isLive: boolean;
    readonly center: ScreenPoint | null;
    readonly clusterBelow: number;
    readonly band: { readonly a: ScreenPoint; readonly b: ScreenPoint } | null;
    readonly anchor: ScreenPoint | null;
}

export interface BoardDrawResult {
    readonly view: BoardView;
    /** 이번에 쓴 판 가운데(판 그림 좌표) — 판 움직이기의 출발점. */
    readonly point: ScreenPoint;
    readonly marks: ScreenMark[];
    /** 둘 이상 묶인 것만. */
    readonly clusters: MarkCluster[];
    /** 내 부곡 칸 · 배치 구역 칸의 화면 좌표(e2e 가 누를 곳). */
    readonly cells: Record<string, ScreenPoint>;
}

export function drawBattleBoard(ctx: CanvasRenderingContext2D, input: BoardDrawInput): BoardDrawResult {
    const { assets, width, height, units, allowedCells, selectedIds, scale, isLive, center, clusterBelow, band, anchor } = input;
    ctx.fillStyle = '#0c0f0e';
    ctx.fillRect(0, 0, width, height);
    // 배치: 고른 부곡(없으면 배치 구역 · 내 부곡 가운데)을 화면 가운데로. 실시간: 따로 둔 가운데(처음은 내 부곡 가운데). 「전체」는 판 전체를 맞춘다.
    const focus = (isLive ? null : units.find((u) => selectedIds.has(u.id))?.cell) ?? centreOf(allowedCells) ?? centreOf(units.map((u) => u.cell)) ?? { row: 32, col: 32 };
    const o = pieceOrigin(assets.kit.layout, focus.row, focus.col, 0);
    const point = (isLive ? center : null) ?? { x: o.x + 16, y: o.y + 8 };
    const view = scale.kind === 'fit'
        ? fitView(assets.picture.board, { width, height })
        // 보드 배율(절반 크기 그림 기준) → 렌더러 배율(원본 그림 기준).
        : centerView(point, { width, height }, scale.kind === 'board' ? scale.value * (BOARD_ART_WIDTH / assets.picture.board.width) : scale.value);
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(assets.picture.canvas, view.offsetX, view.offsetY, assets.picture.board.width * view.scale, assets.picture.board.height * view.scale);
    const poly = (c: Cell) => cellPolygon(assets.kit, assets.picture.board, c.row, c.col, view);
    const path = (pts: { x: number; y: number }[]) => {
        ctx.beginPath();
        pts.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
        ctx.closePath();
    };
    // 배치 구역 — 초록 점선.
    ctx.setLineDash([6, 4]);
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = '#6f9b7c';
    ctx.fillStyle = 'rgba(111,155,124,.14)';
    for (const c of allowedCells) {
        path(poly(c));
        ctx.fill();
        ctx.stroke();
    }
    ctx.setLineDash([]);
    // 내 부곡 — 칸 마름모 + 차례 번호(고른 부곡은 노랑). 실시간에서 축소했으면 같은 장수의 가까이 모인 부곡은 깃발 하나 + 숫자로 묶는다.
    const cells: Record<string, { x: number; y: number }> = {};
    const midOf = (c: Cell) => {
        const pts = poly(c);
        return { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
    };
    const marks: ScreenMark[] = units.map((u) => ({ id: u.id, group: u.group ?? 0, ...midOf(u.cell) }));
    const clusters = isLive && view.scale < clusterBelow ? clusterMarks(marks, CLUSTER_RADIUS).filter((c) => c.ids.length > 1) : [];
    const clustered = new Set(clusters.flatMap((c) => c.ids));
    for (const u of units) {
        const pts = poly(u.cell);
        cells[`${u.cell.row}:${u.cell.col}`] = { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
        if (clustered.has(u.id)) continue;
        path(pts);
        const on = selectedIds.has(u.id);
        ctx.fillStyle = on ? 'rgba(255,211,109,.55)' : 'rgba(211,176,100,.35)';
        ctx.fill();
        ctx.lineWidth = on ? 2.5 : 1.5;
        ctx.strokeStyle = on ? '#ffd36d' : '#d3b064';
        ctx.stroke();
        ctx.fillStyle = '#ece6d8';
        ctx.font = `700 ${Math.max(10, Math.round(9 * view.scale))}px sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';
        const mid = { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
        ctx.fillText(u.ai ? `${u.index}·AI` : String(u.index), mid.x, mid.y);
    }
    for (const c of clusters) drawFlag(ctx, c, c.ids.every((id) => selectedIds.has(id)));
    // 끄는 사각형 · 두 점 고르기 첫 점.
    if (band) {
        ctx.setLineDash([5, 3]);
        ctx.lineWidth = 1.5;
        ctx.strokeStyle = '#ffd36d';
        ctx.fillStyle = 'rgba(255,211,109,.12)';
        const x = Math.min(band.a.x, band.b.x);
        const y = Math.min(band.a.y, band.b.y);
        ctx.fillRect(x, y, Math.abs(band.b.x - band.a.x), Math.abs(band.b.y - band.a.y));
        ctx.strokeRect(x, y, Math.abs(band.b.x - band.a.x), Math.abs(band.b.y - band.a.y));
        ctx.setLineDash([]);
    }
    if (anchor) {
        ctx.lineWidth = 2;
        ctx.strokeStyle = '#ffd36d';
        ctx.beginPath();
        ctx.arc(anchor.x, anchor.y, 7, 0, Math.PI * 2);
        ctx.moveTo(anchor.x - 12, anchor.y);
        ctx.lineTo(anchor.x + 12, anchor.y);
        ctx.moveTo(anchor.x, anchor.y - 12);
        ctx.lineTo(anchor.x, anchor.y + 12);
        ctx.stroke();
    }
    // e2e 가 누를 곳 — 내 부곡 칸과 배치 구역 칸의 화면 좌표.
    for (const c of allowedCells) {
        const pts = poly(c);
        cells[`${c.row}:${c.col}`] ??= { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
    }
    // e2e 가 누를 곳 — 내 부곡 칸과 배치 구역 칸의 화면 좌표.
    for (const c of allowedCells) {
        const pts = poly(c);
        cells[`${c.row}:${c.col}`] ??= { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
    }
    return { view, point, marks, clusters, cells };
}

/** 묶음 깃발 — 깃대 + 금빛 깃발(장수 차례) + 수 배지. 묶음 부곡이 모두 골라졌으면 노란 테. 세력색은 서버가 아직 주지 않는다. */
function drawFlag(ctx: CanvasRenderingContext2D, c: MarkCluster, on: boolean) {
    const { x, y } = c;
    ctx.lineWidth = 2;
    ctx.strokeStyle = '#1b201d';
    ctx.beginPath();
    ctx.moveTo(x - 11, y - 18);
    ctx.lineTo(x - 11, y + 10);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(x - 10, y - 17);
    ctx.lineTo(x + 13, y - 17);
    ctx.lineTo(x + 7, y - 10);
    ctx.lineTo(x + 13, y - 3);
    ctx.lineTo(x - 10, y - 3);
    ctx.closePath();
    ctx.fillStyle = '#d3b064';
    ctx.fill();
    ctx.lineWidth = on ? 2 : 1;
    ctx.strokeStyle = on ? '#ffd36d' : '#0c0f0e';
    ctx.stroke();
    ctx.font = '900 11px sans-serif';
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillStyle = '#161410';
    ctx.fillText(String(c.group), x + 1, y - 10);
    const label = String(c.ids.length);
    const w = Math.max(16, 7 * label.length + 6);
    ctx.fillStyle = on ? '#ffd36d' : '#ece6d8';
    ctx.fillRect(x + 10, y - 24, w, 15);
    ctx.font = '700 10px sans-serif';
    ctx.fillStyle = '#161410';
    ctx.fillText(label, x + 10 + w / 2, y - 16.5);
}

function centreOf(cells: readonly Cell[]): Cell | null {
    if (cells.length === 0) return null;
    const r = cells.reduce((s, c) => s + c.row, 0) / cells.length;
    const c = cells.reduce((s, x) => s + x.col, 0) / cells.length;
    return { row: Math.round(r), col: Math.round(c) };
}
