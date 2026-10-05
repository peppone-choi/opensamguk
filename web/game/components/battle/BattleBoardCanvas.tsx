'use client';

// 전투 판(아이소) — 배치 화면(P-C03) · 실시간 전투(P-C05)용. 그림은 정본 전투 키트(public/battle/waryong/<kit>, 원작 214판)와 공용 렌더러(@opensamguk/ui/battle).
// - 판 번호는 SNAPSHOT field.boardId(= 티켓 battlefieldId 0..213, C2 #9). 서버 지형 해시(terrainInputSha256)가 키트 판의 terrainSha256 과
//   다르면 추측해서 그리지 않는다(C2 #9).
// - 배치 구역(allowedCells)은 초록 점선 마름모, 고른 칸은 노란 마름모(보드 V31K6v2BattleJoin).
// - 내 부곡은 칸 마름모 + 장수 안 차례 번호로 그린다. 병종 그림 · 세력색은 서버가 아직 주지 않아(C2 v2 답 #2) 지어내지 않는다.
// - 누르기: 고른 부곡이 있으면 어느 칸이든 그 칸 누름(내 부곡 칸이면 부른 쪽이 맞바꾸기 — 보드 「내 부곡이 있는 칸이면 둘을 맞바꾼다」).
//   고른 부곡이 없을 때만 내 부곡 칸이 그 부곡 고르기다(boardTap). 다른 부곡으로 바꿔 고르기는 목록에서. 끌기는 없다(보드 「끌기는 없다」).
//   실시간(배치 구역 없음)은 내 부곡 칸 누름이 그 부곡 고르기 · 풀기다(여럿 고르기).
// - 실시간 판 조작(live, 보드 V31K6v2BattleLiveMany · MBattleLive, D24 세부 1 · 2 — 계산은 lib/battle/board-select):
//   데스크톱은 마우스로 판을 끌어 사각형 안 내 부곡을 고른다. 터치 끌기는 판 움직이기다. 「판에서 고르기」(rectMode)를 켜면 두 점이 사각형이다.
//   렌더러 배율이 clusterBelow 보다 작으면 같은 장수의 가까이 모인 부곡을 깃발 하나 + 숫자로 묶고, 묶음을 누르면 그 자리로 다가간다.
//   실시간 판은 고르기로 화면이 튀지 않게 가운데를 따로 둔다(처음은 내 부곡 가운데, 그 뒤는 끌기 · 묶음 누르기로만 옮긴다).
// - e2e 는 data-cells(판 위 칸 → 화면 좌표) · data-clusters(묶음) · data-view(판 위치)로 누를 곳 · 움직임을 본다. 캔버스가 없는 환경(jsdom)에서는 그리지 않고 목록 · 패널만 쓴다.
import { useEffect, useRef, useState } from 'react';
import {
    boardPicture, cellPolygon, centerView, fitView, loadBattleKit, pickCell, pieceOrigin, screenToCell,
    type BattleKit, type BoardPicture, type BoardView,
} from '@opensamguk/ui/battle';
import type { Cell } from '@/lib/battle/protocol';
import { boardTap } from '@/lib/battle/join-view';
import {
    CLUSTER_RADIUS, clusterMarks, idsInRect, isDrag, panCenter, toPicture, type MarkCluster, type ScreenMark, type ScreenPoint,
} from '@/lib/battle/board-select';

/** 정본 전투 키트 — 원작 214판 export(파일별 SHA 는 export.json). */
export const BATTLE_KIT_URL = '/battle/waryong/2c8a1a5';
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

/** 실시간 판 조작 — 배치 화면은 넘기지 않는다(누르기만). */
export interface LiveBoardInput {
    /** 「판에서 고르기」 — 켜져 있으면 누르기 두 번이 사각형의 두 점이다. */
    readonly rectMode: boolean;
    /** 첫 점을 찍었는지(알림 줄 「두 번째 점」). */
    readonly onRectAnchor: (set: boolean) => void;
    /** 사각형 안 내 부곡(없으면 빈 배열). */
    readonly onPickRect: (ids: string[]) => void;
    /** 렌더러 배율이 이 값보다 작으면 묶는다. 묶음을 누르면 그 자리에서 이 배율로 다가간다(onZoomTo). */
    readonly clusterBelow: number;
    readonly onZoomTo: (scale: number) => void;
}

export interface BattleBoardCanvasProps {
    readonly boardId: number;
    /** SNAPSHOT field.terrainInputSha256 — 있으면 키트 판 해시와 대조한다. */
    readonly terrainInputSha256: string | null;
    readonly units: readonly BoardUnitMark[];
    readonly allowedCells: readonly Cell[];
    /** 고른 부곡(배치는 하나, 실시간은 여럿). 첫 고른 부곡을 화면 가운데로. */
    readonly selectedIds: ReadonlySet<string>;
    readonly scale: BoardScale;
    readonly onPickUnit: (id: string) => void;
    readonly onPickCell: (cell: Cell) => void;
    readonly label: string;
    readonly live?: LiveBoardInput;
}

