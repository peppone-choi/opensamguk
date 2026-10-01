'use client';

// 지도 위 조작(보드 V31WarRoom · V31MWarRoom · v31system MapViewBar · V31MapLOD 조작 표).
// - 보기 단추(MapViewBar): 왼쪽 아래 세로 줄 — 보기 수준 주 · 군 · 현 → 확대 · 축소 → 내 위치로(Home). 모두 44.
// - 지도 레이어 · 범례(MapLayerButtons): 오른쪽 위. 누르면 그 아래 판이 열린다(비모달, Esc · 다시 누르기로 닫힘).
// 지도 상태(카메라 · 층)는 TopdownMap 이 갖는다. 이 부품은 handle 로 움직이고 layers 를 바꿀 뿐이다. 자리는 화면 틀이 정한다.
import { useEffect, useId, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react';
import { Icon } from '../../Icon';
import type { MapLayers } from './renderer';
import type { TopdownMapHandle } from './TopdownMap';
import type { ViewLevel } from './types';

const FLOAT_BG = 'rgba(20,24,22,0.92)';
const BUTTON: CSSProperties = { minWidth: 44, minHeight: 44, padding: 0, background: FLOAT_BG };

const LEVELS: readonly { value: ViewLevel; label: string; name: string }[] = [
  { value: 'ju', label: '주', name: '주 보기' },
  { value: 'commandery', label: '군', name: '군 보기' },
  { value: 'county', label: '현', name: '현 보기' },
];

export interface MapViewBarProps {
  readonly handle: TopdownMapHandle | null;
  /** 지금 보기 수준(TopdownMap onViewChange). 모르면 아무 칸도 고르지 않은 채로 둔다. */
  readonly level: ViewLevel | null;
  /** 「내 위치로」. 내 장수 자리를 모르면 넘기지 않는다 — 단추를 숨기지 않고 끈 채 사유를 보인다. */
  readonly onMyLocation?: () => void;
  /** 「내 위치로」를 못 누르는 사유(쉬운 말). */
  readonly myLocationReason?: string;
  readonly style?: CSSProperties;
}

/** 보기 단추 — 주 · 군 · 현(라디오, 위아래 화살표), 확대 · 축소, 내 위치로. */
export function MapViewBar({ handle, level, onMyLocation, myLocationReason = '내 장수 자리를 아직 모릅니다', style }: MapViewBarProps) {
  const reasonId = `${useId()}-why`;
  const pick = (next: ViewLevel) => handle?.setLevel(next);
  const current = LEVELS.findIndex((entry) => entry.value === level);
  // 고른 칸만 Tab 순서에 든다. 아무것도 안 골랐으면 첫 칸.
  const focusable = current < 0 ? 0 : current;
  const onKey = (event: KeyboardEvent<HTMLDivElement>) => {
    const step = event.key === 'ArrowDown' || event.key === 'ArrowRight' ? 1 : event.key === 'ArrowUp' || event.key === 'ArrowLeft' ? -1 : 0;
    if (!step) return;
    event.preventDefault();
    event.stopPropagation(); // 지도 방향키 이동으로 새지 않게
    const next = LEVELS[(focusable + step + LEVELS.length) % LEVELS.length];
    pick(next.value);
    (event.currentTarget.querySelector(`[data-level="${next.value}"]`) as HTMLButtonElement | null)?.focus();
  };
  return (
    <div data-map-control="view-bar" style={{ display: 'flex', flexDirection: 'column', gap: 8, ...style }}>
      <div role="radiogroup" aria-label="보기 수준" onKeyDown={onKey} style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        {LEVELS.map((entry, index) => {
          const on = entry.value === level;
          return (
            <button
              key={entry.value}
              type="button"
              role="radio"
              aria-checked={on}
              aria-label={entry.name}
              data-level={entry.value}
              tabIndex={index === focusable ? 0 : -1}
              className={['os-seg__item', on ? 'os-seg__item--on' : ''].filter(Boolean).join(' ')}
              style={{ ...BUTTON, ...(on ? { background: undefined } : null) }}
              onClick={() => pick(entry.value)}
            >
              {entry.label}
            </button>
          );
        })}
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
        <button type="button" className="os-button" aria-label="확대" style={{ ...BUTTON, fontSize: 20 }} onClick={() => handle?.zoomStep(1)}>+</button>
        <button type="button" className="os-button" aria-label="축소" style={{ ...BUTTON, fontSize: 20 }} onClick={() => handle?.zoomStep(-1)}>−</button>
      </div>
      <button
        type="button"
        className="os-button"
        aria-label="내 위치로(Home)"
        aria-disabled={onMyLocation ? undefined : true}
        aria-describedby={onMyLocation ? undefined : reasonId}
        style={BUTTON}
        onClick={onMyLocation}
      >
        <Icon name="war-room" size={20} />
      </button>
      {onMyLocation ? null : <span id={reasonId} hidden>{myLocationReason}</span>}
    </div>
  );
}

export type MapLayerKey = keyof MapLayers;

/** 레이어 판 줄(보드 P-W03: 구역 · 현 · 군 경계, 보급선, 시야, 부대 경로, 도시 이름). */
export const MAP_LAYER_ROWS: readonly { readonly key: MapLayerKey; readonly label: string }[] = [
  { key: 'provinceLines', label: '구역 경계' },
  { key: 'countyLines', label: '현 경계' },
  { key: 'commanderyLines', label: '군 경계' },
  { key: 'corpsRoutes', label: '부대 경로' },
  { key: 'cityNames', label: '도시 이름' },
];

/** 서버 칸이 아직 없는 층. 숨기지 않고 「서버 대기」로 보이며 계약판 행을 단다. */
export interface PendingLayer {
  readonly id: string;
  readonly label: string;
  /** 계약판 행 id(예: K2-08). */
  readonly contract: string;
}

export interface MapLayerButtonsProps {
  readonly layers: MapLayers;
  readonly onLayersChange: (layers: MapLayers) => void;
  readonly pending?: readonly PendingLayer[];
  /** 범례 판 내용. */
  readonly legend: ReactNode;
  /** 좁은 화면(모바일): 단추를 세로로 세우고 판을 단추 왼쪽에 연다. */
  readonly compact?: boolean;
  readonly style?: CSSProperties;
}

type Open = 'layers' | 'legend' | null;

/** 「지도 레이어」 · 「범례」 단추와 그 판. 한 번에 하나만 열린다. */
export function MapLayerButtons({ layers, onLayersChange, pending = [], legend, compact = false, style }: MapLayerButtonsProps) {
  const [open, setOpen] = useState<Open>(null);
  const base = useId();
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]);
  const toggle = (which: Exclude<Open, null>) => setOpen((was) => (was === which ? null : which));
  const panel: CSSProperties = compact
    ? { position: 'absolute', right: 'calc(100% + 8px)', top: 0, width: 'min(280px, calc(100vw - 80px))' }
    : { position: 'absolute', right: 0, top: 'calc(100% + 8px)', width: 280 };
  return (
    <div data-map-control="layer-buttons" style={{ position: 'relative', display: 'flex', flexDirection: compact ? 'column' : 'row', gap: 6, ...style }}>
      <button
        type="button"
        className="os-button"
        aria-expanded={open === 'layers'}
        aria-controls={`${base}-layers`}
        aria-label={compact ? '지도 레이어' : undefined}
        style={{ minHeight: 44, minWidth: 44, padding: compact ? 0 : '0 12px', background: FLOAT_BG }}
        onClick={() => toggle('layers')}
      >
        <Icon name="layers" size={20} />
        {compact ? null : '지도 레이어'}
      </button>
      <button
        type="button"
        className="os-button"
        aria-expanded={open === 'legend'}
        aria-controls={`${base}-legend`}
        aria-label={compact ? '범례' : undefined}
        style={{ minHeight: 44, minWidth: 44, padding: compact ? 0 : '0 12px', background: FLOAT_BG }}
        onClick={() => toggle('legend')}
      >
        <Icon name="legend" size={20} />
        {compact ? null : '범례'}
      </button>
      {open === 'layers' ? (
        <section id={`${base}-layers`} aria-label="지도 레이어" className="os-panel" style={{ ...panel, padding: 8, display: 'grid', gap: 4, background: 'rgba(27,32,29,0.97)' }}>
          {MAP_LAYER_ROWS.map((row) => (
            <button
              key={row.key}
              type="button"
              className="os-button os-button--ghost"
              aria-pressed={layers[row.key]}
              style={{ justifyContent: 'space-between', width: '100%' }}
              onClick={() => onLayersChange({ ...layers, [row.key]: !layers[row.key] })}
            >
              <span>{row.label}</span>
              <span aria-hidden="true">{layers[row.key] ? '켬' : '끔'}</span>
            </button>
          ))}
          {pending.map((row) => (
            <div key={row.id} data-pending-layer={row.id} style={{ minHeight: 44, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, padding: '0 14px', color: 'var(--muted)' }}>
              <span>{row.label}</span>
              <span style={{ fontSize: 12 }}>서버 대기 · {row.contract}</span>
            </div>
          ))}
        </section>
      ) : null}
      {open === 'legend' ? (
        <section id={`${base}-legend`} aria-label="범례" className="os-panel" style={{ ...panel, padding: 8, background: 'rgba(27,32,29,0.97)' }}>
          {legend}
        </section>
      ) : null}
    </div>
  );
}

/** 범례의 세력 색 칸(보드 shell LEGEND): 색 네모 + 이름. */
export function LegendSwatch({ color, label, hatched = false }: { readonly color?: string; readonly label: string; readonly hatched?: boolean }) {
  return (
    <span className="os-chip" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <i
        aria-hidden="true"
        style={{
          width: 10,
          height: 10,
          display: 'inline-block',
          background: hatched ? 'repeating-linear-gradient(45deg, var(--muted) 0 2px, transparent 2px 4px)' : color,
        }}
      />
      {label}
    </span>
  );
}
