'use client';

// 다시 보기 전장 판(P-H03) — 읽기 전용. 정본 전투 키트(BATTLE_KIT_URL, 원작 214판)의 판 하나를 그린다.
// - 판 번호는 리플레이의 field.boardId, 서버 지형 해시가 키트 판의 terrainSha256 과 다르면 추측해서 그리지 않는다
//   (K6 배치 판 BattleBoardCanvas 와 같은 규칙, C2 #9).
// - 처음엔 판 전체가 보이게(가장 작은 배율) 두고, + · − 단추(44) · 끌기 · 두 손가락 벌리기로 본다(K5 설계 §5.3 모바일).
//   바퀴 확대는 두지 않는다 — React 바퀴 이벤트는 막을 수 없어(passive) 판을 확대하는 동안 쪽도 함께 굴러간다.
// - 부곡 · 사건 표시는 리플레이 본문 모양이 합의된 뒤 얹는다(계약판 「K10 → C2 리플레이 읽기 모양 제안」).
// - e2e 는 data-board-status · data-scale 로 판 상태와 배율을 읽는다. 캔버스가 없는 환경(jsdom)에서는 그리지 않는다.
import { useEffect, useRef, useState, type PointerEvent } from 'react';
import { Button } from '@opensamguk/ui';
import { boardPicture, loadBattleKit, type BoardPicture } from '@opensamguk/ui/battle';
import { BATTLE_KIT_URL } from './BattleBoardCanvas';
import styles from './ReplayBoard.module.css';

/** 가장 크게 볼 때의 배율(판 원본 px × 4). 가장 작게는 판 전체가 들어오는 배율이다. */
export const REPLAY_MAX_SCALE = 4;
const ZOOM_STEP = 1.5;

export interface BoardSize {
    readonly width: number;
    readonly height: number;
}

/** 판 전체가 보는 창에 들어오는 배율. */
export function fitScale(board: BoardSize, viewport: BoardSize): number {
    if (board.width <= 0 || board.height <= 0 || viewport.width <= 0 || viewport.height <= 0) return 1;
    return Math.min(viewport.width / board.width, viewport.height / board.height);
}

/** 한 단계 확대(+1) · 축소(−1) — 가장 작게(판 전체) ~ 가장 크게 사이로 자른다. */
export function zoomScale(scale: number, dir: 1 | -1, min: number, max = REPLAY_MAX_SCALE): number {
    return Math.min(max, Math.max(min, dir > 0 ? scale * ZOOM_STEP : scale / ZOOM_STEP));
}

/** 보는 창 가운데에 올 판 위 점 — 판 밖으로 나가지 않게 자른다. */
export function clampCenter(center: { x: number; y: number }, board: BoardSize): { x: number; y: number } {
    return { x: Math.min(board.width, Math.max(0, center.x)), y: Math.min(board.height, Math.max(0, center.y)) };
}

export interface ReplayBoardProps {
    readonly boardId: number;
    /** 리플레이 field.terrainInputSha256 — 있으면 키트 판 해시와 대조한다. */
    readonly terrainInputSha256: string | null;
    readonly label: string;
}

type Status = 'loading' | 'ready' | 'error' | 'mismatch';

