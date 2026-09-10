// 지도에 세우는 변경(邊境) 縣.
//
// 「그리고 왜 교지, 일남, 구진등에 현이 없지?」(2026-09-10). 없던 이유는 이렇다 — 세계
// 생성기는 CHGIS 縣 점을 받아 城 을 세우는데, 交州·幽州 변경에는 그 점이 한 개도 없다.
// 그래서 《續漢書·郡國五》가 이름까지 적어 둔 63 縣이 화면에서는 郡 표식 일곱 개로 보였다.
//
// 여기 실린 51 곳은 전부 **郡國志에 이름이 있고**, 저장소 안의 현대 좌표 수확본에 좌표가
// 있는 것만이다. 근거는 한 줄씩 `data/curated/han/frontier-counties-v1.json` 에 원문 인용과
// 행번호까지 달아 두었고, 이 표가 그 원장과 어긋나면 __tests__/frontierCounties.test.ts 가
// 빨개진다.
//
// **게임 城 이 아니다.** id 가 음수라 눌러도 들어갈 데가 없고(isExternalPlace), 소유도
// 보급도 없다. 게임 城 으로 만들려면 세계 생성기를 다시 돌려야 하는데 그 입력
// (terrain-grid.json·han-places.json)이 ADR-LITE-039 로 저장소 밖이라 지금은 못 한다.
// 그래서 이 단계는 **표시까지**다 — 關(strategicPasses.ts)과 같은 자리다.
//
// ## 25 → 51 (「못세운것은 웹과 사료에서 찾아」, 2026-09-10)
//
// 처음 판(schemaVersion 1)은 4 郡 25 縣이었고 九真·日南·遼東屬國은 한 곳도 못 세웠다.
// 세 축으로 다시 찾았고, **어느 축이 실제로 값을 냈는지**를 그대로 적는다.
//
//   · 사료(讀史方輿紀要·元和郡縣圖志) — 방위만 적는다(「在郡東南」). 좌표가 없다.
//   · 웹(ko/zh/vi/ja 위키백과) — 郡 단위 현대 지명까지만 가고 판본끼리 어긋난다.
//   · **저장소 안의 수확본** — 여기 다 있었다. 못 찾은 게 아니라 **못 읽고 있었다.**
//
// 못 읽던 원인 둘, 둘 다 코드 쪽 결함이다.
//
//   1. **NFKC.** 수확본의 遼는 U+F9C3(CJK 호환 한자)이라 U+907C 와 문자열 비교가 안 맞는다.
//      遼陽·上殷台·西蓋馬·象林이 통째로 「해당 없음」이었다. 정규화 한 줄로 살아났다.
//   2. **결속 축이 하나뿐이었다.** affiliations(「[한]유주자사부 요동군」)만 봤는데 그게
//      비어 있는 레코드가 있고, 나무위키가 晉代 郡(中遼郡·昌黎郡·新昌郡·帶方郡·武平郡·
//      九德郡)으로 묶어 둔 대목도 있다. section 제목을 둘째 축으로 더해 이었다.
//
// 겸해 좌표 출처를 namu-place-locations-v1(227행 요약본)에서 namu-source-records-v1
// (2,658 레코드 원본)으로 바꿨다. 그 과정에서 **沓氏가 남의 좌표를 물고 있던 것**을 잡았다 —
// 41.25893/122.78741 은 遼隧縣 자리다(216.33km 어긋남). 沓氏는 遼東半島 끝 요새라
// 《讀史方輿紀要》卷031 이 「以遼東沓氏縣吏民**渡海**來歸」라 적는다 — 바다를 건너야 오는
// 곳이니 내륙 遼隧일 수 없다. 39.5046/121.6582(大連 金州)로 고쳤다.
//
// ## 못 세운 12
//
//   · **郡治 일곱**(襄平·高句驪·朝鮮·昌遼·龍編·胥浦·西卷) — 이미 郡 노드가 그 자리에 서
//     있다. 거리로 재지 않고 jurisdiction-seat-recoveries-v1 의 seatPlaceId 로 **동일성**을
//     확인했다.
//   · **帶方**(樂浪) — 縣 목록에 있지만 그 자리에 帶方郡 노드(X004)가 이미 서 있다. 같은
//     동일성 규칙으로 걸렀다.
//   · **候城**(遼東) — 郡國志가 遼東·玄菟 양쪽에 적었고 校勘記가 遼東 쪽을 衍文으로 판정한다.
//     玄菟 쪽 한 자리에만 세운다.
//   · **無慮**(遼東屬國) — 같은 중복이고 校勘記는 屬國 쪽을 扶黎의 오기로 본다. 遼東 쪽
//     한 자리에만 세운다(수확본은 屬國으로 적어 두었는데, 그 반대 근거도 원장에 남겼다).
//   · **樂都**(樂浪) — 후보 좌표가 둘이고 어느 쪽인지 못 가린다. 자리를 지어내지 않는다.
//   · **𧦦邯**(樂浪) — 좌표는 있는데 그 타일이 16칸 중 15칸이 바다다. landUnderSeats 가
//     provinceRecords 만 먹어서 변경 縣 밑의 땅은 복원해 주지 않는다 — 지금 세우면 표식이
//     물 위에 뜬다. 별건으로 남긴다.

