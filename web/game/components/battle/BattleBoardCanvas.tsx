'use client';

// 전투 판(아이소) — 배치 화면(P-C03)용. 그림은 정본 전투 키트(public/battle/waryong/<kit>, 원작 214판)와 공용 렌더러(@opensamguk/ui/battle).
// - 판 번호는 SNAPSHOT field.boardId(= 티켓 battlefieldId 0..213, C2 #9). 서버 지형 해시(terrainInputSha256)가 키트 판의 terrainSha256 과
//   다르면 추측해서 그리지 않는다(C2 #9).
// - 배치 구역(allowedCells)은 초록 점선 마름모, 고른 칸은 노란 마름모(보드 V31K6v2BattleJoin).
// - 내 부곡은 칸 마름모 + 장수 안 차례 번호로 그린다. 병종 그림 · 세력색은 서버가 아직 주지 않아(C2 v2 답 #2) 지어내지 않는다.
// - 누르기: 고른 부곡이 있으면 어느 칸이든 그 칸 누름(내 부곡 칸이면 부른 쪽이 맞바꾸기 — 보드 「내 부곡이 있는 칸이면 둘을 맞바꾼다」).
//   고른 부곡이 없을 때만 내 부곡 칸이 그 부곡 고르기다(boardTap). 다른 부곡으로 바꿔 고르기는 목록에서. 끌기는 없다(보드 「끌기는 없다」).
// - e2e 는 data-cells(판 위 칸 → 화면 좌표)로 누를 곳을 찾는다. 캔버스가 없는 환경(jsdom)에서는 그리지 않고 목록 · 패널만 쓴다.
import { useEffect, useRef, useState } from 'react';
import {
    boardPicture, cellPolygon, centerView, loadBattleKit, pickCell, pieceOrigin, screenToCell,
    type BattleKit, type BoardPicture, type BoardView,
} from '@opensamguk/ui/battle';
import type { Cell } from '@/lib/battle/protocol';
import { boardTap, type JoinUnitView } from '@/lib/battle/join-view';

/** 정본 전투 키트 — 원작 214판 export(파일별 SHA 는 export.json). */
export const BATTLE_KIT_URL = '/battle/waryong/2c8a1a5';
/** 보드 그림이 쓴 판 그림 너비(boards_v31_k6 MAP battle_field — 원작 판을 절반으로 줄인 1024). 보드 배율을 렌더러 배율로 바꿀 때 쓴다. */
const BOARD_ART_WIDTH = 1024;

export interface BattleBoardCanvasProps {
    readonly boardId: number;
    /** SNAPSHOT field.terrainInputSha256 — 있으면 키트 판 해시와 대조한다. */
    readonly terrainInputSha256: string | null;
    readonly units: readonly JoinUnitView[];
    readonly allowedCells: readonly Cell[];
    readonly selectedId: string | null;
    /** 보드 배율(D29: 배치 데스크톱 1.25 · 모바일 1.6) — 보드 그림(절반 크기) 기준. */
    readonly boardScale: number;
    readonly onPickUnit: (id: string) => void;
    readonly onPickCell: (cell: Cell) => void;
    readonly label: string;
}

export function BattleBoardCanvas({ boardId, terrainInputSha256, units, allowedCells, selectedId, boardScale, onPickUnit, onPickCell, label }: BattleBoardCanvasProps) {
    const boxRef = useRef<HTMLDivElement>(null);
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const viewRef = useRef<BoardView | null>(null);
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
        // 보드 배율(절반 크기 그림 기준) → 렌더러 배율(원본 그림 기준).
        const scale = boardScale * (BOARD_ART_WIDTH / assets.picture.board.width);
        // 고른 부곡(없으면 배치 구역 가운데)을 화면 가운데로.
        const focus = units.find((u) => u.id === selectedId)?.cell ?? centreOf(allowedCells) ?? { row: 32, col: 32 };
        const o = pieceOrigin(assets.kit.layout, focus.row, focus.col, 0);
        const view = centerView({ x: o.x + 16, y: o.y + 8 }, { width, height }, scale);
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
        // 내 부곡 — 칸 마름모 + 차례 번호(고른 부곡은 노랑).
        const cells: Record<string, { x: number; y: number }> = {};
        for (const u of units) {
            const pts = poly(u.cell);
            path(pts);
            const on = u.id === selectedId;
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
            ctx.fillText(String(u.index), mid.x, mid.y);
            cells[`${u.cell.row}:${u.cell.col}`] = mid;
        }
        // e2e 가 누를 곳 — 내 부곡 칸과 배치 구역 칸의 화면 좌표.
        for (const c of allowedCells) {
            const pts = poly(c);
            cells[`${c.row}:${c.col}`] ??= { x: (pts[1].x + pts[3].x) / 2, y: (pts[0].y + pts[2].y) / 2 };
        }
        canvas.dataset.cells = JSON.stringify(cells);
        canvas.dataset.scale = scale.toFixed(3);
    }, [assets, units, allowedCells, selectedId, boardScale]);

    const onPointerUp = (event: React.PointerEvent<HTMLCanvasElement>) => {
        const view = viewRef.current;
        if (!assets || !view) return;
        const rect = event.currentTarget.getBoundingClientRect();
        const x = event.clientX - rect.left;
        const y = event.clientY - rect.top;
        const hit = pickCell(assets.kit, assets.picture.board, x, y, view) ?? screenToCell(assets.kit, x, y, view);
        if (!hit) return;
        const tap = boardTap(units, selectedId, { row: hit.r, col: hit.c });
        if (tap.kind === 'pickUnit') onPickUnit(tap.id);
        else onPickCell(tap.cell);
    };

    return (
        <div ref={boxRef} data-battle-status={state} style={{ position: 'relative', flex: 1, minHeight: 0, background: '#0c0f0e' }}>
            <canvas ref={canvasRef} role="img" aria-label={label} data-testid="battle-board" onPointerUp={onPointerUp}
                style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', touchAction: 'none' }} />
            {state === 'error' || state === 'mismatch' ? (
                <p role="alert" style={{ position: 'absolute', left: 12, right: 12, bottom: 12, margin: 0, color: '#e08a7c' }}>
                    {state === 'error' ? '전투 판을 불러오지 못했습니다.' : '전투 판 그림이 서버 판과 맞지 않아 그리지 않습니다.'}
                </p>
            ) : null}
        </div>
    );
}

function centreOf(cells: readonly Cell[]): Cell | null {
    if (cells.length === 0) return null;
    const r = cells.reduce((s, c) => s + c.row, 0) / cells.length;
    const c = cells.reduce((s, x) => s + x.col, 0) / cells.length;
    return { row: Math.round(r), col: Math.round(c) };
}
