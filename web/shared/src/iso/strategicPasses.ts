// 지도에 세우는 關(관문).
//
// 郡國志 832 城 은 郡治·縣治다. 관문은 그 목록에 없다 — 함곡관도 검각도 城 이 아니라
// **길목**이라, 여태 아이소 지도에 한 곳도 서지 않았다. 옛 지도(data/extracted/map/che.json)
// 는 등급 3 자리에 8 곳을 두고 있었지만 그중 셋은 演義 산물이었다.
//
// 여기 실린 8 곳은 전부 **正史에 이름이 나오고**(續漢書 郡國志 또는 三國志), 저장소 안의
// 현대 좌표 수확본에 좌표가 있는 것만이다. 근거는 한 줄씩
// `data/curated/han/strategic-passes-v1.json` 에 인용까지 달아 두었고, 이 표가 그 원장과
// 어긋나면 __tests__/strategicPasses.test.ts 가 빨개진다.
//
// **게임 城 이 아니다.** id 가 음수라 눌러도 들어갈 데가 없고(isExternalPlace), 소유도
// 보급도 없다. 게임 城 으로 만들려면 세계 생성기를 다시 돌려야 하는데 그 입력
// (terrain-grid.json·han-places.json)이 ADR-LITE-039 로 저장소 밖이라 지금은 못 한다.
// 그래서 이 단계는 **표시까지**다.
//
// 빠진 셋(사수관·가맹관·면죽관)과 그 이유도 같은 원장의 `excluded` 에 적어 두었다.

import { RASTER_GROUP } from '../isoTileGrid';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';
import type { PlacedCity } from './placeGameCities';

/** 關 이 서는 등급. 建物 표(IsoMap2D/IsoMap3D BUILDING_TIERS)의 3 = pass. */
export const PASS_LEVEL = 3;

/**
 * 關 id 를 뺄 밑자리.
 *
 * 郡國 밖 세력은 지형 cities[] 인덱스로 `-(index+1)` 을 쓴다(실측 1,524 항목). 여기서
 * 한참 아래로 내려 잡아 두 집합이 절대 겹치지 않게 한다. 음수라는 것 자체가 「게임 城 이
 * 아니다」는 표시이므로(placeGameCities.isExternalPlace) 그 성질은 그대로 유지된다.
 */
const PASS_ID_BASE = -1_000_000;

export interface StrategicPass {
  /** 원장(strategic-passes-v1.json)의 id. */
  id: string;
  nameKo: string;
  nameHan: string;
  latitude: number;
  longitude: number;
}

/**
 * 좌표는 나무위키 「삼국지/지명」 수확본의 명시 좌표다 — 전장 표식(관도·장판)이 쓰는 것과
 * 같은 출처·같은 투영이라 새로 세운 이음매가 없다. 전부 APPROXIMATE 이다.
 */
export const STRATEGIC_PASSES: readonly StrategicPass[] = [
  { id: 'hanguguan', nameKo: '함곡관', nameHan: '函谷關', latitude: 34.72051, longitude: 112.16587 },
  { id: 'hulaoguan', nameKo: '호뢰관', nameHan: '虎牢關', latitude: 34.84736, longitude: 113.19887 },
  { id: 'hukouguan', nameKo: '호관', nameHan: '壺口關', latitude: 36.16459, longitude: 113.15286 },
  { id: 'xieguguan', nameKo: '사곡관', nameHan: '斜谷關', latitude: 34.1801, longitude: 107.66289 },
  { id: 'wuguan', nameKo: '무관', nameHan: '武關', latitude: 33.60317, longitude: 110.62031 },
  { id: 'yangpingguan', nameKo: '양평관', nameHan: '陽平關', latitude: 33.14761, longitude: 106.60975 },
  { id: 'yanganguan', nameKo: '양안관', nameHan: '陽安關', latitude: 32.965611, longitude: 106.0346 },
  { id: 'jiange', nameKo: '검각', nameHan: '劍閣', latitude: 32.21485, longitude: 105.56415 },
];

/**
 * 關 을 타일 좌표에 앉힌다. 투영은 전장과 같은 함수를 쓴다(projectBattlefieldTarget).
 * 투영이 없거나 격자 밖으로 떨어지는 關 은 빠진다 — 자리를 지어내지 않는다.
 *
 * 실측(han-tiles _meta.projection): 8 곳 모두 격자 안이고, 넷은 가장 가까운 縣 과 같은
 * 타일에 든다(함곡관–곡성현 0.64 · 호뢰관–야왕현 0.17 · 호관–둔류현 0.57 ·
 * 양평관–면양현 0.27 타일). 한 칸에 둘이 서는 것은 fitFootprintsInTile 이 벌려 준다.
 */
export function placeStrategicPasses(
  projection: BattlefieldMapProjection | undefined,
  source: { cols: number; rows: number },
  grid: { cols: number; rows: number },
): PlacedCity[] {
  const placed: PlacedCity[] = [];
  for (let i = 0; i < STRATEGIC_PASSES.length; i += 1) {
    const pass = STRATEGIC_PASSES[i];
    const cell = projectBattlefieldTarget(
      pass.latitude, pass.longitude, projection, source.cols, source.rows,
    );
    if (!cell) continue;
    const col = cell.col / RASTER_GROUP;
    const row = cell.row / RASTER_GROUP;
    if (col < 0 || col >= grid.cols || row < 0 || row >= grid.rows) continue;
    const tileCol = Math.floor(col);
    const tileRow = Math.floor(row);
    placed.push({
      id: PASS_ID_BASE - i,
      name: pass.nameKo,
      // 縣 이 아니다 — cityDisplayName 이 「현」을 붙이지 않도록 한자 표기를 그대로 둔다.
      nameCh: pass.nameHan,
      level: PASS_LEVEL,
      nationId: 0,
      col,
      row,
      tileCol,
      tileRow,
      drawCol: col,
      drawRow: row,
      drawScale: 1,
      seat: false,
      isCapital: false,
      exact: true,
    });
  }
  return placed;
}
