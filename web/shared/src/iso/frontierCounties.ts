// 지도에 세우는 변경(邊境) 縣.
//
// 「그리고 왜 교지, 일남, 구진등에 현이 없지?」(2026-09-10). 없던 이유는 이렇다 — 세계
// 생성기는 CHGIS 縣 점을 받아 城 을 세우는데, 交州·幽州 변경에는 그 점이 한 개도 없다.
// 그래서 《續漢書·郡國五》가 樂浪 18城·遼東 11城·玄菟 6城·交趾 12城 을 이름까지 적어 두었는데도
// 화면에는 郡 표식 하나씩만 서 있었다.
//
// 여기 실린 25 곳은 전부 **郡國志에 이름이 있고**, 저장소 안의 현대 좌표 수확본
// (namu-place-locations-v1)에 좌표가 있는 것만이다. 근거는 한 줄씩
// `data/curated/han/frontier-counties-v1.json` 에 인용까지 달아 두었고, 이 표가 그 원장과
// 어긋나면 __tests__/frontierCounties.test.ts 가 빨개진다.
//
// **게임 城 이 아니다.** id 가 음수라 눌러도 들어갈 데가 없고(isExternalPlace), 소유도
// 보급도 없다. 게임 城 으로 만들려면 세계 생성기를 다시 돌려야 하는데 그 입력
// (terrain-grid.json·han-places.json)이 ADR-LITE-039 로 저장소 밖이라 지금은 못 한다.
// 그래서 이 단계는 **표시까지**다 — 關(strategicPasses.ts)과 같은 자리다.
//
// 못 세운 것도 원장의 `excluded`·`coverage` 에 적어 두었다.
//   · 九真郡 5城 · 日南郡 5城 — 수확본에 좌표가 한 줄도 없다. 자리를 지어내지 않는다.
//   · 郡治 넷(朝鮮·高句驪·襄平·龍編) — 이미 郡 노드가 그 자리에 서 있다(실측 1.4~4.0km).
//   · 候城 — 郡國志가 遼東·玄菟 양쪽에 적었고 校勘記가 遼東 쪽을 衍文으로 판정한다.

import { RASTER_GROUP } from '../isoTileGrid';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';
import type { PlacedCity } from './placeGameCities';

/**
 * 縣 이 서는 등급. 建物 표(IsoMap2D/IsoMap3D BUILDING_TIERS)의 11 = county-small.
 *
 * 생성기와 같은 규칙으로 정했다(build_han_world.py v3_level: 郡治가 아니면 戶÷城 이
 * 10,000 이상일 때만 영현, 아니면 장현). 실측 樂浪 61,492÷18=3,416 · 遼東 64,158÷11=5,832 ·
 * 玄菟 1,594÷6=265 이고 交趾는 郡國志에 戶口가 없다 — 넷 다 장현이다.
 */
export const FRONTIER_COUNTY_LEVEL = 11;

/**
 * 변경 縣 id 를 뺄 밑자리.
 *
 * 郡國 밖 세력은 `-(cities[] 인덱스+1)`(실측 1,524 항목), 關 은 -1,000,000 부터 쓴다.
 * 여기서 한 단 더 내려 잡아 세 집합이 절대 겹치지 않게 한다. 음수라는 것 자체가
 * 「게임 城 이 아니다」는 표시다(placeGameCities.isExternalPlace).
 */
const FRONTIER_COUNTY_ID_BASE = -2_000_000;

export interface FrontierCounty {
  /** 원장(frontier-counties-v1.json)의 id — 「hhs:113:<郡>:<郡國志 순번>」. */
  id: string;
  nameKo: string;
  nameHan: string;
  commanderyHan: string;
  latitude: number;
  longitude: number;
}

/**
 * 좌표는 나무위키 「삼국지/지명」 수확본의 명시 좌표다 — 關·전장 표식이 쓰는 것과 같은
 * 출처·같은 투영이라 새로 세운 이음매가 없다. 전부 APPROXIMATE 이다.
 */
