'use client';

// 작은 지도(K3 v3.1 MapMinimap): 州 개관 그림 + 지금 보는 곳 사각형 + 내 위치 점. 누르면 그리로 옮긴다.
// 개관 격자는 이미 받은 것을 다시 쓴다(추가 받기 없음).
import { useEffect, useRef } from 'react';
import type { Camera, CellPoint, MapShape, Viewport } from './types';

export const MINIMAP_SIZE = { width: 176, height: 153 } as const;

/**
 * 좁은 상자에서는 작은 지도를 두지 않는다: 가로 · 세로 모두 작은 지도가 상자의 반(여백 12 × 2 포함)을 넘으면 숨긴다.
 * 실지도 결함 2 — 모바일 작전실 지도 열이 151px일 때 176px 작은 지도가 조작 단추와 한데 몰렸다. 이 문턱은 K2가 정한 안전장치이고
 * 보드 수치가 아니다(보드 V31은 모바일 작전실에 작은 지도를 두지 않는다 — 화면이 따로 끈다).
 */
export function minimapFits(box: { width: number; height: number }): boolean {
  return box.width >= MINIMAP_SIZE.width * 2 + 24 && box.height >= MINIMAP_SIZE.height * 2 + 24;
}

export interface MapMinimapProps {
  picture: OffscreenCanvas | null;
  shape: MapShape;
  camera: Camera | null;
  viewport: Viewport;
  me?: CellPoint | null;
  meColor?: string | null;
  onJump: (cell: CellPoint) => void;
}

export function MapMinimap({ picture, shape, camera, viewport, me, meColor, onJump }: MapMinimapProps) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !picture) return;
    const dpr = typeof window === 'undefined' ? 1 : window.devicePixelRatio || 1;
    canvas.width = Math.round(MINIMAP_SIZE.width * dpr);
    canvas.height = Math.round(MINIMAP_SIZE.height * dpr);
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.imageSmoothingEnabled = true;
    ctx.drawImage(picture, 0, 0, MINIMAP_SIZE.width, MINIMAP_SIZE.height);
    const sx = MINIMAP_SIZE.width / shape.cols;
    const sy = MINIMAP_SIZE.height / shape.rows;
    if (camera && viewport.width) {
      const w = (viewport.width / camera.zoom) * sx;
      const h = (viewport.height / camera.zoom) * sy;
      ctx.strokeStyle = '#ffd36d';
      ctx.lineWidth = 1.5;
      ctx.strokeRect(camera.center.col * sx - w / 2, camera.center.row * sy - h / 2, Math.max(w, 3), Math.max(h, 3));
    }
    if (me) {
      ctx.beginPath();
      ctx.arc(me.col * sx, me.row * sy, 3.5, 0, Math.PI * 2);
      ctx.fillStyle = meColor ?? '#ece6d8';
      ctx.fill();
      ctx.lineWidth = 1;
      ctx.strokeStyle = '#0c0f0e';
      ctx.stroke();
    }
  }, [picture, shape, camera, viewport, me, meColor]);

  return (
    <button
      type="button"
      // 지도 조작이다 — 「내 위치는 화면 밖」 가장자리 단추가 그 밑에 깔리지 않게 비킨다(MyLocationLayer)
      data-map-control="minimap"
      aria-label="작은 지도 — 누른 곳으로 옮깁니다"
      style={{ padding: 0, border: '1px solid #3d4740', background: '#0c0f0e', cursor: 'pointer', display: 'block', lineHeight: 0 }}
      onPointerDown={(event) => event.stopPropagation()}
      onPointerUp={(event) => event.stopPropagation()}
      onClick={(event) => {
        const rect = event.currentTarget.getBoundingClientRect();
        if (event.detail === 0) {
          if (me) onJump(me); // 키보드로 누르면 내 위치로
          return;
        }
        onJump({
          col: ((event.clientX - rect.left) / rect.width) * shape.cols,
          row: ((event.clientY - rect.top) / rect.height) * shape.rows,
        });
      }}
    >
      <canvas ref={ref} aria-hidden="true" style={{ width: MINIMAP_SIZE.width, height: MINIMAP_SIZE.height, display: 'block', pointerEvents: 'none' }} />
    </button>
  );
}
