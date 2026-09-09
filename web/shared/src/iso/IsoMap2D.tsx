'use client';

// 천하 지도 2D 아이소 렌더러 — iso2d 스프라이트 + DEM 높낮이.
//
// 애셋 계약(web/game/public/sprites/iso2d/manifest.json):
//   · 평지 발자국 256×128. 스프라이트는 256×160, 앵커 (128,96).
//   · 코너 순서 N,E,S,W. 이미지 좌표로 N(128,32) E(256,96) S(128,160) W(0,96).
//   · 마스크 0..14 를 미리 그려 뒀다. 한 단차 = 화면 32px.
//   · 흙벽은 skirt-left(남서 변)·skirt-right(남동 변) 두 장, 각 128×96 = 한 단.
//   · 오브젝트는 256×256, 앵커 (128,240) = 알파 경계상자의 아래·가운데(넷 다 실측 일치).
//   · 칠할 수 있는 깃발 마스크는 제공되지 않는다 — 깃발은 렌더러가 화면 좌표로 얹는다.
//
// 세력색은 캔버스 합성 모드 **'color'** 로 넣는다. 휘도는 지형 것을 그대로 두고 색상·채도만
// 세력 것으로 바꾸므로 산·강 음영이 살아 있고 색이 짙게 깔리지 않는다(HOI4 정치 지도와
// 같은 방식). 배포본은 불투명 덮어쓰기였고 그다음은 곱하기였다 — 전자는 지형이 한 픽셀도
// 안 보였고(reports/opensamguk/tasks/2026-09-09-design-re-review.md) 후자는 어두운 국가색이
// 땅을 통째로 눌렀다. 색만으로는 세력 범위가 안 읽히므로 縣·郡·국가 경계선을 함께 긋는다.

import { useEffect, useMemo, useRef, useState } from 'react';
import {
  MAX_LEVEL,
  SEAT_ONLY_TILE_PIXELS,
  STEP_SCREEN_PIXELS,
  TERRAIN_ASSET_NAME,
  TILE_SCREEN_HEIGHT,
  TILE_SCREEN_WIDTH,
  isWater,
  pickTileAtScreen,
} from '../isoTileGrid';
import type { IsoMapData } from './useIsoTileGrid';
import {
  drawBattlefieldMark,
  drawCityFlag,
  drawCityName,
  drawCityRing,
  markerScale,
} from './marker';
import { indexTint, normaliseNationColor, rgbCss, type TintMode } from './tint';
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
/**
 * 오브젝트 접지점. 매니페스트가 말하는 (128,240) 은 넷 다 알파 경계상자의 아래·가운데와
 * 정확히 같다(실측: capital 4..252/·240, commandery 12..244, county 20..236, hamlet 38..218 —
 * 가로 중심이 전부 128, 아래끝이 전부 240).
 */
const OBJECT_ANCHOR_X = 128;
const OBJECT_ANCHOR_Y = 240;
/**
 * 城 은 타일보다 조금 작게, 다이아몬드 안에 앉힌다.
 *
 * 배포본은 원본 크기(256px = 타일 폭 그대로)를 타일 아래 꼭짓점에 붙여 세웠다. 그러면
 * 성벽이 칸 밖으로 삐져나가고 격자와 어긋나 보인다 — 「셀과 아이콘이 안 맞는다」(2026-09-09).
 * 0.85 배로 줄이고 접지점을 중심과 아래 꼭짓점 사이(HALF_H·0.75)에 두면 바닥이 칸 안에 든다.
 */
