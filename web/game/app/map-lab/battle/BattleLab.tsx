'use client';

import { useEffect, useRef, useState } from 'react';
import {
  BATTLE_DEFAULT_SCALE,
  boardPicture,
  centerView,
  drawBoardUnits,
  loadBattleKit,
  loadUnitKit,
  pickUnit,
  pieceOrigin,
  unitFrame,
  type BattleKit,
  type BoardPicture,
  type BoardUnit,
  type BoardView,
  type DrawnUnit,
  type UnitAction,
  type UnitFacing,
  type UnitKit,
  type UnitType,
} from '@opensamguk/ui/battle';

// 시험 분대: 판 4 성문 앞(c = 33) · 성 안(c = 36). 세력색 · 장수 첫 글자는 설계 B안 예시 그대로(가짜 자료).
interface LabSquad { id: string; r: number; c: number; type: UnitType; facing: UnitFacing; action: UnitAction; colour: string; letter: string }
const SQUADS: LabSquad[] = [
  { id: 'heo', r: 31, c: 31, type: 'leader', facing: 'ne', action: 'idle', colour: '#4f7fbf', letter: '허' },
  { id: 'ha', r: 33, c: 30, type: 'infantry', facing: 'ne', action: 'attack', colour: '#4f7fbf', letter: '하' },
  { id: 'lee', r: 29, c: 30, type: 'archer', facing: 'ne', action: 'idle', colour: '#4f7fbf', letter: '이' },
  { id: 'cav', r: 35, c: 31, type: 'lightCavalry', facing: 'nw', action: 'idle', colour: '#4f7fbf', letter: '' },
  { id: 'foe1', r: 31, c: 37, type: 'infantry', facing: 'sw', action: 'hit', colour: '#c0392b', letter: '적' },
  { id: 'foe2', r: 33, c: 36, type: 'archer', facing: 'sw', action: 'attack', colour: '#c0392b', letter: '적' },
];
/** Tick length in the original is UNKNOWN; the lab alternates phases twice a second. */
const LAB_TICK_MS = 500;

export default function BattleLab({ kitUrl, boardId }: { kitUrl: string; boardId: number }) {
  const boxRef = useRef<HTMLDivElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawnRef = useRef<DrawnUnit[]>([]);
  const viewRef = useRef<BoardView | null>(null);
  const [state, setState] = useState<'loading' | 'ready' | 'error'>('loading');
  const [hit, setHit] = useState<string | null>(null);
  const [scale, setScale] = useState(BATTLE_DEFAULT_SCALE);
  const [phase, setPhase] = useState<0 | 1>(0);
  const [assets, setAssets] = useState<{ kit: BattleKit; units: UnitKit; picture: BoardPicture } | null>(null);

  useEffect(() => {
    let cancelled = false;
    Promise.all([loadBattleKit(kitUrl), loadUnitKit(kitUrl)]).then(
      ([kit, units]) => {
        if (cancelled) return;
        setAssets({ kit, units, picture: boardPicture(kit, boardId) });
        setState('ready');
      },
      () => !cancelled && setState('error'),
    );
    return () => {
      cancelled = true;
    };
  }, [kitUrl, boardId]);

  useEffect(() => {
    const timer = window.setInterval(() => setPhase((p) => (p ? 0 : 1)), LAB_TICK_MS);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    const canvas = canvasRef.current;
    const box = boxRef.current;
    if (!assets || !canvas || !box) return;
    const dpr = window.devicePixelRatio || 1;
    const width = box.clientWidth;
    const height = box.clientHeight;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    const ctx = canvas.getContext('2d')!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = '#0c0f0e';
    ctx.fillRect(0, 0, width, height);
    // 성문 앞 칸(31, 33)을 가운데로
    const gate = pieceOrigin(assets.kit.layout, 31, 33, 0);
    const view = centerView({ x: gate.x + 16, y: gate.y + 8 }, { width, height }, scale);
    viewRef.current = view;
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(assets.picture.canvas, view.offsetX, view.offsetY, assets.picture.board.width * view.scale, assets.picture.board.height * view.scale);
    const list: BoardUnit[] = SQUADS.map((s) => ({ id: s.id, r: s.r, c: s.c, frame: unitFrame(s.type, s.facing, s.action, phase), colour: s.colour, letter: s.letter }));
    drawnRef.current = drawBoardUnits(ctx, assets.kit, assets.units, assets.picture, list, view);
    // e2e가 그린 자리를 읽는다(시험 화면 전용)
    canvas.dataset.units = JSON.stringify(drawnRef.current);
    canvas.dataset.scale = String(scale);
  }, [assets, phase, scale]);

  const onPointerUp = (event: React.PointerEvent<HTMLCanvasElement>) => {
    const rect = event.currentTarget.getBoundingClientRect();
    setHit(pickUnit(drawnRef.current, event.clientX - rect.left, event.clientY - rect.top));
  };

  return (
    <main style={{ height: '100vh', display: 'flex', flexDirection: 'column', background: '#0c0f0e', color: '#ece6d8' }}>
      <div style={{ display: 'flex', gap: 8, padding: 8, flexWrap: 'wrap', alignItems: 'center' }}>
        <strong>전투 판 시험(분대 B안)</strong>
        <button type="button" style={{ minHeight: 44, minWidth: 44 }} onClick={() => setScale(2)}>2배</button>
        <button type="button" style={{ minHeight: 44, minWidth: 44 }} onClick={() => setScale(1)}>1배</button>
        <output data-testid="battle-lab-hit">{hit ? `분대 ${hit}` : '누른 분대 없음'}</output>
      </div>
      <div ref={boxRef} data-battle-status={state} style={{ position: 'relative', flex: 1, minHeight: 0 }}>
        <canvas ref={canvasRef} data-testid="battle-lab-canvas" onPointerUp={onPointerUp} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', touchAction: 'none' }} />
        {state === 'error' && <p role="alert" style={{ position: 'absolute', inset: 'auto 16px 16px 16px', margin: 0, color: '#e08a7c' }}>전투 판을 불러오지 못했습니다.</p>}
      </div>
    </main>
  );
}
