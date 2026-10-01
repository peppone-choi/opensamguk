'use client';

// 내 위치 표지(원장 M2-11, 보드 V31SystemMarker · V31SystemMMarker, 사용자 09-30 「자신의 위치를 나타내는 아이콘이 맵에 있어야」).
// - 핀: 내 장수 초상(원형 48, 토큰 보드 「모서리 0」의 예외) + 국가색 링 3 + 금색 테 + 핀 끝. 핀 끝이 실제 자리.
// - 모든 보기 수준에서 같은 화면 크기, 지도 이름표 · 城 · 깃발보다 위(지도 위 DOM 층). 현 보기에서만 「내 위치 · 성 안」 꼬리표.
// - 화면 밖이면 그 방향 가장자리에 「내 위치」 단추(44 × 52) + 거리. 누르면 그리로.
// - 누르면 내 장수 카드(화면 틀이 연다). 대상 고르는 중(inert)에는 표지만 보이고 고르기를 막지 않는다.
// 지도 위(TopdownMap 형제)에 같은 크기로 겹쳐 놓고, TopdownMap onViewChange 의 카메라를 받는다.
import { useEffect, useLayoutEffect, useRef, useState, type CSSProperties } from 'react';
import { Icon } from '../../Icon';
import { usePortraitResolver } from '../../Portrait';
import { cellToScreen } from './camera';
import { MY_LOCATION_PIN, type MyLocationState } from './myLocation';
import type { Camera, CellPoint, ViewLevel } from './types';

const PIN = MY_LOCATION_PIN.width;
const PIN_H = MY_LOCATION_PIN.height;
const EDGE_W = 44;
const EDGE_H = 52;
const GOLD = '#ffd36d';
const NO_NATION = '#8e8879';

export const MY_LOCATION_STATE_LABEL: Record<MyLocationState, string> = {
  IN_CITY: '성 안',
  FIELD: '성 밖',
  WITH_CORPS: '군단과 함께',
  MARCHING: '이동 중',
};

export interface MyLocationPin {
  /**
   * 핀 끝이 설 지도 자리(칸 좌표, 연속값). 城이면 발자국 가운데(`cityCell`), 칸 하나면 그 칸 가운데(col + 0.5).
   * 칸 번호가 아니다 — 여기서 0.5를 더하지 않는다.
   */
  readonly at: CellPoint;
  readonly state: MyLocationState;
  /** 내 장수 이름(초상이 없으면 첫 글자). */
  readonly name: string;
  /** 내 세력 색. 재야 · 모르면 null(무소속 회색 링 — 색을 짓지 않는다). */
  readonly nationColor: string | null;
  readonly picture?: string | null;
  readonly imageServer?: number | null;
}

export interface MyLocationLayerProps {
  /** 지금 카메라(TopdownMap onViewChange). 아직 모르면 그리지 않는다. */
  readonly camera: Camera | null;
  /** 지금 보기 수준. 꼬리표는 현 보기에서만. */
  readonly level?: ViewLevel | null;
  readonly me: MyLocationPin | null;
  /** 핀을 누르면 — 내 장수 카드(화면 틀). */
  readonly onPick?: () => void;
  /** 화면 밖 단추를 누르면 — 부르는 쪽이 내 자리로 옮긴다. */
  readonly onGo?: () => void;
  /** 대상 고르는 중: 표지만 보이고 누르지 않는다(고르기를 막지 않음). */
  readonly inert?: boolean;
  /** 화면 틀이 덮은 폭(서랍 · 하단 시트). 덮인 곳은 화면 밖으로 치고 화살표를 덮이지 않은 가장자리에 둔다. */
  readonly edgeInset?: { readonly left?: number; readonly bottom?: number };
  /** 서버가 아직 주지 않는 자리 상태를 기다리는 계약판 행(예: U-04). */
  readonly serverWait?: string;
}

interface Placement { kind: 'pin'; x: number; y: number }
interface Edge { kind: 'edge'; x: number; y: number; angle: number; side: 'left' | 'right' | 'top' | 'bottom' }

