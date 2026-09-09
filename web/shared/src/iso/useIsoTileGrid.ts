'use client';

// 아이소 지도 두 렌더러(3D·2D)가 함께 쓰는 데이터 적재.
//
// 원천이 둘이다.
//   지형·소유·城  → /api/game/api/map/terrain (HanMapCanvas 와 같은 응답)
//   높낮이        → /map/elevation/han-world-v3-levels.png (NOAA ETOPO1 파생, 정적)
//
// 둘을 192×167 타일 격자 하나로 합치는 계산은 전부 isoTileGrid.ts 에 있다.
// 여기서는 가져오고 디코드하는 일만 한다.

import { useEffect, useState } from 'react';
import {
  RASTER_GROUP,
  buildIsoTileGrid,
  downsampleOwner,
  expandRunLength,
  sourceCellToTile,
  type IsoTileGrid,
} from '../isoTileGrid';
import type { BattlefieldMapProjection, HanTiles } from '../HanMapCanvas';

export const LEVEL_PNG_URL = '/map/elevation/han-world-v3-levels.png';
export const ELEVATION_MANIFEST_URL = '/map/elevation/manifest.json';

export interface IsoCity {
  id: string;
  name: string;
  nameCh: string;
  level: number;
  kind: string;
  seat: boolean;
  /** 타일 좌표. 원본 셀 좌표를 rasterGroup 으로 나눈 것. */
  col: number;
  row: number;
}

export interface ElevationManifest {
  dataset?: { title?: string; endpoint?: string; license?: string; retrieved?: string };
  limitations?: string[];
}

export interface IsoMapData {
  grid: IsoTileGrid;
  /** 타일별 縣(province) 인덱스. -1 = 비플레이. */
  owner: Int32Array;
  /** 타일별 郡(commandery) 인덱스. -1 = 비플레이. */
  parentOwner: Int32Array;
  cities: IsoCity[];
  /**
   * 縣 인덱스 → 그 縣 治所의 **원본 셀** 좌표. 없으면 col/row 가 -1.
   *
   * 게임 도시를 타일에 앉히는 대조표다. 게임 응답의 `city.provinceId` 는
   * provinceRecords 배열 인덱스이고(MapPreviewController.kt:123 · MapJson.kt:22),
   * provinceRecords[i].cityIndex 는 지형 응답 cities[] 인덱스다. 두 칸을 이으면
   * 게임 도시 번호가 CHGIS 실측 좌표에 정확히 닿는다 — 좌표 변환도 이름 대조도 없다.
   * 실측(han-world-v3): 게임 도시 781 중 773 이 이 길로 좌표를 얻고 충돌은 0 이다.
   * 새로 지어낸 이음매가 아니다 — 기존 캔버스가 마커를 앉힐 때 쓰는 그 길이다
   * (HanMapCanvas.tsx:1546 preferredByProvince).
   */
  provinceSeatCell: { col: Int32Array; row: Int32Array };
  commanderyNames: string[];
  /** 원본 지형 격자 크기(타일이 아니라 셀). 좌표 폴백이 쓴다. */
  sourceCols: number;
  sourceRows: number;
  /** 위경도 → 원본 셀 투영. 전장(위경도로 온다) 을 격자에 앉히는 데 쓴다. */
  projection: BattlefieldMapProjection | undefined;
  year: number;
  elevation: ElevationManifest | null;
}

type State =
  | { status: 'loading'; data: null; error: null }
  | { status: 'ready'; data: IsoMapData; error: null }
  | { status: 'error'; data: null; error: string };

/** DEM 레벨 PNG 를 픽셀로 푼다. 회색조 8비트라 R 채널만 의미가 있다. */
async function decodeLevelPng(url: string, signal: AbortSignal): Promise<ImageData> {
  const response = await fetch(url, { signal });
  if (!response.ok) throw new Error(`고도 PNG 를 못 받았다: ${response.status}`);
  const bitmap = await createImageBitmap(await response.blob());
  const canvas = document.createElement('canvas');
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const context = canvas.getContext('2d', { willReadFrequently: true });
  if (!context) throw new Error('2D 컨텍스트를 못 만들었다');
  context.drawImage(bitmap, 0, 0);
  bitmap.close();
  return context.getImageData(0, 0, canvas.width, canvas.height);
}

