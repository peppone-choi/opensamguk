'use client';

// 아이소 지도 두 렌더러(3D·2D)가 함께 쓰는 데이터 적재.
//
// 원천이 둘이다.
//   지형·소유·城  → /api/game/api/map/terrain (HanMapCanvas 와 같은 응답)
//   높낮이        → /map/elevation/han-world-v3-levels.png (NOAA ETOPO1 파생, 정적)
//
// 둘을 192×167 타일 격자 하나로 합치는 계산은 전부 @opensamguk/ui 의 isoTileGrid 에 있다.
// 여기서는 가져오고 디코드하는 일만 한다.

import { useEffect, useState } from 'react';
import {
  RASTER_GROUP,
  buildIsoTileGrid,
  downsampleOwner,
  expandRunLength,
  sourceCellToTile,
  type HanTiles,
  type IsoTileGrid,
} from '@opensamguk/ui';

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
  commanderyNames: string[];
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

export function useIsoTileGrid(terrainUrl: string): State {
  const [state, setState] = useState<State>({ status: 'loading', data: null, error: null });

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    setState({ status: 'loading', data: null, error: null });

    (async () => {
      const [tilesResponse, image, manifestResponse] = await Promise.all([
        fetch(terrainUrl, { signal }),
        decodeLevelPng(LEVEL_PNG_URL, signal),
        fetch(ELEVATION_MANIFEST_URL, { signal }).catch(() => null),
      ]);
      if (!tilesResponse.ok) throw new Error(`지형을 못 받았다: ${tilesResponse.status}`);
      const tiles = (await tilesResponse.json()) as HanTiles;

      const grid = buildIsoTileGrid(tiles.terrain, image.data, image.width, image.height);
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

      const elevation = manifestResponse?.ok
        ? ((await manifestResponse.json()) as ElevationManifest)
        : null;

      setState({
        status: 'ready',
        error: null,
        data: {
          grid,
          owner,
          parentOwner,
          cities,
          commanderyNames: (tiles.parentRegions ?? []).map((region) => region.displayName),
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
