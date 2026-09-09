// 게임 도시를 아이소 타일 격자에 앉힌다.
//
// 아이소 지도가 기존 지도를 대체하려면 城 을 눌러 도시로 들어갈 수 있어야 한다. 그러려면
// 화면에 서 있는 건물이 **게임 도시 번호**를 들고 있어야 한다. 지형 응답의 cities[] 는
// CHGIS 계열 문자열 id("85377")라 게임 번호와 다른 공간이므로 그쪽은 쓸 수 없다.
//
// 이음매는 두 칸이다. 둘 다 이미 서버·기존 캔버스가 쓰는 값이고, 여기서 새로 만든 건 없다.
//
//   1) 게임 도시.provinceId  = provinceRecords 배열 인덱스
//      (MapJson.kt:22 "Canonical han-tiles provinceRecords array index",
//       MapPreviewController.kt:123 이 그대로 실어 보낸다)
//   2) provinceRecords[i].cityIndex = 지형 응답 cities[] 인덱스
//      (HanMapCanvas.tsx:1546 이 마커 자리를 잡을 때 쓰는 그 필드)
//
// 실측(han-world-v3, 2026-09-09): 게임 도시 781 중 773 이 이 길로 원본 셀 좌표를 얻고,
// 서로 다른 도시가 같은 셀에 겹치는 경우는 0 이었다. 이름도 773/773 이 맞았다
// (예: 게임 "장안(京兆尹)" → provinceRecords[503] "장안현" → cities[1033]).
//
// 남는 8 곳(구자속국·낙랑군·현도군·요동속국·요동군·구진군·교지군·일남군)은 provinceId 가
// 없다 — 후한 縣 판정이 안 된 변경(邊境) 郡이다. 이쪽만 좌표 폴백을 쓴다. 폴백은 기존
// 캔버스의 mapCityToTile 과 같은 선형식이고, 8 곳 모두 육지 타일에 떨어지는 것을 확인했다.

import type { IsoMapData } from './useIsoTileGrid';
import { RASTER_GROUP } from '../isoTileGrid';
import { isOwnedNationVisual } from '../nationVisual';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';

/** 배치 입력. MapPreviewCity 에서 필요한 만큼만 뽑은 모양이다. */
export interface GameCityInput {
  id: number;
  name: string;
  level: number;
  nationId: number;
  x: number;
  y: number;
  provinceId?: number;
  isCommanderySeat?: boolean;
  isCapital?: boolean;
  supply?: boolean;
  state?: number;
}

/**
 * 격자에 앉은 게임 도시. 렌더러 두 판이 함께 쓴다.
 *
 * col/row 는 **소수**다. 타일로 반올림하면 안 된다 — 실측하면 게임 도시 781 곳 중
 * 37 곳이 다른 도시와 같은 타일(4×4 원본 셀)에 묶여 뒤에 오는 쪽이 통째로 가려진다.
 * 그러면 그 37 곳은 눌러서 들어갈 수 없다. 원본 셀 해상도를 그대로 들고 다니면
 * 확대했을 때 서로 떨어져 각각 집힌다. 높이를 볼 때만 tile 로 내린다.
 */
export interface PlacedCity {
  id: number;
  name: string;
  level: number;
  nationId: number;
  /** 소수 타일 좌표(원본 셀 / rasterGroup). */
  col: number;
  row: number;
  /** 높이·소유를 읽을 정수 타일. */
  tileCol: number;
  tileRow: number;
  /** 郡治 여부. 축소 상태에서 이것만 남긴다. */
  seat: boolean;
  isCapital: boolean;
  /** 정규화 전 자유 hex. 중립(무소속)이면 undefined. */
  nationColor?: string;
  nationName?: string;
  /** 좌표를 대조표에서 얻었는가. false 면 x/y 선형 폴백이다. */
  exact: boolean;
}

/** 게임 좌표계(width×height) → 원본 지형 셀. 기존 캔버스 mapCityToTile 과 같은 식이다. */
export function gameXyToSourceCell(
  x: number, y: number,
  source: { width: number; height: number },
  grid: { cols: number; rows: number },
): { col: number; row: number } {
  return {
    col: (x * grid.cols) / source.width,
    row: (y * grid.rows) / source.height,
  };
}