const OBJECT_SCALE = 0.85;
const OBJECT_FOOT_Y = HALF_H * 0.75;
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
  /**
   * 城 위에 마우스를 **얹었을 때**. 좌표는 캔버스 왼위 기준 화면 좌표다.
   * 城 밖으로 나가면 null 로 한 번 더 부른다. 툴팁은 이걸로 뜬다 — 누를 필요가 없다.
   */
  onHoverCity?: (city: PlacedCity | null, at: { x: number; y: number }) => void;
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
  onHoverCity,
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

  // 확대·이동은 씬을 다시 그리게 할 뿐 다시 짓게 하지는 않는다. 그런데 아래 effect 는
  // 색 인자(tintMode 등)에도 걸려 있어서, 세력색 탭을 누르거나 국가색이 늦게 도착하면
  // 다시 돈다. 지역 변수로 두면 그때마다 사용자의 확대·이동이 전체 맞춤으로 되돌아가고
  // 스스로 복구되지 않는다 — ref 에 얹어 effect 를 넘겨 산다.
  const viewRef = useRef({ scale: 1, panX: 0, panY: 0, fitted: false });
  // 지형이 바뀌면 배율·위치는 뜻이 없다. 다시 맞춘다.
  useEffect(() => {
    viewRef.current = { scale: 1, panX: 0, panY: 0, fitted: false };
  }, [data]);

  // 캔버스 위 +/−/전체 단추가 쥐는 손잡이. 휠이 없는 손가락 조작에서 유일한 확대 수단이다.
  const zoomRef = useRef<{ by: (factor: number) => void; fit: () => void } | null>(null);

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
    const view = viewRef.current;
    let frame = 0;

    // 城 집기 상자. 표식과 같은 **화면 좌표**다. 매 그리기마다 다시 채운다.
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
      if (!view.fitted) {
        // 타일 중심 x 는 −(rows−1)·128 … (cols−1)·128, y 는 0 … (cols+rows−2)·64 를 훑는다.
        // 여기에 스프라이트가 중심 밖으로 뻗는 만큼(가로 ±128, 위 96 + 최대 단차)을 더한다.
        const lift = MAX_LEVEL * STEP_SCREEN_PIXELS;
        const minX = -(rows - 1) * HALF_W - HALF_W;
        const maxX = (cols - 1) * HALF_W + HALF_W;
        const minY = -ANCHOR_Y - lift;
        const maxY = (cols + rows - 2) * HALF_H + (160 - ANCHOR_Y);
        view.scale = Math.min(w / (maxX - minX), h / (maxY - minY)) * 0.95;
        view.panX = w / 2 - ((minX + maxX) / 2) * view.scale;
        view.panY = h / 2 - ((minY + maxY) / 2) * view.scale;
        view.fitted = true;
      }

      context.setTransform(dpr, 0, 0, dpr, 0, 0);
      context.fillStyle = '#0c0f0e'; // 팔레트 --bg
      context.fillRect(0, 0, w, h);
      context.save();
      context.translate(view.panX, view.panY);
      context.scale(view.scale, view.scale);
      context.imageSmoothingEnabled = view.scale < 1;

      // 화면에 걸리는 타일만 그린다. 화면 → 격자 역변환 네 귀퉁이로 범위를 잡는다.
      const toCell = (sx: number, sy: number): [number, number] => {
        const x = (sx - view.panX) / view.scale;
        const y = (sy - view.panY) / view.scale;
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

      // 세력색 — 색상만 얹는다('color'). 휘도는 지형 것이 남아 산·강 음영이 살아 있고
      // 색이 짙게 깔리지 않는다. 곱하기였을 때 「너무 짙다」는 지적을 받았다(2026-09-09).
      if (tintMode !== 'none' && tintStrength > 0) {
        context.save();
        context.globalCompositeOperation = 'color';
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

      // 縣·郡·국가 경계 — 색만으로는 어디까지가 한 세력인지 안 읽힌다(2026-09-09 지적).
      //
      // 한 변은 한 번만 긋는다. 이웃 (c+1,r) 은 화면에서 오른아래에 있으므로 공유 변은
      // 오른 꼭짓점—아래 꼭짓점, 이웃 (c,r+1) 은 왼아래라 아래 꼭짓점—왼 꼭짓점이다.
      // 물·지도 밖과의 경계는 긋지 않는다 — 해안선은 지형이 이미 말한다.
      // 세력 판정은 색 문자열로 한다. 같은 나라의 두 縣 은 같은 색을 받으므로 그 사이에는
      // 국경이 서지 않고, 대신 郡 경계가 남는다.
      {
        const tilePixels = view.scale * TILE_SCREEN_WIDTH;
        // 축소하면 아래 등급부터 끈다. 전체 보기(타일 13px)에서 郡 경계까지 다 그으면
        // 금이 땅보다 넓어져 세력 덩어리가 안 보인다.
        const showCounty = tilePixels >= 26;
        const showCommandery = tilePixels >= 16;
        const nationKey = (i: number): string | null => {
          if (playable[i] === 0 || isWater(code[i])) return null;
          const o = owner[i];
          if (o < 0) return '';
          return nationColorByOwner?.[o] ?? '';
        };
        const nationPath = new Path2D();
        const commanderyPath = new Path2D();
        const countyPath = new Path2D();
        for (let r = r0; r <= r1; r += 1) {
          for (let c = c0; c <= c1; c += 1) {
            const i = r * cols + c;
            const key = nationKey(i);
            if (key === null) continue;
            const [x, y] = tileScreen(c, r);
            const edges: [number, number, number, number, number][] = [];
            if (c + 1 < cols) edges.push([i + 1, x + HALF_W, y, x, y + HALF_H]);
            if (r + 1 < rows) edges.push([i + cols, x, y + HALF_H, x - HALF_W, y]);
            for (const [j, ax, ay, bx, by] of edges) {
              const other = nationKey(j);
              if (other === null) continue;
              const path = other !== key
                ? nationPath
                : (showCommandery && parentOwner[j] !== parentOwner[i])
                  ? commanderyPath
                  : (showCounty && owner[j] !== owner[i]) ? countyPath : null;
              if (!path) continue;
              path.moveTo(ax, ay);
              path.lineTo(bx, by);
            }
          }
        }
        context.save();
        context.lineCap = 'round';
        // 선 굵기는 화면 기준이다 — 확대해도 국경이 두꺼워지지 않는다. 다만 축소할수록
        // 조금 가늘게 간다. 전체 보기에서 3px 국경은 縣 한 칸(13px)의 1/4 라 땅을 먹는다.
        const nationWidth = Math.max(1.2, Math.min(3, tilePixels / 12));
        const strokes: [Path2D, string, number][] = [
          [countyPath, 'rgba(12, 15, 14, 0.26)', 1],
          [commanderyPath, 'rgba(12, 15, 14, 0.5)', Math.min(1.8, nationWidth * 0.6)],
          [nationPath, 'rgba(12, 15, 14, 0.88)', nationWidth],
        ];
        for (const [path, style, width] of strokes) {
          context.strokeStyle = style;
          context.lineWidth = width / view.scale;
          context.stroke(path);
        }
        context.restore();
      }

      // 城 건물 — 세계 좌표. 지형과 같은 배율로 서야 등급별 크기가 뜻을 갖는다.
      // 축소 상태에서는 郡治만 남긴다(SEAT_ONLY_TILE_PIXELS 주석 참조).
      const seatOnly = view.scale * TILE_SCREEN_WIDTH < SEAT_ONLY_TILE_PIXELS;
      // 뒤에서 앞으로. 같은 화가 순서를 집기 판정에서 거꾸로 훑어 위에 있는 城 을 먼저 집는다.
      const visible = cities
        .filter((city) => (!seatOnly || city.seat)
          && city.col >= c0 - 1 && city.col <= c1 + 1
          && city.row >= r0 - 1 && city.row <= r1 + 1)
        .sort((a, b) => (a.col + a.row) - (b.col + b.row));
      let drawnCities = 0;
      const placedOnScreen: { city: PlacedCity; sx: number; sy: number }[] = [];
      for (const city of visible) {
        const [x, y] = tileScreen(city.col, city.row);
        placedOnScreen.push({
          city,
          sx: view.panX + x * view.scale,
          sy: view.panY + y * view.scale,
        });
        const tier = BUILDING_TIERS.find((t) => city.level >= t.from && city.level <= t.to);
        if (!tier) continue;
        const sprite = sprites.get(`objects/${tier.file}`);
        if (!sprite) continue;
        const size = sprite.naturalWidth * OBJECT_SCALE;
        context.drawImage(
          sprite,
          x - OBJECT_ANCHOR_X * OBJECT_SCALE,
          y + OBJECT_FOOT_Y - OBJECT_ANCHOR_Y * OBJECT_SCALE,
          size,
          sprite.naturalHeight * OBJECT_SCALE,
        );
        drawnCities += 1;
      }

      context.restore();

      // ── 여기부터는 화면 좌표다 ────────────────────────────────────────
      // 깃발·이름·전장은 배율을 따라가지 않는다. 세계 좌표로 그리면 전체 보기에서 1px 로
      // 사라지고 당기면 화면을 덮는다 — 배포본이 그래서 전장 두 곳만 도드라져 보였다.
      const k = markerScale(view.scale);
      const flagLift = Math.max(11, 96 * view.scale);
      hits.length = 0;
      for (const { city, sx, sy } of placedOnScreen) {
        if (sx < -60 || sx > w + 60 || sy < -80 || sy > h + 60) continue;
        const top = drawCityFlag(context, sx, sy - flagLift, {
          color: city.nationColor ? rgbCss(normaliseNationColor(city.nationColor)) : null,
          capital: city.isCapital,
          k,
        });
        // 집기 상자는 깃발 꼭대기부터 칸 아래 꼭짓점까지 — 깃발을 눌러도, 성벽을 눌러도 잡힌다.
        const half = Math.max(15 * k, HALF_W * 0.45 * view.scale);
        hits.push({
          city,
          x0: sx - half,
          x1: sx + half,
          y0: top - 2,
          y1: sy + Math.max(9, HALF_H * 0.7 * view.scale),
        });
      }

      // 선택·주둔 테는 깃발 위에 얹는다 — 가려지면 어디가 내 城 인지 못 찾는다.
      for (const { city, sx, sy } of placedOnScreen) {
        const ring = city.id === currentCityId
          ? '#ffd36d' // --focus
          : city.id === selectedCityId ? '#ece6d8' : null; // --text
        if (ring) drawCityRing(context, sx, sy, { color: ring, k });
      }

      if (!hideCityNames && !seatOnly) {
        const below = Math.max(9, HALF_H * 0.7 * view.scale) + 2;
        for (const { city, sx, sy } of placedOnScreen) {
          if (sx < -60 || sx > w + 60 || sy < -60 || sy > h + 60) continue;
          drawCityName(context, city.name, sx, sy + below, k);
        }
      }

      // 전장 — 城 위에 얹는다. 여기서만 볼 수 있는 진행 중 전투다.
      fieldHits.length = 0;
      for (const target of battlefields) {
        const [x, y] = tileScreen(target.col, target.row);
        const sx = view.panX + x * view.scale;
        const sy = view.panY + y * view.scale;
        const radius = drawBattlefieldMark(context, sx, sy, { current: target.current === true, k });
        fieldHits.push({ target, x: sx, y: sy, radius });
      }

      canvas.dataset.drawnTiles = String(drawn);
      canvas.dataset.drawnCities = String(drawnCities);
      canvas.dataset.drawnBattlefields = String(battlefields.length);
      canvas.dataset.seatOnly = String(seatOnly);
    };

    const schedule = () => {
      if (!frame) frame = requestAnimationFrame(draw);
    };

    /** 화면 좌표 (x, y) 위에 있는 城. 위에 그려진 쪽을 먼저 집는다. */
    const cityAt = (x: number, y: number): PlacedCity | null => {
      for (let n = hits.length - 1; n >= 0; n -= 1) {
        const box = hits[n];
        if (x >= box.x0 && x <= box.x1 && y >= box.y0 && y <= box.y1) return box.city;
      }
      return null;
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
    // 마우스를 얹기만 해도 城 정보가 나와야 한다 — 눌러야 나오는 건 지도가 아니라 목록이다.
    let hovered: number | null = null;
    const onMove = (e: PointerEvent) => {
      if (dragging) {
        view.panX += e.clientX - lastX;
        view.panY += e.clientY - lastY;
        lastX = e.clientX;
        lastY = e.clientY;
        schedule();
        return;
      }
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
      const city = cityAt(x, y);
      canvas.style.cursor = city ? 'pointer' : 'grab';
      if (!onHoverCity) return;
      // 같은 城 위에서 움직이는 동안에도 좌표는 계속 준다 — 툴팁이 커서를 따라간다.
      if (city || hovered !== null) onHoverCity(city, { x, y });
      hovered = city ? city.id : null;
    };
    const onLeave = () => {
      if (hovered === null) return;
      hovered = null;
      onHoverCity?.(null, { x: 0, y: 0 });
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
      const next = Math.max(0.02, Math.min(2, view.scale * factor));
      const applied = next / view.scale;
      view.panX = mx - (mx - view.panX) * applied;
      view.panY = my - (my - view.panY) * applied;
      view.scale = next;
      schedule();
    };
    const onClick = (e: MouseEvent) => {
      if (!onPickTile && !onPickCity && !onPickBattlefield) return;
      const rect = canvas.getBoundingClientRect();
      const x = e.clientX - rect.left;
      const y = e.clientY - rect.top;
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
      if (onPickCity) {
        const city = cityAt(x, y);
        if (city) {
          onPickCity(city, { pointerType: lastPointerType });
          return;
        }
      }
      if (!onPickTile) return;
      // 지형은 세계 좌표로 되돌려서 집는다. 높이를 감안한 역변환이다(pickTileAtScreen) —
      // 높은 타일은 화면에서 위로 올라가 있어 지면 역변환만으로는 한두 칸 어긋난다.
      onPickTile(pickTileAtScreen(
        (x - view.panX) / view.scale,
        (y - view.panY) / view.scale,
        grid,
      ));
    };

    // 화면 한가운데를 기준으로 배율만 바꾼다 — 휠과 같은 식이되 커서 대신 중심을 쓴다.
    zoomRef.current = {
      by: (factor: number) => {
        const mx = canvas.clientWidth / 2;
        const my = canvas.clientHeight / 2;
        const next = Math.max(0.02, Math.min(2, view.scale * factor));
        const applied = next / view.scale;
        view.panX = mx - (mx - view.panX) * applied;
        view.panY = my - (my - view.panY) * applied;
        view.scale = next;
        schedule();
      },
      fit: () => {
        view.fitted = false;
        schedule();
      },
    };

    canvas.style.cursor = 'grab';
    canvas.style.touchAction = 'none';
    canvas.addEventListener('pointerdown', onDown);
    canvas.addEventListener('pointermove', onMove);
    canvas.addEventListener('pointerup', onUp);
    canvas.addEventListener('pointercancel', onUp);
    canvas.addEventListener('pointerleave', onLeave);
    canvas.addEventListener('wheel', onWheel, { passive: false });
    canvas.addEventListener('click', onClick);
    const observer = new ResizeObserver(schedule);
    observer.observe(canvas);
    schedule();

    return () => {
      if (frame) cancelAnimationFrame(frame);
      zoomRef.current = null;
      observer.disconnect();
      canvas.removeEventListener('pointerdown', onDown);
      canvas.removeEventListener('pointermove', onMove);
      canvas.removeEventListener('pointerup', onUp);
      canvas.removeEventListener('pointercancel', onUp);
      canvas.removeEventListener('pointerleave', onLeave);
      canvas.removeEventListener('wheel', onWheel);
      canvas.removeEventListener('click', onClick);
    };
  }, [data, sprites, tintMode, tintStrength, nationColorByOwner, cities, hideCityNames,
    currentCityId, selectedCityId, onPickTile, onPickCity, onHoverCity,
    battlefields, onPickBattlefield]);

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
      {sprites ? (
        <div className="iso-zoom" role="group" aria-label="지도 배율">
          <button type="button" aria-label="지도 확대" onClick={() => zoomRef.current?.by(1.4)}>+</button>
          <button type="button" aria-label="지도 축소" onClick={() => zoomRef.current?.by(1 / 1.4)}>−</button>
          <button type="button" aria-label="지도 전체 보기" onClick={() => zoomRef.current?.fit()}>전체</button>
        </div>
      ) : null}
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
