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
// 실측(han-world-v3, 2026-09-09, 781 城 시절): 게임 도시 781 중 773 이 이 길로 원본 셀 좌표를
// 얻고, 서로 다른 도시가 같은 셀에 겹치는 경우는 0 이었다. 이름도 773/773 이 맞았다
// (예: 게임 "장안(京兆尹)" → provinceRecords[503] "장안현" → cities[1033]).
//
// 2026-09-11 변경 縣 51 곳이 게임 城 으로 서면서(782–832) 변경 郡 일곱 곳도 縣 구획을 얻어
// provinceId 가 붙었다. provinceId 가 없는 城 은 835 중 넷 — 구자속국(704)과 城 없던 郡
// 3 곳의 治所(833–835)다. 넷 다 縣 구획이 아니라 郡 직할 땅 위에 서 있어 縣 省이 없다.
// 그 넷만 좌표 폴백을 쓴다. 폴백은 기존 캔버스의 mapCityToTile 과 같은 선형식이다.
//
// 郡國 밖 세력(EXTERNAL_PLACE)도 여기서 같이 앉힌다 — 중원과 다른 그림으로 그리지 않는다.
// 자세한 것은 아래 placeExternalPlaces 주석. 關(관문)은 한때 여기서 표시 전용으로 덧댔지만(strategicPasses.ts),
// 2026-09-15 부로 수·진·관 거점 73 곳이 han-tiles 省을 떼어 받아 han-world-v3 城 1025–1097 로 선다 —
// 다른 城 과 같은 길로 들어오므로 덧대면 두 번 그려진다.
// 변경 縣(交趾·九真·日南·遼東·玄菟·樂浪·遼東屬國 屬縣 51 곳)은 한때 여기서 표시 전용으로
// 덧댔지만(PR #698 frontierCounties.ts), 2026-09-11 부로 han-tiles 縣 구획 → 경로 노드 →
// han-world-v3 城 782–832 로 서버 세계에 들어갔다 — 다른 城 과 같은 길로 들어온다.

import type { IsoMapData } from './useIsoTileGrid';
import { RASTER_GROUP } from '../isoTileGrid';
import { isOwnedNationVisual } from '../nationVisual';
import { externalPlaceLevel } from './externalPlaceTier';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';
import { resolveCityFootprints } from './cityFootprint';
import { cellFootprintInTiles } from './buildingFit';

