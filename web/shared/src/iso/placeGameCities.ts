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
//
// 郡國 밖 세력(EXTERNAL_PLACE)도 여기서 같이 앉힌다 — 중원과 다른 그림으로 그리지 않는다.
// 자세한 것은 아래 placeExternalPlaces 주석.

import type { IsoMapData } from './useIsoTileGrid';
import { RASTER_GROUP } from '../isoTileGrid';
import { isOwnedNationVisual } from '../nationVisual';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';

/** 배치 입력. MapPreviewCity 에서 필요한 만큼만 뽑은 모양이다. */
export interface GameCityInput {
  id: number;
  name: string;
  /** han.json meta.nameCh. 縣 판정에만 쓴다 — cityName.ts 참조. */
  nameCh?: string;
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
 *
 * 다만 **그릴 때는** col/row 를 쓰지 않는다. drawCol/drawRow/drawScale 을 쓴다 —
 * 아래 fitFootprintsInTile 주석 참조.
 */
export interface PlacedCity {
  /** 게임 도시 번호. **음수면 게임 城 이 아니다**(郡國 밖 세력) — isExternalPlace 참조. */
  id: number;
  name: string;
  /** 행정 단위가 붙은 원 표기("长安县"). 화면 이름을 「뭐뭐현」으로 짓는 데 쓴다. */
  nameCh?: string;
  level: number;
  nationId: number;
  /** 소수 타일 좌표(원본 셀 / rasterGroup). */
  col: number;
  row: number;
  /** 높이·소유를 읽을 정수 타일. */
  tileCol: number;
  tileRow: number;
  /** 건물을 세울 자리. 발자국이 제 칸 안에 들도록 눌러 둔 소수 타일 좌표. */
  drawCol: number;
  drawRow: number;
  /** 발자국 배율(타일 폭 = 1). 같은 칸에 여럿이 들면 함께 줄어든다. */
  drawScale: number;
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
  data: Pick<IsoMapData, 'grid' | 'provinceSeatCell' | 'sourceCols' | 'sourceRows' | 'cities'>,
  options: PlaceGameCitiesOptions,
): PlacedCity[] {
  const { grid, provinceSeatCell, sourceCols, sourceRows } = data;
  const placed: PlacedCity[] = [];
  // 게임 城 이 이미 서 있는 지형 cities[] 항목. 아래에서 郡國 밖 세력을 덧댈 때
  // 같은 곳을 두 번 세우지 않으려고 모은다.
  const covered = new Set<number>();

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
        const index = provinceSeatCell.cityIndex[province];
        if (index >= 0) covered.add(index);
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
      nameCh: city.nameCh,
      level: city.level,
      nationId: city.nationId,
      col,
      row,
      tileCol,
      tileRow,
      // 그리기 자리는 아래에서 칸에 맞춘다. 여기서는 자리를 비워 두지 않는다 —
      // 한 번도 안 맞춘 도시가 (0,0) 에 서는 사고를 막으려고 제자리로 채워 둔다.
      drawCol: col,
      drawRow: row,
      drawScale: 1,
      seat: city.isCommanderySeat === true,
      isCapital: city.isCapital === true,
      nationColor: nation?.color,
      nationName: nation?.name,
      exact,
    });
  }

  placed.push(...placeExternalPlaces(data.cities, grid, covered));
  fitFootprintsInTile(placed);
  return placed;
}

/**
 * 게임 城 이 아닌 곳에 붙는 id. 음수라 게임 도시 번호와 절대 겹치지 않는다.
 * 이 id 를 든 城 은 눌러도 들어갈 데가 없으므로 렌더러가 집기 상자에서 뺀다.
 */
export function isExternalPlace(city: PlacedCity): boolean {
  return city.id < 0;
}

/**
 * 집기 광선이 앞에서부터 맞힌 것들 중 **들어갈 수 있는 첫 城**.
 *
 * 郡國 밖 세력도 중원의 城 과 똑같은 건물 메시로 서 있으므로 광선에 그대로 맞는다.
 * 그렇다고 제일 가까운 것 하나만 보고 포기하면, 앞에 선 이민족 하나가 뒤의 城 을
 * 통째로 못 누르게 만든다. 맞은 순서대로 훑어 게임 城 이 나오면 그것을 집는다.
 * 맞은 게 없거나 전부 郡國 밖이면 null — 부르는 쪽은 지형 집기로 내려간다.
 */
export function firstPickableCity(hits: readonly (PlacedCity | undefined)[]): PlacedCity | null {
  for (const city of hits) {
    if (city && !isExternalPlace(city)) return city;
  }
  return null;
}

/**
 * 郡國 밖 세력을 **城 과 같은 자리에** 세운다.
 *
 * 지형 응답의 `EXTERNAL_PLACE` 37 곳(백제국·사로국·부여·야마일국·대마국·흉노 …)이다.
 * 이 중 31 곳은 이미 게임 城 이라 위 루프가 세운다 — provinceRecords[provinceId].cityIndex
 * 가 바로 그 항목을 가리킨다(실측 31/37). 그래서 예전 렌더러처럼 37 곳을 통째로 따로
 * 그리면 **같은 곳에 城 아이콘과 속 빈 마름모가 겹쳐** 섰다. 한반도·왜가 중원과 다른
 * 그림으로 보인 이유가 이것이다.
 *
 * 남는 6 곳(일대국·이도국·노국·대마국·읍루·말로국)만 게임 城 이 없다. 그 여섯도 중원과
 * 같은 건물 아이콘으로 세운다 — 등급 4('이')라 tribal 이 붙는다. 다만 게임 도시 번호가
 * 없으므로 id 를 음수로 두고, 렌더러는 그것만 보고 집기에서 뺀다.
 */
