'use client';

// 지도 위 조작(보드 V31WarRoom · V31MWarRoom · v31system MapViewBar · V31MapLOD 조작 표).
// - 보기 단추(MapViewBar): 왼쪽 아래 세로 줄 — 보기 수준 주 · 군 · 현 → 확대 · 축소 → 내 위치로(Home). 모두 44.
// - 지도 레이어 · 범례(MapLayerButtons): 오른쪽 위. 누르면 그 아래 판이 열린다(비모달, Esc · 다시 누르기로 닫힘).
// 지도 상태(카메라 · 층)는 TopdownMap 이 갖는다. 이 부품은 handle 로 움직이고 layers 를 바꿀 뿐이다. 자리는 화면 틀이 정한다.
import { useEffect, useId, useLayoutEffect, useRef, useState, type CSSProperties, type KeyboardEvent, type ReactNode } from 'react';
import { Icon } from '../../Icon';
import { ReasonTooltip } from '../../ReasonTooltip';
import type { MapLayers } from './renderer';
import type { TopdownMapHandle } from './TopdownMap';
import type { ViewLevel } from './types';
import { safeNationColor } from '../../nationVisual';

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
  const myLocationButton = (
    <button
      type="button"
      className={onMyLocation ? 'os-button' : 'os-button os-button--disabled'}
      aria-label="내 위치로(Home)"
      aria-disabled={onMyLocation ? undefined : true}
      style={BUTTON}
      onClick={onMyLocation}
    >
      <Icon name="war-room" size={20} />
    </button>
  );
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
      {onMyLocation ? myLocationButton : <ReasonTooltip reason={myLocationReason}>{myLocationButton}</ReasonTooltip>}
    </div>
  );
}

export type MapLayerKey = keyof MapLayers;

/** 레이어 판 줄(보드 P-W03: 구역 · 현 · 군 경계, 보급선, 시야, 부대 경로, 도시 이름). */
export const MAP_LAYER_ROWS: readonly { readonly key: MapLayerKey; readonly label: string }[] = [
  { key: 'provinceLines', label: '구역 경계' },
  { key: 'countyLines', label: '현 경계' },
  { key: 'commanderyLines', label: '군 경계' },
  { key: 'supply', label: '보급선' },
  { key: 'fog', label: '시야' },
  { key: 'corpsRoutes', label: '부대 경로' },
  { key: 'cityNames', label: '도시 이름' },
];

/** 켠 층은 사람마다 브라우저에 남긴다(설계서 §4.4). 편의일 뿐이라 못 읽으면 기본값으로 그린다. */
export const MAP_LAYERS_STORAGE_KEY = 'opensamguk.map.layers.v1';

/** 남긴 글 → 층. 모르는 키 · 참거짓이 아닌 값은 버리고 빠진 키는 기본값으로 채운다. */
export function parseStoredLayers(raw: string | null, defaults: MapLayers): MapLayers {
  if (!raw) return defaults;
  let stored: unknown;
  try {
    stored = JSON.parse(raw);
  } catch {
    return defaults;
  }
  if (!stored || typeof stored !== 'object') return defaults;
  const next: MapLayers = { ...defaults };
  for (const key of Object.keys(defaults) as MapLayerKey[]) {
    const value = (stored as Record<string, unknown>)[key];
    if (typeof value === 'boolean') next[key] = value;
  }
  return next;
}

/** 층 상태 + 브라우저에 남기기. 첫 그림은 기본값(서버 그림과 같게), 붙은 뒤 남긴 값을 읽는다. */
export function useStoredMapLayers(defaults: MapLayers): [MapLayers, (next: MapLayers) => void] {
  const [layers, setLayers] = useState<MapLayers>(defaults);
  useEffect(() => {
    try {
      setLayers(parseStoredLayers(window.localStorage.getItem(MAP_LAYERS_STORAGE_KEY), defaults));
    } catch {
      // 저장소를 막은 브라우저(사생활 보호 창 등): 기본값 그대로
    }
  }, []); // eslint-disable-line react-hooks/exhaustive-deps
  const change = (next: MapLayers) => {
    setLayers(next);
    try {
      window.localStorage.setItem(MAP_LAYERS_STORAGE_KEY, JSON.stringify(next));
    } catch {
      // 남기지 못해도 이번 화면에서는 바뀐 대로 그린다
    }
  };
  return [layers, change];
}

/** 서버 칸이 아직 없는 층. 숨기지 않고 「서버 대기」로 보이며 계약판 행을 단다. */
export interface PendingLayer {
  readonly id: string;
  readonly label: string;
  /** 계약판 행 id(예: K2-08). */
  readonly contract: string;
  /** 「서버 대기 · 계약판 행」 대신 쓸 글(읽기 실패 등). */
  readonly note?: string;
}

