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
} from '../isoTileGrid';
import type { IsoMapData } from './useIsoTileGrid';
import { indexTint, normaliseNationColor, type Rgb, type TintMode } from './tint';
import type { IsoBattlefieldMarker, PlacedCity } from './placeGameCities';

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
const EMPTY_CITIES: readonly PlacedCity[] = [];
const EMPTY_BATTLEFIELDS: readonly IsoBattlefieldMarker[] = [];

export interface IsoMap2DProps {
  data: IsoMapData;
  tintStrength: number;
  tintMode: TintMode;
  nationColorByOwner?: Record<number, string>;
  /** 격자에 앉힌 게임 도시. 이게 있어야 눌러서 도시로 들어갈 수 있다. */
  cities?: readonly PlacedCity[];
  hideCityNames?: boolean;
  /** 내 장수가 있는 도시 — 금색 테. */
  currentCityId?: number | null;
  /** 선택된 도시 — 흰 테. */
  selectedCityId?: number | null;
  onPickTile?: (tile: { col: number; row: number } | null) => void;
  /**
   * 城 을 눌렀을 때. 붙어 있으면 城 이 지형보다 먼저 집힌다.
   * pointerType 은 직전 pointerdown 의 것이다 — 손가락 오조작을 막는 두 번 누르기
   * 규칙이 이 값에 걸려 있어서 넘긴다(MapViewer activateCity 참조).
   */
  onPickCity?: (city: PlacedCity, activation: { pointerType: string }) => void;
  /** 전장. 城 위에 마름모로 얹고 城 보다 먼저 집힌다. */
  battlefields?: readonly IsoBattlefieldMarker[];
  onPickBattlefield?: (target: IsoBattlefieldMarker) => void;
  className?: string;
  ariaLabel?: string;
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
  cities = EMPTY_CITIES,
  battlefields = EMPTY_BATTLEFIELDS,
  onPickBattlefield,
  hideCityNames = false,
  currentCityId = null,
  selectedCityId = null,
  onPickTile,
  onPickCity,
  className,
  ariaLabel,
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

    const { grid, owner, parentOwner } = data;
    const { cols, rows, code, mask, baseHeight, playable } = grid;

    // 화면 변환: 배율 1 에서 타일 폭 256px. 시작 배율은 전체가 담기도록 맞춘다.
    let scale = 1;
    let panX = 0;
    let panY = 0;
    let fitted = false;
    let frame = 0;

    // 城 집기 상자. 매 그리기마다 다시 채운다 — 화면 밖은 안 들어간다.
    const hits: { city: PlacedCity; x0: number; x1: number; y0: number; y1: number }[] = [];
    const fieldHits: { target: IsoBattlefieldMarker; x: number; y: number; radius: number }[] = [];

    // 소수 좌표(城)도 받는다. 높이는 그 좌표가 속한 정수 타일에서 읽는다 —
    // 城 은 타일 안 어디에 서 있든 그 타일 윗면에 얹혀야 한다.
    const tileScreen = (c: number, r: number): [number, number] => {
      const tc = Math.min(cols - 1, Math.max(0, Math.floor(c)));
      const tr = Math.min(rows - 1, Math.max(0, Math.floor(r)));
      return [
        (c - r) * HALF_W,
        (c + r) * HALF_H - baseHeight[tr * cols + tc] * STEP_SCREEN_PIXELS,
      ];
    };

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
      // 축소 상태에서는 郡治만 남긴다(SEAT_ONLY_TILE_PIXELS 주석 참조).
      const seatOnly = scale * TILE_SCREEN_WIDTH < SEAT_ONLY_TILE_PIXELS;
      // 뒤에서 앞으로. 같은 화가 순서를 집기 판정에서 거꾸로 훑어 위에 있는 城 을 먼저 집는다.
      hits.length = 0;
      const visible = cities
        .filter((city) => (!seatOnly || city.seat)
          && city.col >= c0 - 1 && city.col <= c1 + 1
          && city.row >= r0 - 1 && city.row <= r1 + 1)
        .sort((a, b) => (a.col + a.row) - (b.col + b.row));
      let drawnCities = 0;
      for (const city of visible) {
        const tier = BUILDING_TIERS.find((t) => city.level >= t.from && city.level <= t.to);
        if (!tier) continue;
        const sprite = sprites.get(`objects/${tier.file}`);
        if (!sprite) continue;
        const [x, y] = tileScreen(city.col, city.row);
        const left = x - OBJECT_ANCHOR_X;
        const top = y - OBJECT_ANCHOR_Y + HALF_H;
        context.drawImage(sprite, left, top);
        drawnCities += 1;
        // 집기 상자는 스프라이트 전체가 아니라 건물이 실제로 서 있는 아래쪽 절반이다.
        // 오브젝트 스프라이트 256×256 은 위쪽이 대부분 빈 하늘이라, 전부를 상자로 잡으면
        // 城 위 지형이 영영 안 집힌다.
        hits.push({
          city,
          x0: x - HALF_W * 0.5,
          x1: x + HALF_W * 0.5,
          y0: y - OBJECT_ANCHOR_Y + HALF_H + 128,
          y1: y + HALF_H,
        });

        // 소속·상태 표식. 스프라이트에 칠할 깃발 마스크가 없어 렌더러가 얹는다.
        const ring = city.id === currentCityId
          ? '#ffd36d' // --focus
          : city.id === selectedCityId ? '#ece6d8' : null; // --text
        if (ring) {
          context.save();
          context.strokeStyle = ring;
          context.lineWidth = 6;
          context.beginPath();
          context.moveTo(x, y - HALF_H);
          context.lineTo(x + HALF_W, y);
          context.lineTo(x, y + HALF_H);
          context.lineTo(x - HALF_W, y);
          context.closePath();
          context.stroke();
          context.restore();
        }
        if (city.nationColor) {
          const rgb = normaliseNationColor(city.nationColor);
          context.save();
          context.fillStyle = rgbCss(rgb);
          context.strokeStyle = 'rgba(12, 15, 14, 0.8)';
          context.lineWidth = 3;
          context.beginPath();
          context.arc(x, y - 150, city.isCapital ? 17 : 12, 0, Math.PI * 2);
          context.fill();
          context.stroke();
          if (city.isCapital) {
            // 수도는 안쪽에 밝은 점을 하나 더 둔다 — 색만으로는 못 가른다.
            context.fillStyle = '#ece6d8';
            context.beginPath();
            context.arc(x, y - 150, 6, 0, Math.PI * 2);
            context.fill();
          }
          context.restore();
        }
      }