import { RASTER_GROUP } from '../isoTileGrid';
import { projectBattlefieldTarget, type BattlefieldMapProjection } from '../HanMapCanvas';
import type { PlacedCity } from './placeGameCities';

/**
 * 縣 이 서는 등급. 建物 표(IsoMap2D/IsoMap3D BUILDING_TIERS)의 11 = county-small.
 *
 * 생성기와 같은 규칙으로 정했다(build_han_world.py v3_level: 郡治가 아니면 戶÷城 이
 * 10,000 이상일 때만 영현, 아니면 장현). 실측 樂浪 61,492÷18=3,416 · 遼東 64,158÷11=5,832 ·
 * 玄菟 1,594÷6=265 · 九真 46,513÷5=9,302 · 日南 18,263÷5=3,652 이고 交趾는 郡國志에 戶口가
 * 없다. 遼東屬國은 「此郡獨無戶口」라 적혀 있어 나눌 수가 없다 — 일곱 郡 모두 장현이다.
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
 * 좌표는 나무위키 「삼국지/지명」 수확본(namu-source-records-v1, 2,658 레코드)의 명시
 * 좌표다 — 關·전장 표식이 쓰는 것과 같은 출처·같은 투영이라 새로 세운 이음매가 없다.
 * 전부 APPROXIMATE 이고, 51 중 21 은 원장에 UNCERTAIN·1 은 DISPUTED 로 표시돼 있다.
 *
 * 51 중 25 는 둘째 원장(namu-place-locations-v1)에도 좌표가 있어 대조했다. 24 는 1km
 * 안에서 맞고, 1km 넘게 벌어진 것은 沓氏 하나뿐이다(그쪽이 틀렸다 — 파일 머리 참조).
 *
 * **줄을 섞지 마라.** id 는 `FRONTIER_COUNTY_ID_BASE - i` 로 붙는다 — 배열 순서가 곧
 * 게임 id 다. 순서는 郡國志 그대로 遼東 → 玄菟 → 樂浪 → 遼東屬國 → 交趾 → 九真 → 日南,
 * 그 안은 원문 등장 순번이다.
 */
