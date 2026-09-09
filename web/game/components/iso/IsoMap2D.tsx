'use client';

// 천하 지도 2D 아이소 렌더러 — iso2d 스프라이트 + DEM 높낮이.
//
// 애셋 계약(web/game/public/sprites/iso2d/manifest.json):
//   · 평지 발자국 256×128. 스프라이트는 256×160, 앵커 (128,96).
//   · 코너 순서 N,E,S,W. 이미지 좌표로 N(128,32) E(256,96) S(128,160) W(0,96).
//   · 마스크 0..14 를 미리 그려 뒀다. 한 단차 = 화면 32px.
//   · 흙벽은 skirt-left(남서 변)·skirt-right(남동 변) 두 장, 각 128×96 = 한 단.
//   · 국가색은 "스프라이트 전체 RGB 곱하기"로 넣으라고 매니페스트가 못박았다.
//     칠할 수 있는 깃발 마스크는 제공되지 않는다.
//
// 세력색을 **곱하기**로 넣는 것이 이 판의 핵심이다. 배포본은 불투명 덮어쓰기라
// 지형이 한 픽셀도 안 보인다(reports/opensamguk/tasks/2026-09-09-design-re-review.md).

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  MAX_LEVEL,
  SEAT_ONLY_TILE_PIXELS,
  STEP_SCREEN_PIXELS,
  TERRAIN_ASSET_NAME,
  TILE_SCREEN_HEIGHT,
  TILE_SCREEN_WIDTH,
  isWater,
} from '@opensamguk/ui';
import type { IsoMapData } from './useIsoTileGrid';
import { indexTint, normaliseNationColor, type Rgb } from './tint';
import type { TintMode } from './IsoMap3D';

const SPRITE_BASE = '/sprites/iso2d';
const HALF_W = TILE_SCREEN_WIDTH / 2;
const HALF_H = TILE_SCREEN_HEIGHT / 2;
/** 스프라이트 이미지 안에서 다이아몬드 중심의 위치. */
const ANCHOR_X = 128;
const ANCHOR_Y = 96;

const BUILDING_TIERS: { file: string; from: number; to: number }[] = [
  { file: 'hamlet', from: 4, to: 4 },
  { file: 'county', from: 5, to: 6 },
  { file: 'commandery', from: 7, to: 8 },
  { file: 'capital', from: 9, to: 11 },
];
const OBJECT_ANCHOR_X = 128;
const OBJECT_ANCHOR_Y = 240;

export interface IsoMap2DProps {
  data: IsoMapData;
  tintStrength: number;
  tintMode: TintMode;
  nationColorByOwner?: Record<number, string>;
  onPickTile?: (tile: { col: number; row: number } | null) => void;
  className?: string;
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    image.decoding = 'async';
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error(`${url} 를 못 불러왔다`));
    image.src = url;
  });
}

/**
 * 지도 밖 타일용 어두운 사본. 실루엣(알파)은 그대로 두고 색만 배경 쪽으로 눌러 둔다.
 *
 * 왜 사본을 만드나. globalAlpha 를 낮춰 그리면 화가 알고리즘에서 앞 타일 밑으로 뒤
 * 타일이 비쳐 겹친 자리가 얼룩진다. source-atop 으로 스프라이트 안쪽만 칠하면
 * 실루엣이 유지돼 겹쳐도 깨끗하다. 쓰이는 (재질,마스크) 짝만 한 번씩 만든다.
 */
const dimCache = new WeakMap<HTMLImageElement, HTMLCanvasElement>();

function dimmed(image: HTMLImageElement): HTMLCanvasElement | HTMLImageElement {
  const cached = dimCache.get(image);
  if (cached) return cached;
  const canvas = document.createElement('canvas');
  canvas.width = image.naturalWidth;
  canvas.height = image.naturalHeight;
  const context = canvas.getContext('2d');
  if (!context) return image;
  context.drawImage(image, 0, 0);
  context.globalCompositeOperation = 'source-atop';
  context.fillStyle = 'rgba(12, 15, 14, 0.62)'; // 팔레트 --bg
  context.fillRect(0, 0, canvas.width, canvas.height);
  dimCache.set(image, canvas);
  return canvas;
}