export function ReplayBoard({ boardId, terrainInputSha256, label }: ReplayBoardProps) {
    const boxRef = useRef<HTMLDivElement>(null);
    const canvasRef = useRef<HTMLCanvasElement>(null);
    const [picture, setPicture] = useState<BoardPicture | null>(null);
    const [status, setStatus] = useState<Status>('loading');
    const [size, setSize] = useState<BoardSize>({ width: 0, height: 0 });
    // scale = null 이면 판 전체(보는 창 크기를 따라간다).
    const [scale, setScale] = useState<number | null>(null);
    const [center, setCenter] = useState<{ x: number; y: number } | null>(null);
    const pointers = useRef(new Map<number, { x: number; y: number }>());

    useEffect(() => {
        let cancelled = false;
        setStatus('loading');
        setPicture(null);
        setScale(null);
        setCenter(null);
        loadBattleKit(BATTLE_KIT_URL).then(
            (kit) => {
                if (cancelled) return;
                const info = kit.boardInfo.find((b) => b.id === boardId);
                if (!info) {
                    setStatus('error');
                    return;
                }
                if (terrainInputSha256 && info.terrainSha256 && info.terrainSha256 !== terrainInputSha256) {
                    setStatus('mismatch');
                    return;
                }
                setPicture(boardPicture(kit, boardId));
                setStatus('ready');
            },
            () => !cancelled && setStatus('error'),
        );
        return () => {
            cancelled = true;
        };
    }, [boardId, terrainInputSha256]);

    useEffect(() => {
        const box = boxRef.current;
        if (!box || typeof ResizeObserver === 'undefined') return undefined;
        const observer = new ResizeObserver(([entry]) => setSize({ width: entry.contentRect.width, height: entry.contentRect.height }));
        observer.observe(box);
        return () => observer.disconnect();
    }, []);

    const board: BoardSize | null = picture ? { width: picture.board.width, height: picture.board.height } : null;
    const minScale = board ? fitScale(board, size) : 1;
    const shown = scale === null ? minScale : Math.max(minScale, scale);
    const middle = board ? center ?? { x: board.width / 2, y: board.height / 2 } : null;

    const cx = middle?.x ?? 0;
    const cy = middle?.y ?? 0;
    useEffect(() => {
        const canvas = canvasRef.current;
        if (!picture || !canvas || size.width <= 0 || size.height <= 0) return;
        const ctx = canvas.getContext('2d');
        if (!ctx) return;
        const dpr = window.devicePixelRatio || 1;
        canvas.width = Math.round(size.width * dpr);
        canvas.height = Math.round(size.height * dpr);
        ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
        ctx.fillStyle = '#0c0f0e';
        ctx.fillRect(0, 0, size.width, size.height);
        ctx.imageSmoothingEnabled = false;
        const offsetX = size.width / 2 - cx * shown;
        const offsetY = size.height / 2 - cy * shown;
        ctx.drawImage(picture.canvas, offsetX, offsetY, picture.board.width * shown, picture.board.height * shown);
        canvas.dataset.scale = shown.toFixed(3);
    }, [picture, cx, cy, shown, size.width, size.height]);

    const zoom = (dir: 1 | -1) => setScale(zoomScale(shown, dir, minScale));
    const fit = () => {
        setScale(null);
        setCenter(null);
    };

    const onPointerDown = (event: PointerEvent<HTMLCanvasElement>) => {
        event.currentTarget.setPointerCapture?.(event.pointerId);
        pointers.current.set(event.pointerId, { x: event.clientX, y: event.clientY });
    };
    const onPointerMove = (event: PointerEvent<HTMLCanvasElement>) => {
        const before = pointers.current.get(event.pointerId);
        if (!before || !board || !middle) return;
        const now = { x: event.clientX, y: event.clientY };
        const others = [...pointers.current.entries()].filter(([id]) => id !== event.pointerId).map(([, p]) => p);
        pointers.current.set(event.pointerId, now);
        if (others.length === 0) {
            // 한 손가락 · 마우스 끌기 — 판을 옮긴다.
            setCenter(clampCenter({ x: middle.x - (now.x - before.x) / shown, y: middle.y - (now.y - before.y) / shown }, board));
            return;
        }
        // 두 손가락 — 벌린 만큼 확대한다.
        const other = others[0];
        const was = Math.hypot(before.x - other.x, before.y - other.y);
        const is = Math.hypot(now.x - other.x, now.y - other.y);
        if (was > 0) setScale(Math.min(REPLAY_MAX_SCALE, Math.max(minScale, shown * (is / was))));
    };
    const onPointerEnd = (event: PointerEvent<HTMLCanvasElement>) => {
        pointers.current.delete(event.pointerId);
    };

    const atMax = shown >= REPLAY_MAX_SCALE - 1e-6;
    const atMin = shown <= minScale + 1e-6;
    return (
        <div className={styles.board} data-board-status={status}>
            <div ref={boxRef} className={styles.view}>
                <canvas ref={canvasRef} role="img" aria-label={label} data-testid="replay-board" className={styles.canvas}
                    onPointerDown={onPointerDown} onPointerMove={onPointerMove} onPointerUp={onPointerEnd} onPointerCancel={onPointerEnd} />
                {status === 'loading' ? <p className={styles.note}>전장 판을 불러오는 중…</p> : null}
                {status === 'error' || status === 'mismatch' ? (
                    <p role="alert" className={styles.alert}>
                        {status === 'error' ? '전장 판을 불러오지 못했습니다.' : '전장 판 그림이 서버 판과 맞지 않아 그리지 않습니다.'}
                    </p>
                ) : null}
            </div>
            <div className={styles.zoom} role="group" aria-label="판 보기">
                {atMin ? <Button disabled reason="판 전체를 보고 있습니다" aria-label="축소">−</Button> : <Button aria-label="축소" onClick={() => zoom(-1)}>−</Button>}
                {atMax ? <Button disabled reason="가장 크게 보고 있습니다" aria-label="확대">+</Button> : <Button aria-label="확대" onClick={() => zoom(1)}>+</Button>}
                {atMin && center === null ? <Button disabled reason="판 전체를 보고 있습니다">판 전체</Button> : <Button onClick={fit}>판 전체</Button>}
            </div>
        </div>
    );
}
