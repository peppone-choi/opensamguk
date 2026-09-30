'use client';

// 작은 지도(K3 v3.1 MapMinimap): 州 개관 그림 + 지금 보는 곳 사각형 + 내 위치 점. 누르면 그리로 옮긴다.
// 개관 격자는 이미 받은 것을 다시 쓴다(추가 받기 없음).
import { useEffect, useRef } from 'react';
import type { Camera, CellPoint, MapShape, Viewport } from './types';

export const MINIMAP_SIZE = { width: 176, height: 153 } as const;

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
