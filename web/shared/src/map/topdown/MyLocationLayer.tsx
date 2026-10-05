'use client';

// 내 위치 표지(원장 M2-11, 보드 V31SystemMarker · V31SystemMMarker, 사용자 09-30 「자신의 위치를 나타내는 아이콘이 맵에 있어야」).
// - 핀: 내 장수 초상(원형 48, 토큰 보드 「모서리 0」의 예외) + 국가색 링 3 + 금색 테 + 핀 끝. 핀 끝이 실제 자리.
// - 모든 보기 수준에서 같은 화면 크기, 지도 이름표 · 城 · 깃발보다 위(지도 위 DOM 층). 현 보기에서만 「내 위치 · 성 안」 꼬리표.
// - 화면 밖이면 그 방향 가장자리에 「내 위치」 단추(44 × 52) + 거리. 누르면 그리로.
// - 누르면 내 장수 카드(화면 틀이 연다). 대상 고르는 중(inert)에는 표지만 보이고 고르기를 막지 않는다.
// 지도 위(TopdownMap 형제)에 같은 크기로 겹쳐 놓고, TopdownMap onViewChange 의 카메라를 받는다.
import { useCallback, useEffect, useLayoutEffect, useRef, useState, type CSSProperties, type RefObject } from 'react';
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

/** 지도 위 층에서 위로 올라가며(3칸까지) 지도 조작(`[data-map-control]`)을 담은 상자를 찾는다. */
function controlsHost(layer: HTMLElement | null): HTMLElement | null {
  let host: HTMLElement | null = layer?.parentElement ?? null;
  for (let depth = 0; host && depth < 3 && !host.querySelector('[data-map-control]'); depth += 1) host = host.parentElement;
  return host;
}

const sameBoxes = (a: readonly Box[], b: readonly Box[]) =>
  a.length === b.length && a.every((r, i) => r.left === b[i].left && r.top === b[i].top && r.right === b[i].right && r.bottom === b[i].bottom);

/**
 * 같은 지도 상자의 조작 단추(`[data-map-control]`) 자리 — 가장자리 단추가 그 밑에 깔리지 않게 비킨다.
 * 카메라 프레임마다 재지 않는다: 끌기 중 매 프레임 레이아웃 효과에서 재어 React 작업 하나에 40ms CPU(강제 레이아웃)를 썼다(M2-10 측정, 10-04).
 * 조작 자리는 카메라가 아니라 상자 크기 · 조작 크기 · 가장자리 여백(서랍이 보기 단추를 함께 옮긴다)에 따라 바뀐다.
 * - 크기: ResizeObserver 콜백에서 잰다. 레이아웃이 막 끝난 때라 재기가 강제 레이아웃을 부르지 않는다(처음 붙을 때도 한 번 온다).
 * - 여백: 크기는 그대로라 ResizeObserver 가 못 본다. 드문 일(서랍 열고 닫기)이라 그 자리에서 잰다.
 */
function useControlObstacles(boxRef: RefObject<HTMLDivElement | null>, edgeInset: MyLocationLayerProps['edgeInset'], edge: boolean): Box[] | null {
  const [obstacles, setObstacles] = useState<Box[] | null>(null);
  const measureObstacles = useCallback(() => {
    const layer = boxRef.current;
    if (!layer) return;
    const host = controlsHost(layer);
    const origin = layer.getBoundingClientRect();
    const next = host ? [...host.querySelectorAll<HTMLElement>('[data-map-control]')].map((element) => {
      const rect = element.getBoundingClientRect();
      return { left: rect.left - origin.left, top: rect.top - origin.top, right: rect.right - origin.left, bottom: rect.bottom - origin.top };
    }) : [];
    setObstacles((was) => (was && sameBoxes(was, next) ? was : next));
  }, []);
  useEffect(() => {
    const layer = boxRef.current;
    if (!layer) return undefined;
    const observer = new ResizeObserver(measureObstacles);
    observer.observe(layer);
    const host = controlsHost(layer);
    for (const element of host?.querySelectorAll('[data-map-control]') ?? []) observer.observe(element);
    // 늦게 붙는 조작도 잰다 — 작은 지도는 개관 그림을 받은 뒤에 선다. 붙으면 ResizeObserver 에 걸어 첫 콜백(레이아웃 직후)에서 재고,
    // 떨어지면 그 자리에서 다시 잰다. 지도 위 DOM 은 끌기 중에 자식이 거의 바뀌지 않아(핀은 style 만) 이 감시는 드물게 돈다.
    const controlsIn = (nodes: NodeList) => [...nodes].flatMap((node) => (node instanceof Element
      ? [...(node.matches('[data-map-control]') ? [node] : []), ...node.querySelectorAll('[data-map-control]')] : []));
    const mutations = host ? new MutationObserver((records) => {
      let removed = false;
      for (const record of records) {
        for (const element of controlsIn(record.addedNodes)) observer.observe(element);
        if (controlsIn(record.removedNodes).length > 0) removed = true;
      }
      if (removed) measureObstacles();
    }) : null;
    if (host) mutations?.observe(host, { childList: true, subtree: true });
    return () => {
      observer.disconnect();
      mutations?.disconnect();
    };
  }, [measureObstacles]);
  const inset = useRef<string | null>(null);
  useLayoutEffect(() => {
    const key = `${edgeInset?.left ?? 0},${edgeInset?.bottom ?? 0}`;
    const changed = inset.current !== null && inset.current !== key;
    inset.current = key;
    if (changed) measureObstacles();
  }, [edgeInset?.left, edgeInset?.bottom, measureObstacles]);
  // 아직 한 번도 못 쟀는데(ResizeObserver 첫 콜백 전) 가장자리 단추가 서면 그때 한 번 잰다
  useLayoutEffect(() => {
    if (edge && obstacles === null) measureObstacles();
  }, [edge, obstacles, measureObstacles]);
  return obstacles;
}

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
  const obstacles = useControlObstacles(boxRef, edgeInset, place?.kind === 'edge');
  const edgeAt = place?.kind === 'edge' && box ? nudgeEdge(place, obstacles ?? [], box) : null;
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
          // 보이기 · 키보드(Tab · Enter) 전용 — 누르기는 지도 렌더러 히트(kind 'me')가 받는다. 단추가 포인터를 받으면 핀 위에서
          // 시작한 끌기 · 휠 · 핀치가 지도로 가지 않는다(핀은 처음 열 때 지도 가운데에 선다).
          style={{ position: 'absolute', left: place.x - PIN / 2, top: place.y - PIN_H, width: PIN, height: PIN_H, padding: 0, border: 0,
            background: 'transparent', font: 'inherit', pointerEvents: 'none' }}
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