interface PointerDrag {
    readonly id: number;
    readonly start: ScreenPoint;
    readonly kind: 'pan' | 'rect' | 'tap';
    readonly startCenter: ScreenPoint | null;
    moved: boolean;
}

export function BattleBoardCanvas({ boardId, terrainInputSha256, units, allowedCells, selectedIds, scale, onPickUnit, onPickCell, label, live }: BattleBoardCanvasProps) {
    const boxRef = useRef<HTMLDivElement>(null);
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const viewRef = useRef<BoardView | null>(null);
    // 실시간 판 — 가운데(판 그림 좌표, null 이면 내 부곡 가운데) · 끄는 사각형 · 두 점 고르기 첫 점 · 그린 부곡 · 묶음 화면 좌표.
    const [center, setCenter] = useState<ScreenPoint | null>(null);
    const [band, setBand] = useState<{ a: ScreenPoint; b: ScreenPoint } | null>(null);
    const [anchor, setAnchor] = useState<ScreenPoint | null>(null);
    const centerRef = useRef<ScreenPoint | null>(null);
    const marksRef = useRef<ScreenMark[]>([]);
    const clustersRef = useRef<MarkCluster[]>([]);
    const dragRef = useRef<PointerDrag | null>(null);
    const isLive = live != null;
    const clusterBelow = live?.clusterBelow ?? 0;
    const rectMode = live?.rectMode ?? false;

    useEffect(() => {
        if (!rectMode) setAnchor(null);
    }, [rectMode]);
    const [assets, setAssets] = useState<{ kit: BattleKit; picture: BoardPicture } | null>(null);
    const [state, setState] = useState<'loading' | 'ready' | 'error' | 'mismatch'>('loading');

    useEffect(() => {
        let cancelled = false;
        setState('loading');
        loadBattleKit(BATTLE_KIT_URL).then(
            (kit) => {
                if (cancelled) return;
                const info = kit.boardInfo.find((b) => b.id === boardId);
                if (!info) {
                    setState('error');
                    return;
                }
                if (terrainInputSha256 && info.terrainSha256 && info.terrainSha256 !== terrainInputSha256) {
                    setState('mismatch');
                    return;
                }
                setAssets({ kit, picture: boardPicture(kit, boardId) });
                setState('ready');
            },
            () => !cancelled && setState('error'),
        );
        return () => {
            cancelled = true;
        };
    }, [boardId, terrainInputSha256]);

    useEffect(() => {
        const canvas = canvasRef.current;
        const box = boxRef.current;
        if (!assets || !canvas || !box) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        const dpr = window.devicePixelRatio || 1;
        const width = box.clientWidth;
        const height = box.clientHeight;
        canvas.width = Math.round(width * dpr);
        canvas.height = Math.round(height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.fillStyle = '#0c0f0e';
        ctx.fillRect(0, 0, width, height);
        // 배치: 고른 부곡(없으면 배치 구역 · 내 부곡 가운데)을 화면 가운데로. 실시간: 따로 둔 가운데(처음은 내 부곡 가운데). 「전체」는 판 전체를 맞춘다.
        const focus = (isLive ? null : units.find((u) => selectedIds.has(u.id))?.cell) ?? centreOf(allowedCells) ?? centreOf(units.map((u) => u.cell)) ?? { row: 32, col: 32 };
        const o = pieceOrigin(assets.kit.layout, focus.row, focus.col, 0);
        const point = (isLive ? center : null) ?? { x: o.x + 16, y: o.y + 8 };
        centerRef.current = point;
        const view = scale.kind === 'fit'
            ? fitView(assets.picture.board, { width, height })
            // 보드 배율(절반 크기 그림 기준) → 렌더러 배율(원본 그림 기준).
            : centerView(point, { width, height }, scale.kind === 'board' ? scale.value * (BOARD_ART_WIDTH / assets.picture.board.width) : scale.value);
        viewRef.current = view;
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
        marksRef.current = units.map((u) => ({ id: u.id, group: u.group ?? 0, ...midOf(u.cell) }));
        clustersRef.current = isLive && view.scale < clusterBelow ? clusterMarks(marksRef.current, CLUSTER_RADIUS).filter((c) => c.ids.length > 1) : [];
        const clustered = new Set(clustersRef.current.flatMap((c) => c.ids));
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
        for (const c of clustersRef.current) drawFlag(ctx, c, c.ids.every((id) => selectedIds.has(id)));
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
        canvas.dataset.cells = JSON.stringify(cells);
        canvas.dataset.clusters = JSON.stringify(clustersRef.current.map((c) => ({ group: c.group, count: c.ids.length, x: c.x, y: c.y })));
        canvas.dataset.scale = view.scale.toFixed(3);
        canvas.dataset.view = `${view.offsetX.toFixed(1)},${view.offsetY.toFixed(1)}`;
    }, [assets, units, allowedCells, selectedIds, scale, isLive, center, clusterBelow, band, anchor]);

    const pointOf = (event: React.PointerEvent<HTMLCanvasElement>): ScreenPoint => {
        const rect = event.currentTarget.getBoundingClientRect();
        return { x: event.clientX - rect.left, y: event.clientY - rect.top };
    };

    // 실시간만 — 누르기 시작(사각형 끌기 · 판 움직이기 · 누르기를 가른다).
    const onPointerDown = (event: React.PointerEvent<HTMLCanvasElement>) => {
        if (!live || !assets || !viewRef.current) return;
        if (event.pointerType === 'mouse' && event.button !== 0) return;
        const kind = live.rectMode ? 'tap' : event.pointerType === 'mouse' ? 'rect' : scale.kind === 'fit' ? 'tap' : 'pan';
        dragRef.current = { id: event.pointerId, start: pointOf(event), kind, startCenter: centerRef.current, moved: false };
        try {
            event.currentTarget.setPointerCapture(event.pointerId);
        } catch {
            // 합성 이벤트(시험)에는 잡을 포인터가 없다 — 잡지 않아도 같은 캔버스 안에서는 동작한다.
        }
    };

    const onPointerMove = (event: React.PointerEvent<HTMLCanvasElement>) => {
        const drag = dragRef.current;
        if (!drag || drag.id !== event.pointerId || !assets || !viewRef.current) return;
        const p = pointOf(event);
        if (!drag.moved && !isDrag(drag.start, p)) return;
        drag.moved = true;
        if (drag.kind === 'rect') setBand({ a: drag.start, b: p });
        else if (drag.kind === 'pan' && drag.startCenter) {
            setCenter(panCenter(drag.startCenter, { x: p.x - drag.start.x, y: p.y - drag.start.y }, viewRef.current.scale, assets.picture.board));
        }
    };

    const onPointerCancel = () => {
        dragRef.current = null;
        setBand(null);
    };

    const onPointerUp = (event: React.PointerEvent<HTMLCanvasElement>) => {
        const view = viewRef.current;
        if (!assets || !view) return;
        const { x, y } = pointOf(event);
        const drag = dragRef.current;
        dragRef.current = null;
        if (live && drag && drag.id === event.pointerId && drag.moved) {
            // 끌기가 끝났다 — 사각형이면 그 안 내 부곡을 고른다. 판 움직이기는 이미 옮겼다.
            if (drag.kind === 'rect') {
                setBand(null);
                live.onPickRect(idsInRect(marksRef.current, drag.start, { x, y }));
            }
            return;
        }
        if (live?.rectMode) {
            if (!anchor) {
                setAnchor({ x, y });
                live.onRectAnchor(true);
            } else {
                setAnchor(null);
                live.onRectAnchor(false);
                live.onPickRect(idsInRect(marksRef.current, anchor, { x, y }));
            }
            return;
        }
        // 묶음 깃발(누를 영역 44) — 그 자리로 다가가 하나씩 갈라진다.
        const cluster = clustersRef.current.find((c) => Math.hypot(c.x - x, c.y - y) <= 22);
        if (live && cluster) {
            setCenter(toPicture(cluster, view));
            live.onZoomTo(live.clusterBelow);
            return;
        }
        const hit = pickCell(assets.kit, assets.picture.board, x, y, view) ?? screenToCell(assets.kit, x, y, view);
        if (!hit) return;
        const cell = { row: hit.r, col: hit.c };
        if (allowedCells.length === 0) {
            // 실시간 — 내 부곡 칸은 그 부곡 고르기 · 풀기(여럿 고르기), 빈 칸은 부른 쪽에 넘긴다.
            const unit = units.find((u) => u.cell.row === cell.row && u.cell.col === cell.col);
            if (unit) onPickUnit(unit.id);
            else onPickCell(cell);
            return;
        }
        const tap = boardTap(units, selectedIds.size === 1 ? [...selectedIds][0] : null, cell);
        if (tap.kind === 'pickUnit') onPickUnit(tap.id);
        else onPickCell(tap.cell);
    };

    return (
        <div ref={boxRef} data-battle-status={state} style={{ position: 'relative', flex: 1, minHeight: 0, background: '#0c0f0e' }}>
            <canvas ref={canvasRef} role="img" aria-label={label} data-testid="battle-board"
                onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerUp} onPointerCancel={onPointerCancel}
                style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', touchAction: 'none' }} />
            {state === 'error' || state === 'mismatch' ? (
                <p role="alert" style={{ position: 'absolute', left: 12, right: 12, bottom: 12, margin: 0, color: '#e08a7c' }}>
                    {state === 'error' ? '전투 판을 불러오지 못했습니다.' : '전투 판 그림이 서버 판과 맞지 않아 그리지 않습니다.'}
                </p>
            ) : null}
        </div>
    );
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