function placeExternalPlaces(
  terrainCities: IsoMapData['cities'],
  grid: { cols: number; rows: number },
  covered: ReadonlySet<number>,
): PlacedCity[] {
  const placed: PlacedCity[] = [];
  for (let index = 0; index < terrainCities.length; index += 1) {
    const city = terrainCities[index];
    if (city.kind !== 'EXTERNAL_PLACE' || covered.has(index)) continue;
    // 지형 cities[] 의 col/row 는 이미 **정수 타일**이다(sourceCellToTile).
    const { col, row } = city;
    if (col < 0 || col >= grid.cols || row < 0 || row >= grid.rows) continue;
    placed.push({
      id: -(index + 1),
      name: city.name,
      level: city.level,
      nationId: 0,
      col,
      row,
      tileCol: col,
      tileRow: row,
      drawCol: col,
      drawRow: row,
      drawScale: 1,
      seat: city.seat === true,
      isCapital: false,
      // 좌표는 CHGIS 실측 경위도에서 왔다 — x/y 선형 폴백이 아니다.
      exact: true,
    });
  }
  return placed;
}

/**
 * 건물 발자국을 제 칸 안에 앉힌다.
 *
 * 두 가지가 겹쳐 있었다.
 *
 *  1) **규약 어긋남.** 렌더러는 정수 (col,row) 를 다이아몬드 **중심**으로 쓴다
 *     (tileToScreen · IsoMap2D 의 세력색 채우기가 x±HALF_W, y±HALF_H 로 칠한다).
 *     그런데 여기서는 원본 셀을 `cell.col / RASTER_GROUP` 으로만 나눠 넘겼다. 그러면
 *     한 타일에 든 4×4 셀이 중심 기준 0 … +0.75 로 **한쪽으로만** 쏠린다.
 *  2) **발자국 크기.** 건물 스프라이트·모델의 밑면은 타일 하나 크기다. 중심이 조금만
 *     밀려도 성벽이 옆 칸을 밟는다.
 *
 * 실측(han-world-v3, 縣 998 곳): 제자리는 61 곳뿐이고 620 곳(62.1%)은 건물 중심이 아예
 * 제 칸 밖이었다. 세로로 최대 96px(1배율) 밀린다 — 「아이콘이 격자에서 삐져나온다 ·
 * 삐뚤빼뚤하잖아」(2026-09-09).
 *
 * 그렇다고 소수부를 버리면 안 된다. 같은 칸에 두 城 이 든 자리가 실측 54 칸 있는데
 * 겹쳐 세우면 뒤엣것을 못 누른다. 그래서 **칸 안에서만** 벌린다 — 혼자면 칸 한가운데,
 * n 곳이면 발자국을 1/n 로 줄이고 남는 1-1/n 을 서로 벌리는 데 쓴다. 벌리는 방향은
 * 실제 좌표의 상대 배치를 지키므로 어느 쪽이 동북인지도 그대로 남는다.
 */
export function fitFootprintsInTile(placed: PlacedCity[]): void {
  const groups = new Map<string, PlacedCity[]>();
  for (const city of placed) {
    const key = `${city.tileCol},${city.tileRow}`;
    const group = groups.get(key);
    if (group) group.push(city);
    else groups.set(key, [city]);
  }

  for (const group of groups.values()) {
    const n = group.length;
    const scale = 1 / n;
    // 발자국이 1/n 이면 칸 안에서 중심이 움직일 수 있는 한계는 마름모 노름 1-1/n 이다.
    const budget = 1 - scale;

    // 칸 중심에서 벗어난 양. 원본 셀 s 의 한가운데는 타일 단위로 (s+0.5)/G 이고,
    // 타일 t 의 중심은 정수 t 이므로 t 를 빼면 -0.375 … +0.375 로 고르게 퍼진다.
    const offsets = group.map((city) => [
      city.col + 0.5 / RASTER_GROUP - city.tileCol,
      city.row + 0.5 / RASTER_GROUP - city.tileRow,
    ] as [number, number]);
    const midCol = offsets.reduce((sum, o) => sum + o[0], 0) / n;
    const midRow = offsets.reduce((sum, o) => sum + o[1], 0) / n;
    let peak = 0;
    for (const offset of offsets) {
      offset[0] -= midCol;
      offset[1] -= midRow;
      peak = Math.max(peak, Math.abs(offset[0]) + Math.abs(offset[1]));
    }
    // 좌표까지 똑같으면 벌릴 방향이 없다 — 마름모 둘레로 돌려세운다. 겹쳐 두면 못 누른다.
    if (n > 1 && peak < 1e-6) {
      for (let i = 0; i < n; i += 1) {
        const angle = (Math.PI * 2 * i) / n;
        offsets[i][0] = Math.cos(angle);
        offsets[i][1] = Math.sin(angle);
        peak = Math.max(peak, Math.abs(offsets[i][0]) + Math.abs(offsets[i][1]));
      }
    }

    const k = peak > 0 ? budget / peak : 0;
    for (let i = 0; i < n; i += 1) {
      group[i].drawCol = group[i].tileCol + offsets[i][0] * k;
      group[i].drawRow = group[i].tileRow + offsets[i][1] * k;
      group[i].drawScale = scale;
    }
  }
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
