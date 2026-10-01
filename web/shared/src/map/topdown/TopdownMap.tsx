'use client';

// 탑다운 지도 React 감싸개: 캔버스 두 장(WebGL2 지형 + 2D 겹층)과 입력(휠 · 끌기 · 핀치 · 키보드).
// 화면 모양(단추 · 카드 · 시트)은 v3.1 설계 승인 뒤 붙인다. 지금은 기능 플래그 뒤 시험용이다.
import { useCallback, useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { clampCamera, fitZoom, levelZoom, nearestStop, restingStop, stepStop, viewLevel, zoomAt, zoomStops } from './camera';
import { Inertia, keyAction, keyPanCells, panBy, pinch, wheelZoomFactor } from './input';
import { DEFAULT_LAYERS, TopdownRenderer, type MapLayers, type TopdownSource, type WorldState } from './renderer';
import type { HitResult } from './hitTest';
import { MapMinimap } from './MapMinimap';
import { loadOverviewPicture } from './overviewPicture';
import type { MyLocation } from './myLocation';
import type { CorpsMarker } from './corps';
import { HAN_MAP_SHAPE, type Camera, type CellPoint, type ViewLevel, type Viewport } from './types';

export interface TopdownMapHandle {
  setLevel: (level: ViewLevel) => void;
  zoomStep: (dir: 1 | -1) => void;
  centerOn: (cell: CellPoint, zoom?: number) => void;
  /**
   * 城으로 이동 + 선택(목록 · 검색에서 고를 때, K4). 그 城 발자국 가운데로 옮기고(현 보기 이상) 누른 것처럼 onSelect 로
   * `{ kind: 'city' }` 를 낸다. 장소 표에 없는 城이면 아무것도 하지 않고 false.
   */
  focusCity: (cityId: number, zoom?: number) => boolean;
}

export interface TopdownMapProps {
  source: TopdownSource;
  world?: WorldState;
  layers?: MapLayers;
  /** 내 위치 표지(M2-11). */
  me?: MyLocation | null;
  /** 부대 표지(K2-08). */
  corps?: readonly CorpsMarker[];
  /** 고른 城(노란 테두리). 화면이 onSelect 로 받은 城을 넘긴다. */
  selectedCityId?: number | null;
  /** 오른쪽 아래 작은 지도(K3 v3.1 MapMinimap). */
  minimap?: boolean;
  /** 'fit' shows the whole map (州 보기); otherwise centre and zoom (CSS px per cell). */
  initialView?: 'fit' | { center: CellPoint; zoom: number };
  onSelect?: (hit: HitResult) => void;
  onViewChange?: (view: { camera: Camera; level: ViewLevel }) => void;
  onReady?: (handle: TopdownMapHandle) => void;
  ariaLabel?: string;
  className?: string;
  style?: CSSProperties;
  children?: ReactNode;
  /**
   * false면 안내문(그릴 수 없음 · 불러오지 못함)을 지도 아래 끝에 그리지 않는다. 화면이 `onStatus`로 받아 제 자리에 띄운다.
   * 로그인 배경처럼 지도 아래 끝이 패널에 가리는 화면용이다.
   */
  notices?: boolean;
  onStatus?: (status: TopdownMapStatus) => void;
}

export type TopdownMapStatus = 'loading' | 'ready' | 'error' | 'unsupported';

/** 지도 안내문. 화면이 제 자리에 띄울 때도 같은 글자를 쓴다. */
export const TOPDOWN_MAP_NOTICE = {
  unsupported: '이 브라우저에서는 지도를 그릴 수 없습니다. 천하 그림만 보입니다.',
  error: '지도를 불러오지 못했습니다. 잠시 뒤 다시 열어 주세요.',
} as const;

type Status = { kind: TopdownMapStatus };

const SETTLE_MS = 150;
const TAP_SLOP_PX = 6;

export function TopdownMap(props: TopdownMapProps) {
  const { source, world, layers = DEFAULT_LAYERS, initialView = 'fit', onSelect, onViewChange, onReady, me = null, minimap = false, corps,
    notices = true, onStatus, selectedCityId = null } = props;
  const boxRef = useRef<HTMLDivElement>(null);
  const glRef = useRef<HTMLCanvasElement>(null);
  const overlayRef = useRef<HTMLCanvasElement>(null);
  const rendererRef = useRef<TopdownRenderer | null>(null);
  const cameraRef = useRef<Camera | null>(null);
  const viewportRef = useRef<Viewport>({ width: 0, height: 0, dpr: 1 });
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [picture, setPicture] = useState<OffscreenCanvas | null>(null);
  const [debug, setDebug] = useState<{ zoom: number; level: ViewLevel; col: number; row: number }>();
  const callbacks = useRef({ onSelect, onViewChange, onReady });
  callbacks.current = { onSelect, onViewChange, onReady };
  const statusCallback = useRef(onStatus);
  statusCallback.current = onStatus;
  useEffect(() => {
    statusCallback.current?.(status.kind);
  }, [status.kind]);

  const shape = HAN_MAP_SHAPE;

  const apply = useCallback((next: Camera) => {
    const viewport = viewportRef.current;
    if (!viewport.width) return;
    const camera = clampCamera(next, viewport, shape);
    cameraRef.current = camera;
    rendererRef.current?.setView(camera, viewport);
    const level = viewLevel(camera.zoom);
    setDebug({ zoom: camera.zoom, level, col: camera.center.col, row: camera.center.row });
    callbacks.current.onViewChange?.({ camera, level });
  }, [shape]);

  // 렌더러 만들기 · 싣기
  useEffect(() => {
    const gl = glRef.current;
    const overlay = overlayRef.current;
    if (!gl || !overlay) return undefined;
    let cancelled = false;
    setStatus({ kind: 'loading' });
    setPicture(null);
    let renderer: TopdownRenderer;
    try {
      renderer = new TopdownRenderer(gl, overlay);
    } catch {
      setStatus({ kind: 'unsupported' });
      // WebGL2가 없으면 천하 그림 한 장만 보인다. 장소 목록이 없는 화면(로그인 배경)도 있어 안내는 그림만 말한다.
      loadOverviewPicture(source.bakeUrl, source.kitUrl).then((next) => { if (!cancelled) setPicture(next); }, () => undefined);
      return () => {
        cancelled = true;
      };
    }
    rendererRef.current = renderer;
    // 서버 원문 · 파일 이름은 화면에 싣지 않고 콘솔에만 남긴다(작전실 · 로그인 안내 문구와 같은 원칙).
    const fail = (error: unknown) => {
      if (cancelled) return;
      console.warn('[탑다운 지도] 불러오지 못함', error);
      setStatus({ kind: 'error' });
    };
    renderer.load(source).then(() => {
      if (cancelled) return;
      setStatus({ kind: 'ready' });
      // 뒤로 미룬 자료(밉 · 개관 · 장소 · 그림 판)가 실패하면 지형이 보여도 오류로 알린다
      renderer.complete.then(() => { if (!cancelled) setPicture(renderer.overviewPicture()); }, fail);
      if (cameraRef.current) renderer.setView(cameraRef.current, viewportRef.current);
      // 단추 · 화면이 옮기는 카메라는 손으로 밀던 관성을 끊고 시작한다 — 안 끊으면 「내 위치로」 뒤에도 미끄러져 자리에서 벗어난다
      callbacks.current.onReady?.({
        setLevel: (level) => {
          stopGlide();
          const cam = cameraRef.current;
          if (cam) apply({ center: cam.center, zoom: levelZoom(level, viewportRef.current, shape) });
        },
        zoomStep: (dir) => {
          stopGlide();
          const cam = cameraRef.current;
          if (cam) apply({ center: cam.center, zoom: stepStop(cam.zoom, zoomStops(viewportRef.current, shape), dir) });
        },
        centerOn: (cell, zoom) => {
          stopGlide();
          const cam = cameraRef.current;
          apply({ center: cell, zoom: zoom ?? cam?.zoom ?? 16 });
        },
        focusCity: (cityId, zoom) => {
          const city = renderer.placesData?.cities.find((entry) => entry.id === cityId);
          if (!city) return false;
          stopGlide();
          const { originCol, originRow, span } = city.footprint;
          const centre = { col: originCol + span / 2, row: originRow + span / 2 };
          apply({ center: centre, zoom: zoom ?? Math.max(cameraRef.current?.zoom ?? 0, levelZoom('county', viewportRef.current, shape)) });
          callbacks.current.onSelect?.({ kind: 'city', id: cityId, cell: { col: Math.floor(centre.col), row: Math.floor(centre.row) } });
          return true;
        },
      });
    }, fail);
    return () => {
      cancelled = true;
      renderer.dispose();
      rendererRef.current = null;
    };
  }, [source.bakeUrl, source.kitUrl]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => {
    if (world) rendererRef.current?.setWorld(world);
  }, [world, status.kind]);

  useEffect(() => {
    rendererRef.current?.setLayers(layers);
  }, [layers, status.kind]);

  useEffect(() => {
    rendererRef.current?.setMe(me);
  }, [me, status.kind]);

  useEffect(() => {
    rendererRef.current?.setCorps(corps ?? []);
  }, [corps, status.kind]);

  useEffect(() => {
    rendererRef.current?.setSelectedCity(selectedCityId);
  }, [selectedCityId, status.kind]);

  // 크기 · 기기 픽셀 비율
  useEffect(() => {
    const box = boxRef.current;
    if (!box) return undefined;
    const measure = () => {
      const rect = box.getBoundingClientRect();
      viewportRef.current = { width: rect.width, height: rect.height, dpr: window.devicePixelRatio || 1 };
      if (!cameraRef.current) {
        const start = initialView === 'fit'
          ? { center: { col: shape.cols / 2, row: shape.rows / 2 }, zoom: fitZoom(viewportRef.current, shape) }
          : initialView;
        apply(start);
      } else {
        apply(cameraRef.current);
      }
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(box);
    return () => observer.disconnect();
  }, [apply]); // eslint-disable-line react-hooks/exhaustive-deps

  // 휠: 커서 기준 연속 확대 → 멈추면 가까운 멈춤 자리로 붙는다
  useEffect(() => {
    const box = boxRef.current;
    if (!box) return undefined;
    let settleTimer = 0;
    let burstFrom = 0;
    const onWheel = (event: WheelEvent) => {
      event.preventDefault();
      const cam = cameraRef.current;
      if (!cam) return;
      if (!settleTimer) burstFrom = cam.zoom;
      const rect = box.getBoundingClientRect();
      const anchor = { x: event.clientX - rect.left, y: event.clientY - rect.top };
      const factor = wheelZoomFactor(event.deltaY, event.deltaMode as 0 | 1 | 2);
      apply(zoomAt(cam, anchor, cam.zoom * factor, viewportRef.current, shape));
      window.clearTimeout(settleTimer);
      // 멈추면 굴린 방향의 다음 멈춤 자리로 붙는다(가까운 쪽이면 한 칸 굴림이 맞춤 보기로 되돌아갔다)
      settleTimer = window.setTimeout(() => {
        settleTimer = 0;
        const now = cameraRef.current;
        if (!now) return;
        const dir = now.zoom > burstFrom ? 1 : now.zoom < burstFrom ? -1 : 0;
        apply(zoomAt(now, anchor, restingStop(now.zoom, zoomStops(viewportRef.current, shape), dir), viewportRef.current, shape));
      }, SETTLE_MS);
    };
    box.addEventListener('wheel', onWheel, { passive: false });
    return () => {
      box.removeEventListener('wheel', onWheel);
      window.clearTimeout(settleTimer);
    };
  }, [apply, shape]);

  // 끌기 · 핀치 · 누르기
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const gesture = useRef({ moved: 0, startX: 0, startY: 0, inertia: new Inertia(), raf: 0 });
  function stopGlide() {
    cancelAnimationFrame(gesture.current.raf);
    gesture.current.inertia.stop();
  }

  const local = (event: React.PointerEvent) => {
    const rect = boxRef.current!.getBoundingClientRect();
    return { x: event.clientX - rect.left, y: event.clientY - rect.top };
  };

  const onPointerDown = (event: React.PointerEvent<HTMLDivElement>) => {
    event.currentTarget.setPointerCapture(event.pointerId);
    const point = local(event);
    pointers.current.set(event.pointerId, point);
    const g = gesture.current;
    cancelAnimationFrame(g.raf);
    if (pointers.current.size === 1) {
      g.moved = 0;
      g.startX = point.x;
      g.startY = point.y;
      g.inertia.stop();
      g.inertia.track(0, 0, event.timeStamp);
    }
  };

  const onPointerMove = (event: React.PointerEvent<HTMLDivElement>) => {
    const previous = pointers.current.get(event.pointerId);
    if (!previous) return;
    const point = local(event);
    const cam = cameraRef.current;
    const g = gesture.current;
    if (pointers.current.size === 2 && cam) {
      const [a, b] = [...pointers.current.entries()];
      const prev: [{ x: number; y: number }, { x: number; y: number }] = [a[1], b[1]];
      pointers.current.set(event.pointerId, point);
      const [c, d] = [...pointers.current.values()];
      apply(pinch(prev, [c, d], cam, viewportRef.current, shape));
      g.moved += TAP_SLOP_PX;
      return;
    }
    pointers.current.set(event.pointerId, point);
    if (!cam) return;
    const dx = point.x - previous.x;
    const dy = point.y - previous.y;
    g.moved = Math.max(g.moved, Math.hypot(point.x - g.startX, point.y - g.startY));
    g.inertia.track(dx, dy, event.timeStamp);
    apply(panBy(cam, dx, dy));
  };

  const onPointerUp = (event: React.PointerEvent<HTMLDivElement>) => {
    const point = local(event);
    const wasPinch = pointers.current.size > 1;
    pointers.current.delete(event.pointerId);
    const g = gesture.current;
    if (wasPinch) {
      const cam = cameraRef.current;
      if (cam && pointers.current.size === 0) apply({ center: cam.center, zoom: nearestStop(cam.zoom, zoomStops(viewportRef.current, shape)) });
      return;
    }
    if (g.moved < TAP_SLOP_PX) {
      const hit = rendererRef.current?.hit(point);
      if (hit) callbacks.current.onSelect?.(hit);
      return;
    }
    g.inertia.release(event.timeStamp);
    let last = performance.now();
    const glide = (now: number) => {
      const step = g.inertia.step(now - last);
      last = now;
      const cam = cameraRef.current;
      if (cam) apply(panBy(cam, step.dx, step.dy));
      if (!g.inertia.done) g.raf = requestAnimationFrame(glide);
    };
    g.raf = requestAnimationFrame(glide);
  };

  // 취소(스크롤 가로채기 · 시스템 제스처)는 누르기로 치지 않는다: 손가락만 지우고 끝낸다
  const onPointerCancel = (event: React.PointerEvent<HTMLDivElement>) => {
    pointers.current.delete(event.pointerId);
    gesture.current.inertia.stop();
    const cam = cameraRef.current;
    if (cam && pointers.current.size === 0) apply({ center: cam.center, zoom: nearestStop(cam.zoom, zoomStops(viewportRef.current, shape)) });
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    const action = keyAction(event.key);
    const cam = cameraRef.current;
    if (!action || !cam) return;
    event.preventDefault();
    if (action.type === 'pan') {
      const step = keyPanCells(viewportRef.current, cam.zoom);
      apply({ center: { col: cam.center.col + action.dx * step, row: cam.center.row + action.dy * step }, zoom: cam.zoom });
    } else if (action.type === 'zoom') {
      apply({ center: cam.center, zoom: stepStop(cam.zoom, zoomStops(viewportRef.current, shape), action.dir) });
    } else {
      callbacks.current.onSelect?.({ kind: 'none', id: null, cell: { col: Math.floor(cam.center.col), row: Math.floor(cam.center.row) } });
    }
  };

  const fill: CSSProperties = { position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' };
  return (
    <div
      ref={boxRef}
      className={props.className}
      role="application"
      aria-label={props.ariaLabel ?? '천하 지도 — 방향키로 옮기고 + · − 로 확대합니다'}
      tabIndex={0}
      data-map-renderer="topdown"
      data-map-status={status.kind}
      data-map-zoom={debug?.zoom.toFixed(3)}
      data-map-level={debug?.level}
      data-map-center={debug ? `${debug.col.toFixed(1)},${debug.row.toFixed(1)}` : undefined}
      data-map-selected={selectedCityId ?? undefined}
      data-map-corps={corps?.length ?? 0}
      style={{ position: 'relative', overflow: 'hidden', touchAction: 'none', userSelect: 'none', background: '#0c0f0e', ...props.style }}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerUp}
      onPointerCancel={onPointerCancel}
      onKeyDown={onKeyDown}
    >
      <canvas ref={glRef} style={fill} />
      <canvas ref={overlayRef} style={{ ...fill, pointerEvents: 'none' }} />
      {status.kind === 'unsupported' && picture && <FallbackPicture picture={picture} />}
      {notices && status.kind === 'unsupported' && (
        <p role="status" style={{ position: 'absolute', inset: 'auto 16px 16px 16px', margin: 0, color: '#ece6d8' }}>
          {TOPDOWN_MAP_NOTICE.unsupported}
        </p>
      )}
      {notices && status.kind === 'error' && (
        <p role="alert" style={{ position: 'absolute', inset: 'auto 16px 16px 16px', margin: 0, color: '#e08a7c' }}>
          {TOPDOWN_MAP_NOTICE.error}
        </p>
      )}
      {minimap && picture && status.kind !== 'unsupported' && (
        <div style={{ position: 'absolute', right: 12, bottom: 12, zIndex: 'var(--z-map-ctrl, 20)' }}>
          <MapMinimap
            picture={picture}
            shape={shape}
            camera={cameraRef.current}
            viewport={viewportRef.current}
            me={me?.cell ?? null}
            meColor={me?.nationColor ?? null}
            onJump={(cell) => apply({ center: cell, zoom: cameraRef.current?.zoom ?? 16 })}
          />
        </div>
      )}
      {props.children}
    </div>
  );
}

function FallbackPicture({ picture }: { picture: OffscreenCanvas }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    canvas.width = picture.width;
    canvas.height = picture.height;
    canvas.getContext('2d')?.drawImage(picture, 0, 0);
  }, [picture]);
  return (
    <canvas
      ref={ref}
      role="img"
      aria-label="천하 지도(그림만)"
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'contain', imageRendering: 'pixelated' }}
    />
  );
}
