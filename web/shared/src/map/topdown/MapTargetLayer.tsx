'use client';

// 지도 대상 고르기의 지도 층(보드 V31SystemMapPick · MapModes, K3 MapTargetPicker 「지도 그리기는 K2 지도 층」).
// - 후보마다 44 표지(누를 수 있는 단추, 이름 · 상태를 읽는다) + 이름표. 상태 · 고른 차례는 K3 picker 가 정본이다.
// - 후보 밖은 흐리게(α .42), 고른 곳까지 금색 점선 + 거리 꼬리표.
// - 못 고르는 표지를 누르면 고르지 않고 onBlocked 로 넘긴다(부르는 쪽이 사유를 연다).
// 지도 위(TopdownMap 형제)에 같은 크기로 겹쳐 놓고, TopdownMap onViewChange 의 카메라를 받는다.
import { useEffect, useRef, useState, type CSSProperties } from 'react';
import type { TargetPicker } from '../../parts/MapTargetPicker';
import type { TargetCandidate, TargetMarkerState } from '../../parts/types';
import { cellToScreen } from './camera';
import type { Camera, CellPoint } from './types';

const SIZE = 44;
/** 표지가 화면 가장자리에 반쯤 걸리면 화면 밖으로 센다. */
const EDGE = SIZE / 2;

const ARIA: Record<TargetMarkerState, string> = {
  ok: '고를 수 있음',
  no: '고를 수 없음 — 누르면 이유',
  selected: '고른 곳',
  here: '지금 자리',
};

function markerStyle(state: TargetMarkerState, corps: boolean): CSSProperties {
  const base: CSSProperties = { position: 'absolute', width: SIZE, height: SIZE, margin: `-${EDGE}px 0 0 -${EDGE}px`, padding: 0, font: 'inherit',
    cursor: 'pointer', pointerEvents: 'auto', color: '#ece6d8' };
  const shape: CSSProperties = corps
    ? { borderRadius: '50%', background: 'rgba(12,15,14,0.6)', fontFamily: "'Noto Serif KR', serif", fontWeight: 900, fontSize: 15 }
    : {};
  if (state === 'selected') return { ...base, ...shape, border: '3px solid #ffd36d', background: 'rgba(255,211,109,0.18)', boxShadow: '0 0 0 2px rgba(12,15,14,0.9)' };
  if (state === 'no') return { ...base, ...shape, border: '2px dashed #e08a7c', background: 'rgba(12,15,14,0.4)' };
  if (state === 'here') return { ...base, ...shape, border: '2px solid #4f7fbf', background: 'rgba(143,167,122,0.2)', boxShadow: '0 0 0 2px #ffd36d' };
  return { ...base, ...shape, border: '2px solid #8fa77a', background: corps ? shape.background : 'rgba(143,167,122,0.2)' };
}

const LABEL: CSSProperties = { position: 'absolute', height: 22, padding: '0 6px', display: 'inline-flex', alignItems: 'center',
  fontFamily: "'Noto Serif KR', serif", fontSize: 14, fontWeight: 700, color: '#f5ecd6', background: 'rgba(12,15,14,0.78)',
  whiteSpace: 'nowrap', transform: 'translateX(-50%)', pointerEvents: 'none' };

export interface MapTargetLayerProps {
  /** 지금 카메라(TopdownMap onViewChange). 아직 모르면 표지를 그리지 않는다. */
  readonly camera: Camera | null;
  readonly candidates: readonly TargetCandidate[];
  readonly picker: Pick<TargetPicker, 'markerStateOf' | 'orderOf' | 'pick'>;
  /** 못 고르는 표지를 눌렀을 때 — 고르지 않고 이 후보를 넘긴다. */
  readonly onBlocked?: (candidate: TargetCandidate) => void;
  /** 점선이 시작하는 칸(내 자리). 없으면 점선을 긋지 않는다. */
  readonly from?: CellPoint | null;
  /**
   * 후보 밖 흐리게(α .42)를 이 층이 덮을지. 구역 단위 후보는 렌더러 고르기(WorldState.pick)가 후보 밖 구역만 흐리므로
   * 그때는 false 로 둔다(둘 다 덮으면 후보 구역까지 어두워진다).
   */
  readonly dim?: boolean;
}

/** 칸 가운데 화면 자리. */
function centreOf(cell: { col: number; row: number }): CellPoint {
  return { col: cell.col + 0.5, row: cell.row + 0.5 };
}