export const FRONTIER_COUNTIES: readonly FrontierCounty[] = [
  { id: 'hhs:113:遼東郡:002', nameKo: '신창', nameHan: '新昌', commanderyHan: '遼東郡', latitude: 41.07035, longitude: 122.95277 },
  { id: 'hhs:113:遼東郡:003', nameKo: '무려', nameHan: '無慮', commanderyHan: '遼東郡', latitude: 41.48977, longitude: 121.82245 },
  { id: 'hhs:113:遼東郡:004', nameKo: '망평', nameHan: '望平', commanderyHan: '遼東郡', latitude: 42.1162, longitude: 123.60954 },
  { id: 'hhs:113:遼東郡:006', nameKo: '안시', nameHan: '安市', commanderyHan: '遼東郡', latitude: 40.80142, longitude: 122.6179 },
  { id: 'hhs:113:遼東郡:007', nameKo: '평곽', nameHan: '平郭', commanderyHan: '遼東郡', latitude: 40.40111, longitude: 122.36578 },
  { id: 'hhs:113:遼東郡:008', nameKo: '서안평', nameHan: '西安平', commanderyHan: '遼東郡', latitude: 40.19671, longitude: 124.47495 },
  { id: 'hhs:113:遼東郡:009', nameKo: '문', nameHan: '汶', commanderyHan: '遼東郡', latitude: 40.54297, longitude: 122.65474 },
  { id: 'hhs:113:遼東郡:010', nameKo: '번한', nameHan: '番汗', commanderyHan: '遼東郡', latitude: 40.02095, longitude: 123.74679 },
  { id: 'hhs:113:遼東郡:011', nameKo: '답씨', nameHan: '沓氏', commanderyHan: '遼東郡', latitude: 39.5046, longitude: 121.6582 },
  { id: 'hhs:113:玄菟郡:002', nameKo: '서개마', nameHan: '西蓋馬', commanderyHan: '玄菟郡', latitude: 40.5044, longitude: 125.00278 },
  { id: 'hhs:113:玄菟郡:003', nameKo: '상은태', nameHan: '上殷台', commanderyHan: '玄菟郡', latitude: 40.63848, longitude: 124.31611 },
  { id: 'hhs:113:玄菟郡:004', nameKo: '고현', nameHan: '高顯', commanderyHan: '玄菟郡', latitude: 41.60318, longitude: 123.43453 },
  { id: 'hhs:113:玄菟郡:005', nameKo: '후성', nameHan: '候城', commanderyHan: '玄菟郡', latitude: 41.82015, longitude: 123.66851 },
  { id: 'hhs:113:玄菟郡:006', nameKo: '요양', nameHan: '遼陽', commanderyHan: '玄菟郡', latitude: 41.53592, longitude: 122.92814 },
  { id: 'hhs:113:樂浪郡:003', nameKo: '패수', nameHan: '浿水', commanderyHan: '樂浪郡', latitude: 39.86992, longitude: 126.02502 },
  { id: 'hhs:113:樂浪郡:004', nameKo: '함자', nameHan: '含資', commanderyHan: '樂浪郡', latitude: 38.53897, longitude: 125.58951 },
  { id: 'hhs:113:樂浪郡:005', nameKo: '점제', nameHan: '占蟬', commanderyHan: '樂浪郡', latitude: 38.86008, longitude: 125.25723 },
  { id: 'hhs:113:樂浪郡:006', nameKo: '수성', nameHan: '遂城', commanderyHan: '樂浪郡', latitude: 39.26227, longitude: 126.12192 },
  { id: 'hhs:113:樂浪郡:007', nameKo: '증지', nameHan: '增地', commanderyHan: '樂浪郡', latitude: 39.61897, longitude: 125.6587 },
  { id: 'hhs:113:樂浪郡:009', nameKo: '사망', nameHan: '駟望', commanderyHan: '樂浪郡', latitude: 39.40332, longitude: 125.50904 },
  { id: 'hhs:113:樂浪郡:010', nameKo: '해명', nameHan: '海冥', commanderyHan: '樂浪郡', latitude: 38.24951, longitude: 125.09306 },
  { id: 'hhs:113:樂浪郡:011', nameKo: '열구', nameHan: '列口', commanderyHan: '樂浪郡', latitude: 38.52651, longitude: 125.11155 },
  { id: 'hhs:113:樂浪郡:012', nameKo: '장잠', nameHan: '長岑', commanderyHan: '樂浪郡', latitude: 38.23799, longitude: 125.79218 },
  { id: 'hhs:113:樂浪郡:013', nameKo: '둔유', nameHan: '屯有', commanderyHan: '樂浪郡', latitude: 38.78307, longitude: 125.86294 },
  { id: 'hhs:113:樂浪郡:014', nameKo: '소명', nameHan: '昭明', commanderyHan: '樂浪郡', latitude: 38.40806, longitude: 125.463 },
  { id: 'hhs:113:樂浪郡:015', nameKo: '누방', nameHan: '鏤方', commanderyHan: '樂浪郡', latitude: 38.99804, longitude: 126.18098 },
  { id: 'hhs:113:樂浪郡:016', nameKo: '제해', nameHan: '提奚', commanderyHan: '樂浪郡', latitude: 38.42327, longitude: 126.22751 },
  { id: 'hhs:113:樂浪郡:017', nameKo: '혼미', nameHan: '渾彌', commanderyHan: '樂浪郡', latitude: 39.6138, longitude: 125.965 },
  { id: 'hhs:113:遼東屬國:002', nameKo: '빈도', nameHan: '賓徒', commanderyHan: '遼東屬國', latitude: 41.06742, longitude: 120.85823 },
  { id: 'hhs:113:遼東屬國:003', nameKo: '도하', nameHan: '徒河', commanderyHan: '遼東屬國', latitude: 41.01857, longitude: 121.23808 },
  { id: 'hhs:113:遼東屬國:005', nameKo: '험독', nameHan: '險瀆', commanderyHan: '遼東屬國', latitude: 41.3317, longitude: 122.51595 },
  { id: 'hhs:113:遼東屬國:006', nameKo: '방', nameHan: '房', commanderyHan: '遼東屬國', latitude: 41.02235, longitude: 121.98654 },
  { id: 'hhs:113:交趾郡:002', nameKo: '이루', nameHan: '羸𨻻', commanderyHan: '交趾郡', latitude: 21.08389, longitude: 105.80848 },
  { id: 'hhs:113:交趾郡:003', nameKo: '안정', nameHan: '安定', commanderyHan: '交趾郡', latitude: 20.65468, longitude: 106.05784 },
  { id: 'hhs:113:交趾郡:004', nameKo: '구루', nameHan: '苟漏', commanderyHan: '交趾郡', latitude: 21.05049, longitude: 105.57406 },
  { id: 'hhs:113:交趾郡:005', nameKo: '미령', nameHan: '麋泠', commanderyHan: '交趾郡', latitude: 21.17953, longitude: 105.63381 },
  { id: 'hhs:113:交趾郡:006', nameKo: '곡양', nameHan: '曲陽', commanderyHan: '交趾郡', latitude: 20.93734, longitude: 106.31455 },
  { id: 'hhs:113:交趾郡:007', nameKo: '북대', nameHan: '北帶', commanderyHan: '交趾郡', latitude: 20.98546, longitude: 106.04637 },
  { id: 'hhs:113:交趾郡:008', nameKo: '계서', nameHan: '稽徐', commanderyHan: '交趾郡', latitude: 20.88306, longitude: 106.14412 },
  { id: 'hhs:113:交趾郡:009', nameKo: '서어', nameHan: '西于', commanderyHan: '交趾郡', latitude: 21.11594, longitude: 105.75689 },
  { id: 'hhs:113:交趾郡:010', nameKo: '주연', nameHan: '朱䳒', commanderyHan: '交趾郡', latitude: 20.87271, longitude: 105.89655 },
  { id: 'hhs:113:交趾郡:011', nameKo: '봉계', nameHan: '封谿', commanderyHan: '交趾郡', latitude: 21.211374, longitude: 105.71751 },
  { id: 'hhs:113:交趾郡:012', nameKo: '망해', nameHan: '望海', commanderyHan: '交趾郡', latitude: 21.24807, longitude: 105.94722 },
  { id: 'hhs:113:九真郡:002', nameKo: '거풍', nameHan: '居風', commanderyHan: '九真郡', latitude: 19.95441, longitude: 105.74391 },
  { id: 'hhs:113:九真郡:003', nameKo: '함환', nameHan: '咸懽', commanderyHan: '九真郡', latitude: 19.00923, longitude: 105.55476 },
  { id: 'hhs:113:九真郡:004', nameKo: '무공', nameHan: '無功', commanderyHan: '九真郡', latitude: 20.2783, longitude: 105.90536 },
  { id: 'hhs:113:九真郡:005', nameKo: '무편', nameHan: '無編', commanderyHan: '九真郡', latitude: 19.53305, longitude: 105.71027 },
  { id: 'hhs:113:日南郡:002', nameKo: '주오', nameHan: '朱吾', commanderyHan: '日南郡', latitude: 17.22405, longitude: 106.78626 },
  { id: 'hhs:113:日南郡:003', nameKo: '노용', nameHan: '盧容', commanderyHan: '日南郡', latitude: 16.52715, longitude: 107.571318 },
  { id: 'hhs:113:日南郡:004', nameKo: '상림', nameHan: '象林', commanderyHan: '日南郡', latitude: 16.44045, longitude: 107.71482 },
  { id: 'hhs:113:日南郡:005', nameKo: '비경', nameHan: '比景', commanderyHan: '日南郡', latitude: 17.7049, longitude: 106.408198 },
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