/** 배치 입력. MapPreviewCity 에서 필요한 만큼만 뽑은 모양이다. */
export interface GameCityInput {
  id: number;
  name: string;
  /** han.json meta.nameCh. 縣 판정에만 쓴다 — cityName.ts 참조. */
  nameCh?: string;
  /** 서버가 계산한 화면 이름(meta.displayName). 오면 그대로 쓴다 — cityName.ts 참조. */
  displayName?: string;
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
 * col/row 는 **소수**다. 타일로 반올림하면 안 된다 — 같은 타일에 묶인 城 은 뒤에 오는 쪽이
 * 통째로 가려지기 때문이다. 실측: rasterGroup 4 에서 39 타일 / 城 79 곳, 2 로 내린 뒤에도
 * 4 타일 / 城 8 곳이 남는다.
 * 그러면 그 37 곳은 눌러서 들어갈 수 없다. 원본 셀 해상도를 그대로 들고 다니면
 * 확대했을 때 서로 떨어져 각각 집힌다. 높이를 볼 때만 tile 로 내린다.
 *
 * 다만 **그릴 때는** col/row 를 쓰지 않는다. drawCol/drawRow/drawScale 을 쓴다 —
 * 아래 fitCityFootprints 주석 참조.
 */
export interface PlacedCity {
  /** 게임 도시 번호. **음수면 게임 城 이 아니다**(郡國 밖 세력) — isExternalPlace 참조. */
  id: number;
  name: string;
  /** 행정 단위가 붙은 원 표기("长安县"). 화면 이름을 「뭐뭐현」으로 짓는 데 쓴다. */
  nameCh?: string;
  /** 서버가 계산한 화면 이름. cityDisplayName 이 그대로 쓴다. */
  displayName?: string;
  level: number;
  nationId: number;
  /** 소수 타일 좌표(원본 셀 / rasterGroup). */
  col: number;
  row: number;
  /** 높이·소유를 읽을 정수 타일. */
  tileCol: number;
  tileRow: number;
  /** 건물을 세울 자리 = 성내 중심(소수 타일 좌표). fitCityFootprints 참조. */
  drawCol: number;
  drawRow: number;
  /** 성내 한 변(타일 폭 = 1). 실루엣이 이 폭을 채운다. 마커 칸까지 같은 城 이 n 곳이면 1/n. */
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
  data: Pick<
    IsoMapData,
    'grid' | 'provinceSeatCell' | 'sourceCols' | 'sourceRows' | 'cities' | 'projection'
  >,
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
      // 縣 구획 없이 郡 직할 省에 선 城(龜茲屬國·대리 治所)도 좌표가 제 省 밖이면 안으로 민다.
      if (province != null && province >= 0) {
        cell = provinceSeatCell.insideProvince?.(province, cell.col, cell.row) ?? cell;
      }
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
      displayName: city.displayName,
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
  fitCityFootprints(placed);
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
    // 지형 cities[] 의 col/row 는 이미 **정수 타일**이다(sourceCellToTile). 원본 셀이 실려 있으면
    // 게임 城 과 같은 소수 타일 좌표로 편다 — 성내가 원본 칸 단위라 타일 구석으로 쏠리지 않게.
    const tileCol = city.col;
    const tileRow = city.row;
    if (tileCol < 0 || tileCol >= grid.cols || tileRow < 0 || tileRow >= grid.rows) continue;
    const col = city.sourceCol !== undefined ? city.sourceCol / RASTER_GROUP : tileCol;
    const row = city.sourceRow !== undefined ? city.sourceRow / RASTER_GROUP : tileRow;
    placed.push({
      id: -(index + 1),
      name: city.name,
      // 지형이 실어 보낸 level 은 자리표시 4 다 — 그대로 쓰면 백제국도 흉노도 같은
      // 이민족 야영으로 선다. 행정 계통으로 갈라 세운다(externalPlaceTier.ts 참조).
      level: externalPlaceLevel(city),
      nationId: 0,
      col,
      row,
      tileCol,
      tileRow,
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
 * 건물을 **성내에 꽉 맞춰** 앉힌다(2026-09-23 사용자 결정 「성내에 꽉 맞춤」, 모든 지도 화면).
 *
 * 성내는 원본 격자 칸으로 정한다 — 등급별 변(cityFootprint.ts)을 이웃과 겹치지 않게 푼
 * resolveCityFootprints 의 값이다. HanMapCanvas 도 같은 함수로 풀어 세 지도가 같은 땅을 가리킨다.
 * 아이소 타일 한 칸은 원본 칸 RASTER_GROUP² 개라 성내 폭은 span/G 타일이다(buildingFit.ts).
 *
 *  - drawCol/drawRow = 성내 중심(타일 좌표, 소수). 변이 홀수라 마커 칸의 중심이다.
 *  - drawScale = 성내 한 변(타일 단위). 렌더러는 실루엣 폭을 이 값에 맞춘다.
 *
 * 예전 규칙(2026-09-09, 「셀과 아이콘이 안 맞는다」)은 **타일**을 발자국으로 보고 한 타일에 든
 * 城 을 1/n 로 줄여 벌렸다. 이제는 원본 칸이 발자국이라 한 타일 안의 다른 칸에 선 城 들은
 * 줄이지 않아도 서로 떨어져 선다. 다만 **마커 칸까지 같은** 城 (좌표 폴백·랩의 타일 정수 좌표)은
 * 겹쳐 두면 뒤엣것을 못 누르므로, 그 무리만 예전처럼 1/n 로 줄여 성내 안에서 벌린다.
 *
 * 성내 변을 풀 때 게임 城(음수가 아닌 고유 id)은 게임 城 끼리만 푼다 — HanMapCanvas 가 그렇게
 * 풀기 때문에 같은 城 이 두 지도에서 다른 크기로 서면 안 된다. 郡國 밖 세력은 전체 목록에서
 * 게임 城 뒤 순서로 푼다.
 */
export function fitCityFootprints(placed: PlacedCity[], group: number = RASTER_GROUP): void {
  const cells = placed.map((city) => ({
    col: Math.round(city.col * group),
    row: Math.round(city.row * group),
  }));
  const seen = new Set<number>();
  const isGame = placed.map((city) => {
    if (city.id < 0 || seen.has(city.id)) return false;
    seen.add(city.id);
    return true;
  });
  // 게임 城 이 아닌 항목(음수·중복 id)은 게임 城 번호 뒤로 대리 번호를 준다.
  let nextId = placed.reduce((max, city, i) => (isGame[i] ? Math.max(max, city.id) : max), -1) + 1;
  const keys = placed.map((city, i) => (isGame[i] ? city.id : nextId++));
  const entries = placed.map((city, i) => ({ id: keys[i], level: city.level, ...cells[i] }));
  const gameSpans = resolveCityFootprints(entries.filter((_, i) => isGame[i]));
  const allSpans = isGame.every(Boolean) ? gameSpans : resolveCityFootprints(entries);

  const groups = new Map<string, number[]>();
  placed.forEach((city, i) => {
    const span = (isGame[i] ? gameSpans : allSpans).get(keys[i]) ?? 1;
    const footprint = cellFootprintInTiles(cells[i].col, cells[i].row, span, group);
    city.drawCol = footprint.centerCol;
    city.drawRow = footprint.centerRow;
    city.drawScale = footprint.width;
    const key = `${cells[i].col},${cells[i].row}`;
    const members = groups.get(key);
    if (members) members.push(i);
    else groups.set(key, [i]);
  });

  for (const members of groups.values()) {
    const n = members.length;
    if (n < 2) continue;
    // 가장 작은 성내 안에서 벌린다. 폭 w/n 인 정사각형이 폭 w 안에 들려면 중심이 축마다
    // (w - w/n)/2 안이어야 하고, 마름모 노름(|dc|+|dr|)을 그 안으로 누르면 충분하다.
    const smallest = Math.min(...members.map((i) => placed[i].drawScale));
    const budget = (smallest * (1 - 1 / n)) / 2;
    // 원좌표의 상대 배치를 지킨다 — 어느 쪽이 동북인지 남는다.
    const offsets = members.map((i) => [placed[i].col, placed[i].row] as [number, number]);
    const midCol = offsets.reduce((sum, o) => sum + o[0], 0) / n;
    const midRow = offsets.reduce((sum, o) => sum + o[1], 0) / n;
    let peak = 0;
    for (const offset of offsets) {
      offset[0] -= midCol;
      offset[1] -= midRow;
      peak = Math.max(peak, Math.abs(offset[0]) + Math.abs(offset[1]));
    }
    // 좌표까지 똑같으면 벌릴 방향이 없다 — 마름모 둘레로 돌려세운다.
    if (peak < 1e-6) {
      peak = 0;
      for (let j = 0; j < n; j += 1) {
        const angle = (Math.PI * 2 * j) / n;
        offsets[j] = [Math.cos(angle), Math.sin(angle)];
        peak = Math.max(peak, Math.abs(offsets[j][0]) + Math.abs(offsets[j][1]));
      }
    }
    const k = budget / peak;
    members.forEach((i, j) => {
      placed[i].drawCol += offsets[j][0] * k;
      placed[i].drawRow += offsets[j][1] * k;
      placed[i].drawScale /= n;
    });
  }
}

/** @deprecated 타일이 아니라 성내에 맞춘다 — fitCityFootprints 를 써라. 랩 화면 호환용 이름이다. */
export const fitFootprintsInTile = fitCityFootprints;

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