export interface MapLayerButtonsProps {
  readonly layers: MapLayers;
  readonly onLayersChange: (layers: MapLayers) => void;
  readonly pending?: readonly PendingLayer[];
  /** 범례 판 내용. */
  readonly legend: ReactNode;
  /** 좁은 화면(모바일): 단추를 세로로 세운다(글자 없는 단추). */
  readonly compact?: boolean;
  /**
   * 열린 판을 화면 틀이 쥘 때(작전실 하단 시트와 「나중에 연 것이 이전 것을 닫는다」, K4 10-01).
   * 넘기면 제어 모드다 — 단추 · Esc 는 onOpenChange 로만 알린다. 넘기지 않으면 스스로 연다.
   */
  readonly open?: MapLayerPanel | null;
  readonly onOpenChange?: (open: MapLayerPanel | null) => void;
  readonly style?: CSSProperties;
}

export type MapLayerPanel = 'layers' | 'legend';
type Open = MapLayerPanel | null;

/** 「지도 레이어」 · 「범례」 단추와 그 판. 한 번에 하나만 열린다. */
export function MapLayerButtons({ layers, onLayersChange, pending = [], legend, compact = false, open: openProp, onOpenChange, style }: MapLayerButtonsProps) {
  const [ownOpen, setOwnOpen] = useState<Open>(null);
  const controlled = openProp !== undefined;
  const open = controlled ? openProp : ownOpen;
  const setOpen = (next: Open) => {
    if (!controlled) setOwnOpen(next);
    onOpenChange?.(next);
  };
  const base = useId();
  const groupRef = useRef<HTMLDivElement>(null);
  // 판은 단추 오른쪽 끝에 맞춰 왼쪽으로 펼친다 — 지도 상자가 좁으면(모바일 작전실 열) 상자 왼쪽 끝을 넘지 않게 줄인다.
  // 하한을 두지 않는다: 모바일 작전실 지도 열은 151이라 하한 160이 판을 상자 밖으로 25 밀었다(CI e2e, 10-01).
  const [panelWidth, setPanelWidth] = useState(280);
  useLayoutEffect(() => {
    if (!open) return;
    const group = groupRef.current;
    const holder = group?.offsetParent as HTMLElement | null | undefined;
    if (!group || !holder) return;
    const room = group.getBoundingClientRect().right - holder.getBoundingClientRect().left - 8;
    if (room > 0) setPanelWidth(Math.min(280, room));
  }, [open]);
  useEffect(() => {
    if (!open) return undefined;
    const onKey = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(null);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps
  const toggle = (which: MapLayerPanel) => setOpen(open === which ? null : which);
  // 데스크톱 · 모바일 모두 단추 아래, 단추 오른쪽 끝에 맞춘다(옆으로 펼치면 좁은 지도에서 왼쪽이 잘렸다)
  const panel: CSSProperties = { position: 'absolute', right: 0, top: 'calc(100% + 8px)', width: panelWidth, zIndex: 1 };
  return (
    <div ref={groupRef} data-map-control="layer-buttons" style={{ position: 'relative', display: 'flex', flexDirection: compact ? 'column' : 'row', gap: 6, ...style }}>
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
          {/* 서버 대기 줄과 같은 이름의 층은 줄을 숨긴다(보급선은 서버가 연결을 주면 진짜 층) */}
          {MAP_LAYER_ROWS.filter((row) => !pending.some((wait) => wait.id === row.key)).map((row) => (
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
            // 좁은 판(모바일 작전실 열 135)에서는 「서버 대기」가 아랫줄로 내려간다 — 이름이 한 글자씩 접히지 않게
            <div key={row.id} data-pending-layer={row.id} style={{ minHeight: 44, display: 'flex', flexWrap: 'wrap', alignItems: 'center', alignContent: 'center', justifyContent: 'space-between', columnGap: 8, rowGap: 2, padding: '6px 14px', color: 'var(--muted)' }}>
              <span style={{ whiteSpace: 'nowrap' }}>{row.label}</span>
              <span style={{ fontSize: 12, whiteSpace: 'nowrap' }}>{row.note ?? `서버 대기 · ${row.contract}`}</span>
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

/** 범례의 선(보드 00c 범례 보급 연결 · 끊김): 짧은 선 + 이름. 색은 토큰 var(--…)만, 끊김은 점선 + 가운데 ×. */
export function LegendLine({ color, label, cut = false }: { readonly color: string; readonly label: string; readonly cut?: boolean }) {
  const stroke = /^var\(--[a-z0-9-]+\)$/.test(color) ? color : 'var(--muted)';
  return (
    <span className="os-chip" style={{ display: 'inline-flex', alignItems: 'center', gap: 6 }}>
      <svg aria-hidden="true" width="24" height="12" viewBox="-12 -6 24 12" style={{ flexShrink: 0 }}>
        <path d="M-12 0H12" stroke={stroke} strokeWidth="2.5" strokeDasharray={cut ? '5 4' : undefined} />
        {cut ? <path d="M-4 -4L4 4M4 -4L-4 4" stroke={stroke} strokeWidth="2.5" /> : null}
      </svg>
      {label}
    </span>
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
          // 색은 세력색(#rrggbb) 또는 토큰 var(--…)만 — 그 밖은 기본색(원장 D90, safeNationColor).
          background: hatched ? 'repeating-linear-gradient(45deg, var(--muted) 0 2px, transparent 2px 4px)'
            : color === undefined ? undefined : /^var\(--[a-z0-9-]+\)$/.test(color) ? color : safeNationColor(color),
        }}
      />
      {label}
    </span>
  );
}