export const FRONTIER_COUNTIES: readonly FrontierCounty[] = [
  { id: 'hhs:113:遼東郡:002', nameKo: '신창', nameHan: '新昌', commanderyHan: '遼東郡', latitude: 41.07035, longitude: 122.95277 },
  { id: 'hhs:113:遼東郡:004', nameKo: '망평', nameHan: '望平', commanderyHan: '遼東郡', latitude: 42.1162, longitude: 123.60954 },
  { id: 'hhs:113:遼東郡:006', nameKo: '안시', nameHan: '安市', commanderyHan: '遼東郡', latitude: 40.80142, longitude: 122.6179 },
  { id: 'hhs:113:遼東郡:007', nameKo: '평곽', nameHan: '平郭', commanderyHan: '遼東郡', latitude: 40.40111, longitude: 122.36578 },
  { id: 'hhs:113:遼東郡:008', nameKo: '서안평', nameHan: '西安平', commanderyHan: '遼東郡', latitude: 40.19671, longitude: 124.47495 },
  { id: 'hhs:113:遼東郡:009', nameKo: '문', nameHan: '汶', commanderyHan: '遼東郡', latitude: 40.54297, longitude: 122.65474 },
  { id: 'hhs:113:遼東郡:011', nameKo: '답씨', nameHan: '沓氏', commanderyHan: '遼東郡', latitude: 41.25893, longitude: 122.78741 },
  { id: 'hhs:113:玄菟郡:004', nameKo: '고현', nameHan: '高顯', commanderyHan: '玄菟郡', latitude: 41.60318, longitude: 123.43453 },
  { id: 'hhs:113:玄菟郡:005', nameKo: '후성', nameHan: '候城', commanderyHan: '玄菟郡', latitude: 41.82015, longitude: 123.66851 },
  { id: 'hhs:113:玄菟郡:006', nameKo: '요양', nameHan: '遼陽', commanderyHan: '玄菟郡', latitude: 41.53592, longitude: 122.92814 },
  { id: 'hhs:113:樂浪郡:005', nameKo: '점제', nameHan: '占蟬', commanderyHan: '樂浪郡', latitude: 38.86008, longitude: 125.25723 },
  { id: 'hhs:113:樂浪郡:006', nameKo: '수성', nameHan: '遂城', commanderyHan: '樂浪郡', latitude: 39.26227, longitude: 126.12192 },
  { id: 'hhs:113:樂浪郡:007', nameKo: '증지', nameHan: '增地', commanderyHan: '樂浪郡', latitude: 39.61897, longitude: 125.6587 },
  { id: 'hhs:113:樂浪郡:009', nameKo: '사망', nameHan: '駟望', commanderyHan: '樂浪郡', latitude: 39.40332, longitude: 125.50904 },
  { id: 'hhs:113:樂浪郡:013', nameKo: '둔유', nameHan: '屯有', commanderyHan: '樂浪郡', latitude: 38.78307, longitude: 125.86294 },
  { id: 'hhs:113:樂浪郡:015', nameKo: '누방', nameHan: '鏤方', commanderyHan: '樂浪郡', latitude: 38.99804, longitude: 126.18098 },
  { id: 'hhs:113:樂浪郡:017', nameKo: '혼미', nameHan: '渾彌', commanderyHan: '樂浪郡', latitude: 39.6138, longitude: 125.965 },
  { id: 'hhs:113:交趾郡:002', nameKo: '이루', nameHan: '羸𨻻', commanderyHan: '交趾郡', latitude: 21.08389, longitude: 105.80848 },
  { id: 'hhs:113:交趾郡:003', nameKo: '안정', nameHan: '安定', commanderyHan: '交趾郡', latitude: 20.65468, longitude: 106.05784 },
  { id: 'hhs:113:交趾郡:004', nameKo: '구루', nameHan: '苟漏', commanderyHan: '交趾郡', latitude: 21.05049, longitude: 105.57406 },
  { id: 'hhs:113:交趾郡:006', nameKo: '곡양', nameHan: '曲陽', commanderyHan: '交趾郡', latitude: 20.93734, longitude: 106.31455 },
  { id: 'hhs:113:交趾郡:007', nameKo: '북대', nameHan: '北帶', commanderyHan: '交趾郡', latitude: 20.98546, longitude: 106.04637 },
  { id: 'hhs:113:交趾郡:008', nameKo: '계서', nameHan: '稽徐', commanderyHan: '交趾郡', latitude: 20.88306, longitude: 106.14412 },
  { id: 'hhs:113:交趾郡:009', nameKo: '서어', nameHan: '西于', commanderyHan: '交趾郡', latitude: 21.11594, longitude: 105.75689 },
  { id: 'hhs:113:交趾郡:012', nameKo: '망해', nameHan: '望海', commanderyHan: '交趾郡', latitude: 21.24807, longitude: 105.94722 },
];

/**
 * 화면에 적을 이름.
 *
 * 「지도나 도시 출력은 중국의 군현제 안의 경우 뭐뭐현으로 통일해」(2026-09-10)에 따라 縣 을
 * 붙인다. 다만 cityDisplayName 은 **id 가 음수면 縣 판정을 하지 않는다**(cityName.ts:
 * 郡國 밖 세력이 「이민족현」이 되는 것을 막는 가드). 이쪽은 진짜 縣 이므로 그 가드를
 * 느슨하게 만드는 대신 이름에 직접 적어 둔다 — 가드는 郡國 밖 세력을 계속 지킨다.
 */
export function frontierCountyDisplayName(county: FrontierCounty): string {
  return `${county.nameKo}현`;
}

/**
 * 변경 縣 을 타일 좌표에 앉힌다. 투영은 전장·關 과 같은 함수를 쓴다
 * (projectBattlefieldTarget). 투영이 없거나 격자 밖으로 떨어지는 縣 은 빠진다 —
 * 자리를 지어내지 않는다.
 */
export function placeFrontierCounties(
  projection: BattlefieldMapProjection | undefined,
  source: { cols: number; rows: number },
  grid: { cols: number; rows: number },
): PlacedCity[] {
  const placed: PlacedCity[] = [];
  for (let i = 0; i < FRONTIER_COUNTIES.length; i += 1) {
    const county = FRONTIER_COUNTIES[i];
    const cell = projectBattlefieldTarget(
      county.latitude, county.longitude, projection, source.cols, source.rows,
    );
    if (!cell) continue;
    const col = cell.col / RASTER_GROUP;
    const row = cell.row / RASTER_GROUP;
    if (col < 0 || col >= grid.cols || row < 0 || row >= grid.rows) continue;
    placed.push({
      id: FRONTIER_COUNTY_ID_BASE - i,
      name: frontierCountyDisplayName(county),
      // 원 표기는 郡國志가 적은 그대로 둔다 — 「駟望縣」처럼 없는 형태를 만들지 않는다.
      nameCh: county.nameHan,
      level: FRONTIER_COUNTY_LEVEL,
      nationId: 0,
      col,
      row,
      tileCol: Math.floor(col),
      tileRow: Math.floor(row),
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