function rgbCss({ r, g, b }: Rgb): string {
  const to = (v: number) => Math.round(Math.max(0, Math.min(1, v)) * 255);
  return `rgb(${to(r)} ${to(g)} ${to(b)})`;
}

export function IsoMap2D({
  data,
  tintStrength,
  tintMode,
  nationColorByOwner,
  onPickTile,
  className,
}: IsoMap2DProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const [sprites, setSprites] = useState<Map<string, HTMLImageElement> | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 실제로 쓰이는 (재질, 마스크) 짝만 받는다. 93장을 전부 받을 필요가 없다.
  const needed = useMemo(() => {
    const { code, mask } = data.grid;
    const keys = new Set<string>();
    for (let i = 0; i < code.length; i += 1) {
      const t = code[i];
      // 물은 마스크 0 한 장뿐이다(매니페스트 terrain[*].masks).
      const m = isWater(t) ? 0 : mask[i];
      keys.add(`terrain/${TERRAIN_ASSET_NAME[t]}-${String(m).padStart(2, '0')}`);
    }
    keys.add('skirts/skirt-left');
    keys.add('skirts/skirt-right');
    for (const tier of BUILDING_TIERS) keys.add(`objects/${tier.file}`);
    return [...keys];
  }, [data]);

  useEffect(() => {
    let cancelled = false;
    setSprites(null);
    setError(null);
    Promise.all(needed.map(async (key) => [key, await loadImage(`${SPRITE_BASE}/${key}.png`)] as const))
      .then((pairs) => {
        if (!cancelled) setSprites(new Map(pairs));
      })
      .catch((e: unknown) => {
        if (!cancelled) setError(e instanceof Error ? e.message : '스프라이트를 못 불러왔다');
      });
    return () => { cancelled = true; };
  }, [needed]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !sprites) return undefined;
    const context = canvas.getContext('2d');
    if (!context) {
      setError('2D 컨텍스트를 못 만들었다');
      return undefined;
    }

    const { grid, owner, parentOwner, cities } = data;
    const { cols, rows, code, mask, baseHeight, playable } = grid;

    // 화면 변환: 배율 1 에서 타일 폭 256px. 시작 배율은 전체가 담기도록 맞춘다.
    let scale = 1;
    let panX = 0;
    let panY = 0;
    let fitted = false;
    let frame = 0;

    const tileScreen = (c: number, r: number): [number, number] => [
      (c - r) * HALF_W,
      (c + r) * HALF_H - baseHeight[r * cols + c] * STEP_SCREEN_PIXELS,
    ];

    const draw = () => {
      frame = 0;
      const dpr = Math.min(window.devicePixelRatio || 1, 2);
      const w = canvas.clientWidth || 1;
      const h = canvas.clientHeight || 1;
      if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
        canvas.width = Math.round(w * dpr);
        canvas.height = Math.round(h * dpr);
      }
      if (!fitted) {
        // 타일 중심 x 는 −(rows−1)·128 … (cols−1)·128, y 는 0 … (cols+rows−2)·64 를 훑는다.
        // 여기에 스프라이트가 중심 밖으로 뻗는 만큼(가로 ±128, 위 96 + 최대 단차)을 더한다.
        const lift = MAX_LEVEL * STEP_SCREEN_PIXELS;
        const minX = -(rows - 1) * HALF_W - HALF_W;
        const maxX = (cols - 1) * HALF_W + HALF_W;
        const minY = -ANCHOR_Y - lift;
        const maxY = (cols + rows - 2) * HALF_H + (160 - ANCHOR_Y);
        scale = Math.min(w / (maxX - minX), h / (maxY - minY)) * 0.95;
        panX = w / 2 - ((minX + maxX) / 2) * scale;
        panY = h / 2 - ((minY + maxY) / 2) * scale;
        fitted = true;
      }

      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      context.fillStyle = '#0c0f0e'; // 팔레트 --bg
      context.fillRect(0, 0, w, h);
      context.save();
      context.translate(panX, panY);
      context.scale(scale, scale);
      context.imageSmoothingEnabled = scale < 1;

      // 화면에 걸리는 타일만 그린다. 화면 → 격자 역변환 네 귀퉁이로 범위를 잡는다.
      const toCell = (sx: number, sy: number): [number, number] => {
        const x = (sx - panX) / scale;
        const y = (sy - panY) / scale;
        return [(x / HALF_W + y / HALF_H) / 2, (y / HALF_H - x / HALF_W) / 2];
      };
      const corners = [toCell(0, 0), toCell(w, 0), toCell(0, h), toCell(w, h)];
      const cs = corners.map((p) => p[0]);
      const rs = corners.map((p) => p[1]);
      // 높이만큼 위로 뜬 타일이 잘리지 않게 뒤쪽으로 넉넉히 잡는다(최대 6단 = 192px).
      const pad = 8;
      const c0 = Math.max(0, Math.floor(Math.min(...cs)) - pad);
      const c1 = Math.min(cols - 1, Math.ceil(Math.max(...cs)) + pad);
      const r0 = Math.max(0, Math.floor(Math.min(...rs)) - pad);
      const r1 = Math.min(rows - 1, Math.ceil(Math.max(...rs)) + pad);

      // 화가 알고리즘: 깊이 d = c+r 오름차순. 같은 d 안에서는 순서가 겹치지 않는다.
      const skirtLeft = sprites.get('skirts/skirt-left')!;
      const skirtRight = sprites.get('skirts/skirt-right')!;
      let drawn = 0;

      for (let d = c0 + r0; d <= c1 + r1; d += 1) {
        const cLo = Math.max(c0, d - r1);
        const cHi = Math.min(c1, d - r0);
        for (let c = cLo; c <= cHi; c += 1) {
          const r = d - c;
          const i = r * cols + c;
          const t = code[i];
          const [x, y] = tileScreen(c, r);
          // 지도 밖 타일은 지형은 그리되 배경 쪽으로 눌러, 플레이 영역과 눈으로 갈린다.
          const out = playable[i] === 0;

          // 흙벽 먼저. 남서·남동 변에서 이웃보다 높은 만큼 한 단씩 쌓는다.
          const h = baseHeight[i];
          const left = out ? dimmed(skirtLeft) : skirtLeft;
          const right = out ? dimmed(skirtRight) : skirtRight;
          const dropSW = r + 1 < rows ? h - baseHeight[(r + 1) * cols + c] : h + 1;
          const dropSE = c + 1 < cols ? h - baseHeight[i + 1] : h + 1;
          for (let k = 0; k < dropSW; k += 1) {
            context.drawImage(left, x - HALF_W, y + k * STEP_SCREEN_PIXELS);
          }
          for (let k = 0; k < dropSE; k += 1) {
            context.drawImage(right, x, y + k * STEP_SCREEN_PIXELS);
          }

          const m = isWater(t) ? 0 : mask[i];
          const sprite = sprites.get(`terrain/${TERRAIN_ASSET_NAME[t]}-${String(m).padStart(2, '0')}`);
          if (sprite) {
            context.drawImage(out ? dimmed(sprite) : sprite, x - ANCHOR_X, y - ANCHOR_Y);
            drawn += 1;
          }
        }
      }

      // 세력색 — 덮어쓰기가 아니라 곱하기다. 지형이 그대로 비쳐 보인다.
      if (tintMode !== 'none' && tintStrength > 0) {
        context.save();
        context.globalCompositeOperation = 'multiply';
        context.globalAlpha = tintStrength;
        for (let r = r0; r <= r1; r += 1) {
          for (let c = c0; c <= c1; c += 1) {
            const i = r * cols + c;
            if (playable[i] === 0 || isWater(code[i])) continue;
            const key = tintMode === 'commandery' ? parentOwner[i] : owner[i];
            if (key < 0) continue;
            const hex = nationColorByOwner?.[key];
            context.fillStyle = rgbCss(hex ? normaliseNationColor(hex) : indexTint(key));
            const [x, y] = tileScreen(c, r);
            context.beginPath();
            context.moveTo(x, y - HALF_H);
            context.lineTo(x + HALF_W, y);
            context.lineTo(x, y + HALF_H);
            context.lineTo(x - HALF_W, y);
            context.closePath();
            context.fill();
          }
        }
        context.restore();
      }

      // 城 — 세력색 위에 그린다. 아이콘까지 곱해지면 등급이 안 읽힌다.
      // 축소 상태에서는 治所만 남긴다(SEAT_ONLY_TILE_PIXELS 주석 참조).
      const seatOnly = scale * TILE_SCREEN_WIDTH < SEAT_ONLY_TILE_PIXELS;
      for (const city of cities) {
        if (seatOnly && !city.seat) continue;
        if (city.col < c0 || city.col > c1 || city.row < r0 || city.row > r1) continue;
        const tier = BUILDING_TIERS.find((t) => city.level >= t.from && city.level <= t.to);
        if (!tier) continue;
        const sprite = sprites.get(`objects/${tier.file}`);
        if (!sprite) continue;
        const [x, y] = tileScreen(city.col, city.row);
        context.drawImage(sprite, x - OBJECT_ANCHOR_X, y - OBJECT_ANCHOR_Y + HALF_H);
      }

      context.restore();
      canvas.dataset.drawnTiles = String(drawn);
      canvas.dataset.seatOnly = String(seatOnly);
    };

    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(draw);
    };

    let dragging = false;
    let lastX = 0;
    let lastY = 0;
    const onDown = (e: PointerEvent) => {
      dragging = true;
      lastX = e.clientX;
      lastY = e.clientY;
      canvas.style.cursor = 'grabbing';
      canvas.setPointerCapture(e.pointerId);
    };
    const onMove = (e: PointerEvent) => {
      if (!dragging) return;
      panX += e.clientX - lastX;
      panY += e.clientY - lastY;
      lastX = e.clientX;
      lastY = e.clientY;
      schedule();
    };
    const onUp = (e: PointerEvent) => {
      dragging = false;
      canvas.style.cursor = 'grab';
      if (canvas.hasPointerCapture(e.pointerId)) canvas.releasePointerCapture(e.pointerId);
    };
    const onWheel = (e: WheelEvent) => {
      e.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const mx = e.clientX - rect.left;
      const my = e.clientY - rect.top;
      const factor = e.deltaY > 0 ? 1 / 1.12 : 1.12;
      const next = Math.max(0.02, Math.min(2, scale * factor));
      const applied = next / scale;
      panX = mx - (mx - panX) * applied;
      panY = my - (my - panY) * applied;
      scale = next;
      schedule();
    };
    const onClick = (e: MouseEvent) => {
      if (!onPickTile) return;
      const rect = canvas.getBoundingClientRect();
      const x = (e.clientX - rect.left - panX) / scale;
      const y = (e.clientY - rect.top - panY) / scale;
      // 높이를 무시한 지면 역변환이다. 높은 타일은 한두 칸 어긋날 수 있다.
      const c = Math.round((x / HALF_W + y / HALF_H) / 2);
      const r = Math.round((y / HALF_H - x / HALF_W) / 2);
      const inside = c >= 0 && c < cols && r >= 0 && r < rows && playable[r * cols + c] === 1;
      onPickTile(inside ? { col: c, row: r } : null);
    };

    canvas.style.cursor = 'grab';
    canvas.style.touchAction = 'none';
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerup', onUp);
    canvas.addEventListener('pointercancel', onUp);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('click', onClick);
    const observer = new ResizeObserver(schedule);
    observer.observe(canvas);
    schedule();

    return () => {
      if (frame) cancelAnimationFrame(frame);
      observer.disconnect();
      canvas.removeEventListener('pointerdown', onDown);
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerup', onUp);
      canvas.removeEventListener('pointercancel', onUp);
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('click', onClick);
    };
  }, [data, sprites, tintMode, tintStrength, nationColorByOwner, onPickTile]);

  if (error) {
    return (
      <div className={className} role="alert" style={{ padding: 16, color: 'var(--rust-2)' }}>
        2D 지도를 그리지 못했다: {error}
      </div>
    );
  }

  return (
    <div className={className} style={{ position: 'relative', width: '100%', height: '100%' }}>
      <canvas
        ref={canvasRef}
        data-testid="iso2d-canvas"
        style={{ width: '100%', height: '100%', display: 'block' }}
      />
      {!sprites ? (
        <p
          style={{
            position: 'absolute', inset: 0, display: 'grid', placeItems: 'center',
            margin: 0, font: '12px var(--font-mono)', color: 'var(--muted)',
          }}
        >
          스프라이트 {needed.length}장 불러오는 중…
        </p>
      ) : null}
    </div>
  );
}

export default IsoMap2D;