/**
 * provinceRecords[i].cityIndex → cities[cityIndex] 의 원본 셀 좌표를 편다.
 * cityIndex 가 없거나(526/1,524) 범위를 벗어나면 -1 로 남긴다 — 지어내지 않는다.
 */
export function buildProvinceSeatCells(tiles: HanTiles): { col: Int32Array; row: Int32Array } {
  const records = tiles.provinceRecords ?? [];
  const col = new Int32Array(records.length).fill(-1);
  const row = new Int32Array(records.length).fill(-1);
  for (let i = 0; i < records.length; i += 1) {
    const index = records[i].cityIndex;
    if (index == null || !Number.isInteger(index)) continue;
    const city = tiles.cities[index];
    if (!city) continue;
    col[i] = city.col;
    row[i] = city.row;
  }
  return { col, row };
}

export function useIsoTileGrid(terrainUrl: string): State {
  const [state, setState] = useState<State>({ status: 'loading', data: null, error: null });

  useEffect(() => {
    setState({ status: 'loading', data: null, error: null });
    // 주소가 아직 없다(맵 코드를 못 받았다). 빈 문자열로 fetch 하면 지금 페이지를
    // 받아 와 JSON 파싱에서 엉뚱하게 터진다 — 그냥 기다린다.
    if (!terrainUrl) return undefined;
    const controller = new AbortController();
    const { signal } = controller;

    (async () => {
      const [tilesResponse, image, manifestResponse] = await Promise.all([
        fetch(terrainUrl, { signal }),
        decodeLevelPng(LEVEL_PNG_URL, signal),
        fetch(ELEVATION_MANIFEST_URL, { signal }).catch(() => null),
      ]);
      if (!tilesResponse.ok) throw new Error(`지형을 못 받았다: ${tilesResponse.status}`);
      const tiles = (await tilesResponse.json()) as HanTiles;

      // 治所 좌표를 격자보다 먼저 편다 — buildIsoTileGrid 가 「城 이 선 칸은 뭍」을
      // 적용하는 데 이 값을 쓴다(landUnderSeats).
      const provinceSeatCell = buildProvinceSeatCells(tiles);
      const grid = buildIsoTileGrid(
        tiles.terrain, image.data, image.width, image.height, RASTER_GROUP, provinceSeatCell,
      );
      const srcCols = tiles._meta.cols;
      const srcRows = tiles._meta.rows;
      const cellCount = srcCols * srcRows;

      const owner = downsampleOwner(
        expandRunLength(tiles.owner, cellCount), srcCols, grid.cols, grid.rows, RASTER_GROUP,
      );
      const parentOwner = tiles.parentOwner
        ? downsampleOwner(
          expandRunLength(tiles.parentOwner, cellCount), srcCols, grid.cols, grid.rows, RASTER_GROUP,
        )
        : new Int32Array(grid.cols * grid.rows).fill(-1);

      const cities: IsoCity[] = tiles.cities.map((city) => {
        const [col, row] = sourceCellToTile(city.col, city.row, RASTER_GROUP);
        return { ...city, col, row };
      });

      // 매니페스트는 곁들이다(출처·한계 표시용). 못 받아도, 몸통이 JSON 이 아니어도
      // 지도는 그려야 한다 — 여기서 던지면 아래 catch 가 지도 전체를 error 로 내린다.
      const elevation = manifestResponse?.ok
        ? await manifestResponse.json().then((json) => json as ElevationManifest).catch(() => null)
        : null;

      setState({
        status: 'ready',
        error: null,
        data: {
          grid,
          owner,
          parentOwner,
          cities,
          provinceSeatCell,
          commanderyNames: (tiles.parentRegions ?? []).map((region) => region.displayName),
          sourceCols: srcCols,
          sourceRows: srcRows,
          projection: tiles._meta.projection,
          year: tiles._meta.year,
          elevation,
        },
      });
    })().catch((error: unknown) => {
      if (signal.aborted) return;
      setState({
        status: 'error',
        data: null,
        error: error instanceof Error ? error.message : '알 수 없는 오류',
      });
    });

    return () => controller.abort();
  }, [terrainUrl]);

  return state;
}
