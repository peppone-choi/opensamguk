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
import { boardPicture, loadBattleKit, pickCell, screenToCell, type BattleKit, type BoardPicture, type BoardView } from '@opensamguk/ui/battle';
import type { Cell } from '@/lib/battle/protocol';
import { boardTap } from '@/lib/battle/join-view';
import { idsInRect, isDrag, panCenter, toPicture, type MarkCluster, type ScreenMark, type ScreenPoint } from '@/lib/battle/board-select';
import { drawBattleBoard, type BoardScale, type BoardUnitMark } from './board-draw';

export type { BoardScale, BoardUnitMark } from './board-draw';

/** 정본 전투 키트 — 원작 214판 export(파일별 SHA 는 export.json). */
export const BATTLE_KIT_URL = '/battle/waryong/2c8a1a5';
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
        const r = drawBattleBoard(ctx, { assets, width, height, units, allowedCells, selectedIds, scale, isLive, center, clusterBelow, band, anchor });
        viewRef.current = r.view;
        centerRef.current = r.point;
        marksRef.current = r.marks;
        clustersRef.current = r.clusters;
        canvas.dataset.cells = JSON.stringify(r.cells);
        canvas.dataset.clusters = JSON.stringify(r.clusters.map((c) => ({ group: c.group, count: c.ids.length, x: c.x, y: c.y })));
        canvas.dataset.scale = r.view.scale.toFixed(3);
        canvas.dataset.view = `${r.view.offsetX.toFixed(1)},${r.view.offsetY.toFixed(1)}`;
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