export interface PlaceGameCitiesOptions {
  /** 게임 좌표계 크기(MapPreviewResponse.width/height). 폴백에만 쓴다. */
  sourceSize: { width: number; height: number };
  /** 국가 id → 색·이름. 없는 id 는 중립으로 둔다. */
  nations?: ReadonlyMap<number, { name: string; color: string }>;
}

/**
 * 게임 도시를 타일 좌표에 앉힌다. 격자 밖으로 나가는 도시는 버린다(그릴 자리가 없다).
 * 반환 순서는 입력 순서를 지킨다 — 렌더러가 안정적으로 인스턴스를 세울 수 있게.
 */
export function placeGameCities(
  cities: readonly GameCityInput[],
  data: Pick<IsoMapData, 'grid' | 'provinceSeatCell' | 'sourceCols' | 'sourceRows'>,
  options: PlaceGameCitiesOptions,
): PlacedCity[] {
  const { grid, provinceSeatCell, sourceCols, sourceRows } = data;
  const placed: PlacedCity[] = [];

  for (const city of cities) {
    let cell: { col: number; row: number } | null = null;
    let exact = false;

    const province = city.provinceId;
    if (province != null && province >= 0 && province < provinceSeatCell.col.length) {
      const col = provinceSeatCell.col[province];
      const row = provinceSeatCell.row[province];
      if (col >= 0 && row >= 0) {
        cell = { col, row };
        exact = true;
      }
    }
    if (!cell) {
      if (!Number.isFinite(city.x) || !Number.isFinite(city.y)) continue;
      cell = gameXyToSourceCell(
        city.x, city.y, options.sourceSize, { cols: sourceCols, rows: sourceRows },
      );
    }

    const col = cell.col / RASTER_GROUP;
    const row = cell.row / RASTER_GROUP;
    const tileCol = Math.min(grid.cols - 1, Math.max(0, Math.floor(col)));
    const tileRow = Math.min(grid.rows - 1, Math.max(0, Math.floor(row)));
    if (col < 0 || col >= grid.cols || row < 0 || row >= grid.rows) continue;

    // 세력 표시는 「소유가 확실할 때만」이다. nationId 0(재야)·음수·비정수,
    // 그리고 '#rrggbb' 가 아닌 색은 이름도 색도 붙이지 않는다 — 기존 캔버스가
    // 지키던 규칙(isOwnedNationVisual)을 아이소판에서도 같은 함수로 지킨다.
    const candidate = options.nations?.get(city.nationId);
    const nation = isOwnedNationVisual(city.nationId, candidate?.color) ? candidate : undefined;
    placed.push({
      id: city.id,
      name: city.name,
      level: city.level,
      nationId: city.nationId,
      col,
      row,
      tileCol,
      tileRow,
      seat: city.isCommanderySeat === true,
      isCapital: city.isCapital === true,
      nationColor: nation?.color,
      nationName: nation?.name,
      exact,
    });
  }

  return placed;
}

/**
 * 전장 표식. 게임 도시가 아니라 위경도로 오는 진행 중 전투다.
 * 좌표는 projectBattlefieldTarget(원본 셀) 을 RASTER_GROUP 으로 나눈 **소수** 타일이다.
 */
export interface IsoBattlefieldMarker {
  id: string;
  name: string;
  col: number;
  row: number;
  /** 내 장수가 지금 이 전장에 있다. */
  current?: boolean;
}

/**
 * 전장 위경도를 아이소 타일 좌표로 옮긴다. 투영식은 기존 캔버스와 같은 함수를 쓴다
 * (HanMapCanvas.tsx projectBattlefieldTarget) — 여기서 새로 세우지 않는다.
 * 투영이 없거나 격자 밖이면 그 전장은 빠진다.
 */
export function placeBattlefields(
  targets: readonly { id: string; name: string; latitude: number; longitude: number; current?: boolean }[],
  projection: BattlefieldMapProjection | undefined,
  source: { cols: number; rows: number },
  grid: { cols: number; rows: number },
): IsoBattlefieldMarker[] {
  const placed: IsoBattlefieldMarker[] = [];
  for (const target of targets) {
    const cell = projectBattlefieldTarget(
      target.latitude, target.longitude, projection, source.cols, source.rows,
    );
    if (!cell) continue;
    const col = cell.col / RASTER_GROUP;
    const row = cell.row / RASTER_GROUP;
    if (col < 0 || col >= grid.cols || row < 0 || row >= grid.rows) continue;
    placed.push({ id: target.id, name: target.name, col, row, current: target.current === true });
  }
  return placed;
}