/** 핀(끝 = target)이 덮이지 않은 화면에 다 들어오면 핀, 아니면 가운데에서 target 쪽 반직선이 가장자리를 나가는 자리의 단추. */
export function placePin(target: { x: number; y: number }, box: { left: number; top: number; right: number; bottom: number }): Placement | Edge {
  if (target.x - PIN / 2 >= box.left && target.x + PIN / 2 <= box.right && target.y - PIN_H >= box.top && target.y <= box.bottom) {
    return { kind: 'pin', x: target.x, y: target.y };
  }
  const cx = (box.left + box.right) / 2;
  const cy = (box.top + box.bottom) / 2;
  const dx = target.x - cx;
  const dy = target.y - cy;
  const halfW = Math.max((box.right - box.left) / 2 - EDGE_W / 2 - 4, 1);
  const halfH = Math.max((box.bottom - box.top) / 2 - EDGE_H / 2 - 4, 1);
  const sx = dx === 0 ? Infinity : halfW / Math.abs(dx);
  const sy = dy === 0 ? Infinity : halfH / Math.abs(dy);
  const scale = Math.min(sx, sy);
  const side = sx <= sy ? (dx > 0 ? 'right' : 'left') : (dy > 0 ? 'bottom' : 'top');
  return { kind: 'edge', x: cx + dx * scale, y: cy + dy * scale, angle: Math.atan2(dy, dx), side };
}

interface Box { left: number; top: number; right: number; bottom: number }

/**
 * 가장자리 단추(가운데 x, y)가 지도 조작(`[data-map-control]`, 같은 지도 상자 안 화면 좌표)과 겹치면 안쪽으로 비킨다 —
 * 왼쪽 · 오른쪽 가장자리는 가로로, 위 · 아래는 세로로. 모바일 좁은 지도에서 왼쪽 화살표가 보기 단추 밑에 깔렸다.
 */
export function nudgeEdge(edge: Edge, obstacles: readonly Box[], box: Box): { x: number; y: number } {
  let { x, y } = edge;
  const hits = (o: Box) => x - EDGE_W / 2 < o.right && x + EDGE_W / 2 > o.left && y - EDGE_H / 2 < o.bottom && y + EDGE_H / 2 > o.top;
  for (let pass = 0; pass < obstacles.length; pass += 1) {
    const o = obstacles.find(hits);
    if (!o) break;
    if (edge.side === 'left') x = o.right + 4 + EDGE_W / 2;
    else if (edge.side === 'right') x = o.left - 4 - EDGE_W / 2;
    else if (edge.side === 'top') y = o.bottom + 4 + EDGE_H / 2;
    else y = o.top - 4 - EDGE_H / 2;
  }
  return {
    x: Math.min(Math.max(x, box.left + EDGE_W / 2), box.right - EDGE_W / 2),
    y: Math.min(Math.max(y, box.top + EDGE_H / 2), box.bottom - EDGE_H / 2),
  };
}

const sameBoxes = (a: readonly Box[], b: readonly Box[]) =>
  a.length === b.length && a.every((r, i) => r.left === b[i].left && r.top === b[i].top && r.right === b[i].right && r.bottom === b[i].bottom);

