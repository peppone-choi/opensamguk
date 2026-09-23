// 城 그림을 **성내에 꽉 맞춘다**(2026-09-23 사용자 결정, 모든 지도 화면) — 아이소 2D·3D 공용 셈.
//
// 성내는 원본 격자(768×669) 칸으로 정해진다(cityFootprint.ts). 아이소 타일 한 칸은 그 칸
// RASTER_GROUP×RASTER_GROUP 이다(지금 2×2). 그래서 변 span 칸인 성내는 아이소에서 **span/G 타일**
// 폭이다 — 경(7칸)은 3.5 타일, 1칸 縣 은 반 타일. 타일 수로 세면 같은 城 이 HanMapCanvas 보다
// G 배 넓게 서서 두 지도가 다른 땅을 가리킨다.
//
// 좌표 규약: 타일 t 의 중심이 정수 t 이고 타일은 [t-0.5, t+0.5) 이다(IsoMap2D tileScreen ·
// pickTileAtScreen). 원본 칸 s 는 타일 좌표로 [s/G-0.5, (s+1)/G-0.5) 를 덮는다.

import { RASTER_GROUP } from '../isoTileGrid';

export interface TileFootprint {
  /** 성내 중심의 타일 좌표(소수). */
  readonly centerCol: number;
  readonly centerRow: number;
  /** 성내 한 변의 길이(타일 단위). 화면 마름모 폭은 이 값 × 타일 폭이다. */
  readonly width: number;
}

/**
 * 원본 칸 (col,row) 를 마커로 하고 변이 span 칸인 성내를 타일 좌표로 옮긴다.
 * 변은 늘 홀수(resolveCityFootprints)라 성내 중심은 마커 칸의 중심이다.
 */
export function cellFootprintInTiles(
  cellCol: number,
  cellRow: number,
  span: number,
  group: number = RASTER_GROUP,
): TileFootprint {
  const back = (span - 1) / 2;
  return {
    centerCol: (cellCol - back + span / 2) / group - 0.5,
    centerRow: (cellRow - back + span / 2) / group - 0.5,
    width: span / group,
  };
}

/**
 * iso2d 오브젝트 스프라이트(256×256)의 **실루엣 가로 범위** [왼, 오른쪽 끝+1) — 알파(>40) 경계상자.
 *
 * 스프라이트 캔버스는 타일 폭(256)이지만 등급 위계용 여백이 있다(장현 136px · 도성 254px).
 * 캔버스 폭을 성내에 맞추면 작은 城 은 성내 안에 섬처럼 뜬다 — 실루엣이 성내 폭을 채우게 늘린다.
 * 실루엣 밑면은 (128,176) 을 중심으로 한 마름모다(빌더 기하 가이드, 아래 꼭짓점 y = 176 + 폭/4 가
 * 11장 모두 10px 안에서 맞는다). 스프라이트를 다시 뽑으면 buildingFit.test.ts 가 PNG 를 직접
 * 읽어 빨개진다.
 */
export const SPRITE_SILHOUETTE_PX: Readonly<Record<string, readonly [number, number]>> = {
  water: [56, 198],
  garrison: [44, 212],
  pass: [54, 198],
  tribal: [68, 188],
  commandery: [30, 226],
  'commandery-mid': [24, 230],
  'commandery-major': [18, 238],
  'commandery-grand': [10, 246],
  capital: [0, 254],
  county: [48, 216],
  'county-small': [60, 196],
};

/** 스프라이트 원본 폭(= 타일 폭). 실루엣 표가 없으면 이 폭 전체를 실루엣으로 본다. */
const SPRITE_CANVAS_PX = 256;

export interface SpriteFit {
  /** 스프라이트에 곱할 세계 배율(1 = 원본 256px 가 타일 폭 256 에 맞는다). */
  readonly scale: number;
  /** 성내 중심에 와야 할 스프라이트 안 점 — 실루엣 가로 중심과 밑면 중심 y(176). */
  readonly originX: number;
  readonly originY: number;
}

/**
 * 성내 폭(타일 단위) → 스프라이트 배율. 실루엣 폭이 성내 마름모 폭(width × 256)과 같아진다.
 * 그리는 쪽은 `(x - originX·scale, y - originY·scale)` 에 `256·scale` 크기로 그린다 —
 * 실루엣 밑면 중심이 성내 중심에, 실루엣 앞 꼭짓점이 성내 앞 꼭짓점에 온다.
 */
export function spriteFootprintFit(file: string, footprintWidth: number, groundCenterY: number): SpriteFit {
  const [x0, x1] = SPRITE_SILHOUETTE_PX[file] ?? [0, SPRITE_CANVAS_PX];
  return {
    scale: (footprintWidth * SPRITE_CANVAS_PX) / (x1 - x0),
    originX: (x0 + x1) / 2,
    originY: groundCenterY,
  };
}

export interface ModelBounds {
  readonly min: { readonly x: number; readonly z: number };
  readonly max: { readonly x: number; readonly z: number };
}

export interface ModelFit {
  /** 모델에 곱할 균일 배율. */
  readonly scale: number;
  /** 성내 중심에서 모델 원점을 옮길 양(세계 단위). 모델 밑면 중심이 성내 중심에 온다. */
  readonly offsetX: number;
  readonly offsetZ: number;
}

/**
 * iso3d 건물 모델(XZ 밑면, 타일 1×1 안)을 성내 정사각형에 맞춘다. 밑면의 긴 변이 성내 한 변을
 * 채운다 — 짧은 변(관문처럼 가늘고 긴 모델)은 비율대로 남는다. 높이도 같은 배율로 늘린다.
 */
export function modelFootprintFit(bounds: ModelBounds, footprintWidth: number): ModelFit {
  const extent = Math.max(bounds.max.x - bounds.min.x, bounds.max.z - bounds.min.z);
  const scale = extent > 0 ? footprintWidth / extent : footprintWidth;
  return {
    scale,
    offsetX: -((bounds.min.x + bounds.max.x) / 2) * scale,
    offsetZ: -((bounds.min.z + bounds.max.z) / 2) * scale,
  };
}