      // 도시명 — 城 을 다 그린 뒤 얹는다. 겹치면 이름이 건물에 잘린다.
      if (!hideCityNames && !seatOnly) {
        context.save();
        context.font = '600 34px "Pretendard Variable", Pretendard, sans-serif';
        context.textAlign = 'center';
        context.textBaseline = 'top';
        context.lineJoin = 'round';
        // 받침 대신 외곽선. 배포본은 진홍 영토 위 검정 볼드라 판독이 어려웠다.
        context.strokeStyle = 'rgba(12, 15, 14, 0.92)';
        context.lineWidth = 8;
        context.fillStyle = '#ece6d8'; // --text
        for (const city of visible) {
          const [x, y] = tileScreen(city.col, city.row);
          context.strokeText(city.name, x, y + HALF_H + 6);
          context.fillText(city.name, x, y + HALF_H + 6);
        }
        context.restore();
      }

      // 전장 — 城·이름 위에 얹는다. 여기서만 볼 수 있는 진행 중 전투다.
      fieldHits.length = 0;
      for (const target of battlefields) {
        const [x, y] = tileScreen(target.col, target.row);
        const radius = 26;
        context.save();
        context.fillStyle = '#1b201d'; // --panel
        context.strokeStyle = target.current ? '#ffd36d' : '#d3b064'; // --focus / --bronze
        context.lineWidth = 7;
        context.beginPath();
        context.moveTo(x, y - radius);
        context.lineTo(x + radius, y);
        context.lineTo(x, y + radius);
        context.lineTo(x - radius, y);
        context.closePath();
        context.fill();
        context.stroke();
        context.restore();
        fieldHits.push({ target, x, y, radius: radius + 12 });
      }

      context.restore();
      canvas.dataset.drawnTiles = String(drawn);
      canvas.dataset.drawnCities = String(drawnCities);
      canvas.dataset.drawnBattlefields = String(battlefields.length);
      canvas.dataset.seatOnly = String(seatOnly);
    };

    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(draw);
    };

    let dragging = false;
    let lastX = 0;
    let lastY = 0;
    let lastPointerType = 'mouse';
    const onDown = (e: PointerEvent) => {
      lastPointerType = e.pointerType || 'mouse';
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
      if (!onPickTile && !onPickCity && !onPickBattlefield) return;
      const rect = canvas.getBoundingClientRect();
      const x = (e.clientX - rect.left - panX) / scale;
      const y = (e.clientY - rect.top - panY) / scale;
      // 전장이 제일 먼저다 — 城 위에 그렸으니 집기도 그 순서다.
      if (onPickBattlefield) {
        for (let n = fieldHits.length - 1; n >= 0; n -= 1) {
          const hit = fieldHits[n];
          if (Math.hypot(hit.x - x, hit.y - y) <= hit.radius) {
            onPickBattlefield(hit.target);
            return;
          }
        }
      }
      // 城 이 그다음이다. 화가 순서를 거꾸로 훑어 위에 그려진 쪽을 집는다.
      if (onPickCity) {
        for (let n = hits.length - 1; n >= 0; n -= 1) {
          const box = hits[n];
          if (x >= box.x0 && x <= box.x1 && y >= box.y0 && y <= box.y1) {
            onPickCity(box.city, { pointerType: lastPointerType });
            return;
          }
        }
      }
      if (!onPickTile) return;
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
  }, [data, sprites, tintMode, tintStrength, nationColorByOwner, cities, hideCityNames,
    currentCityId, selectedCityId, onPickTile, onPickCity, battlefields, onPickBattlefield]);

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
        role="img"
        aria-label={ariaLabel}
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