export function MyLocationLayer({ camera, level = null, me, onPick, onGo, inert = false, edgeInset, serverWait }: MyLocationLayerProps) {
  const boxRef = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState<{ width: number; height: number } | null>(null);
  const resolver = usePortraitResolver();
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
  const target = camera && viewport && me ? cellToScreen(me.at, camera, viewport) : null;
  const box = viewport ? { left: edgeInset?.left ?? 0, top: 0, right: viewport.width, bottom: viewport.height - (edgeInset?.bottom ?? 0) } : null;
  const place = target && box ? placePin(target, box) : null;
  // 가장자리 단추일 때만 같은 지도 상자의 조작 단추 자리를 잰다(지도 위 층 → 위로 올라가며 조작을 담은 상자를 찾는다)
  const [obstacles, setObstacles] = useState<Box[]>([]);
  useLayoutEffect(() => {
    const layer = boxRef.current;
    if (!layer || place?.kind !== 'edge') return;
    let host: HTMLElement | null = layer.parentElement;
    for (let depth = 0; host && depth < 3 && !host.querySelector('[data-map-control]'); depth += 1) host = host.parentElement;
    const origin = layer.getBoundingClientRect();
    const next = host ? [...host.querySelectorAll<HTMLElement>('[data-map-control]')].map((element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left - origin.left, top: rect.top - origin.top, right: rect.right - origin.left, bottom: rect.bottom - origin.top };
    }) : [];
    setObstacles((was) => (sameBoxes(was, next) ? was : next));
  });
  const edgeAt = place?.kind === 'edge' && box ? nudgeEdge(place, obstacles, box) : null;
  const stateLabel = me ? MY_LOCATION_STATE_LABEL[me.state] : '';
  const ring = me?.nationColor ?? NO_NATION;
  const cells = camera && me ? Math.round(Math.hypot(me.at.col - camera.center.col, me.at.row - camera.center.row)) : 0;
  const src = me?.picture ? resolver.portraitVariantUrl(me.picture, me.imageServer ?? null, 'icon') : null;

  return (
    <div ref={boxRef} data-my-location={place ? place.kind : 'none'} data-my-location-state={me?.state} data-server-wait={serverWait}
      style={{ position: 'absolute', inset: 0, pointerEvents: 'none', overflow: 'hidden' }}>
      {me && place?.kind === 'pin' ? (
        <button
          type="button"
          aria-label={`내 위치 — ${me.name}, ${stateLabel}. 누르면 내 장수 카드`}
          tabIndex={inert ? -1 : 0}
          onClick={inert ? undefined : onPick}
          style={{ position: 'absolute', left: place.x - PIN / 2, top: place.y - PIN_H, width: PIN, height: PIN_H, padding: 0, border: 0,
            background: 'transparent', font: 'inherit', cursor: inert ? 'default' : 'pointer', pointerEvents: inert ? 'none' : 'auto' }}
        >
          <span style={{ position: 'absolute', left: 0, top: 0, width: PIN, height: PIN, boxSizing: 'border-box', borderRadius: '50%',
            border: `3px solid ${ring}`, boxShadow: `0 0 0 2px ${GOLD}, 0 4px 12px rgba(0,0,0,0.6)`, overflow: 'hidden', background: '#141816',
            display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            {src ? <img src={src} alt="" width={PIN} height={PIN} onError={resolver.onPortraitError}
              style={{ width: '100%', height: '100%', objectFit: 'cover', objectPosition: 'top center', display: 'block' }} />
              : <span aria-hidden style={{ fontFamily: "'Noto Serif KR', serif", fontWeight: 900, fontSize: 20, color: '#ece6d8' }}>{[...me.name][0] ?? ''}</span>}
          </span>
          <span aria-hidden style={{ position: 'absolute', left: PIN / 2 - 6, top: PIN - 2, width: 0, height: 0,
            borderLeft: '6px solid transparent', borderRight: '6px solid transparent', borderTop: `14px solid ${GOLD}` }} />
          {level === 'county' ? (
            <span aria-hidden style={{ position: 'absolute', left: PIN + 4, top: 10, height: 24, padding: '0 8px', display: 'inline-flex',
              alignItems: 'center', fontSize: 12, fontWeight: 700, color: '#161410', background: GOLD, border: '1px solid #9c7f3f',
              whiteSpace: 'nowrap' }}>{`내 위치 · ${stateLabel}`}</span>
          ) : null}
        </button>
      ) : null}
      {me && place?.kind === 'edge' ? (
        <button
          type="button"
          aria-label={`내 위치는 화면 밖 — ${cells}칸, 누르면 그리로`}
          tabIndex={inert ? -1 : 0}
          onClick={inert ? undefined : onGo}
          data-edge-side={place.side}
          style={edgeStyle(edgeAt ?? place, ring, inert)}
        >
          <span aria-hidden style={{ display: 'inline-flex', transform: `rotate(${(place.angle * 180) / Math.PI}deg)` }}>
            <Icon name="arrow-right" size={16} />
          </span>
          <span style={{ fontSize: 11, fontWeight: 700, whiteSpace: 'nowrap' }}>내 위치</span>
          <span style={{ fontSize: 10, fontFamily: 'ui-monospace, monospace' }}>{`${cells}칸`}</span>
        </button>
      ) : null}
    </div>
  );
}

function edgeStyle(edge: { x: number; y: number }, ring: string, inert: boolean): CSSProperties {
  return {
    position: 'absolute', left: edge.x - EDGE_W / 2, top: edge.y - EDGE_H / 2, minWidth: EDGE_W, height: EDGE_H, padding: '2px 6px',
    display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 1, font: 'inherit', color: '#161410',
    background: GOLD, border: `2px solid ${ring}`, cursor: inert ? 'default' : 'pointer', pointerEvents: inert ? 'none' : 'auto',
  };
}