export function MapTargetLayer({ camera, candidates, picker, onBlocked, from = null, dim = true }: MapTargetLayerProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ width: number; height: number } | null>(null);
  useEffect(() => {
    const box = boxRef.current;
    if (!box) return undefined;
    const measure = () => {
      const rect = box.getBoundingClientRect();
      setSize((was) => (was && was.width === rect.width && was.height === rect.height ? was : { width: rect.width, height: rect.height }));
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    return () => observer.disconnect();
  }, []);

  const viewport = size && size.width > 0 ? { ...size, dpr: 1 } : null;
  const placed = camera && viewport
    ? candidates.flatMap((candidate) => (candidate.cell ? [{ candidate, at: cellToScreen(centreOf(candidate.cell), camera, viewport) }] : []))
    : [];
  const shown = viewport
    ? placed.filter(({ at }) => at.x >= EDGE && at.x <= viewport.width - EDGE && at.y >= EDGE && at.y <= viewport.height - EDGE)
    : [];
  // 지도 표지가 없는 후보(칸 없음 · 화면 밖)는 목록(K3)에만 있다 — 그 수를 띠가 「화면 밖 후보」로 보일 수 있게 남긴다.
  const offscreen = candidates.length - shown.length;

  const selected = placed.find(({ candidate }) => picker.markerStateOf(candidate.targetId) === 'selected');
  const start = from && camera && viewport ? cellToScreen(centreOf(from), camera, viewport) : null;
  const distance = selected?.candidate.distanceCells != null
    ? `${selected.candidate.distanceCells}칸${selected.candidate.distanceTurns != null ? ` · ${selected.candidate.distanceTurns}순` : ''}`
    : null;

  return (
    <div ref={boxRef} data-map-targets="" data-offscreen-count={offscreen} style={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden' }}>
      {dim ? <div aria-hidden="true" data-target-dim="" style={{ position: 'absolute', inset: 0, background: 'rgba(8,10,9,0.42)' }} /> : null}
      {selected && start ? (
        <>
          <svg aria-hidden="true" width={viewport!.width} height={viewport!.height} style={{ position: 'absolute', left: 0, top: 0 }}>
            <path d={`M${start.x} ${start.y} L${selected.at.x} ${selected.at.y}`} stroke="#ffd36d" strokeWidth={3} strokeDasharray="8 6" fill="none" />
          </svg>
          {distance ? (
            <span data-target-distance="" style={{ position: 'absolute', left: (start.x + selected.at.x) / 2, top: (start.y + selected.at.y) / 2,
              transform: 'translate(-50%, -50%)', height: 22, padding: '0 6px', display: 'inline-flex', alignItems: 'center', fontSize: 11,
              fontWeight: 700, color: '#161410', background: '#ffd36d', whiteSpace: 'nowrap' }}>{distance}</span>
          ) : null}
        </>
      ) : null}
      {shown.map(({ candidate, at }) => {
        const state = picker.markerStateOf(candidate.targetId);
        const order = picker.orderOf(candidate.targetId);
        const corps = candidate.targetKind === 'corps';
        return (
          <span key={candidate.targetId}>
            <button
              type="button"
              aria-label={`${candidate.name} — ${ARIA[state]}`}
              aria-pressed={state === 'selected'}
              data-target-id={candidate.targetId}
              data-target-state={state}
              style={{ ...markerStyle(state, corps), left: at.x, top: at.y }}
              onClick={() => {
                if (!picker.pick(candidate.targetId)) onBlocked?.(candidate);
              }}
            >
              {corps ? candidate.name.slice(0, 1) : null}
              {order != null ? (
                <span style={{ position: 'absolute', right: -8, top: -8, minWidth: 20, height: 20, padding: '0 4px', fontSize: 11, fontWeight: 700,
                  lineHeight: '20px', color: '#161410', background: '#ffd36d' }}>{order}</span>
              ) : null}
            </button>
            {corps ? null : (
              <span aria-hidden="true" style={{ ...LABEL, left: at.x, top: at.y + 26,
                ...(state === 'no' ? { color: '#b9b2a3', textDecoration: 'line-through', textDecorationColor: 'rgba(224,138,124,0.7)' } : null) }}>
                {candidate.name}
              </span>
            )}
          </span>
        );
      })}
    </div>
  );
}
