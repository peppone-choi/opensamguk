// 제 경위도에서 크게 밀려 앉은 城 을 제 영역 안으로 되돌린다.
//
// 지형 응답 cities[].col/row 는 CHGIS 실측 경위도를 투영한 칸이 **아니다**. 그 城 의
// 영역 래스터에 맞춰 옮겨 심은 씨앗이다(tools/map/rebalance_han_tiles.py
// adapt_historical_city_seeds — 領域의 연결 성분을 골라 그 안 가장 가까운 칸으로 옮긴다).
// 대개는 한두 칸이지만, 영역이 두 조각으로 갈린 곳에서는 씨앗이 큰 조각에 붙어
// 아주 멀리 간다.
//
// 于山國(울릉도)이 그 경우다. 울릉도와 오키 제도(隱岐)에 각각 뭍이 잡혀 영역이 9칸·14칸
// 두 조각으로 갈렸고, 씨앗이 큰 쪽(오키)에 붙었다 — 「울릉도에 위치해야 하는데, 일본에
// 더 가까워」(2026-09-10). 파일 안에 답이 이미 있다: 于山國 郡 행은 (714,175) 로 울릉도를
// 가리키고 있고, 그 칸도 于山國 영역이다.
//
// 그래서 여기서 하는 일은 **지어내기가 아니다**. 옮길 칸은 전부 han-tiles.json 이 그 城 의
// 것이라고 이미 적어 둔 칸 중 하나다. 규칙은 하나뿐이다 —
//
//   그 城 을 가리키는 provinceRecords 의 owner 칸 중, 제 (lat,lon) 투영점에 가장 가까운 칸.
//   지금 칸이 투영점에서 10 칸 이상 떨어져 있고, 옮길 칸이 2 칸 이하일 때만 옮긴다.
//
// 실측(han-tiles.json 5feffb4a…, 2026-09-10): 城 1,138 곳 중 이 규칙에 걸리는 곳은 4 이고
// 평균 20.5 칸 → 1.2 칸으로 줄어든다. 표는 data/curated/han/city-seed-reseats-v1.json 이
// 정본이고, citySeedReseat.test.ts 가 그 규칙을 지형 파일에서 다시 계산해 대조한다.
//
// 영역·소유 래스터는 건드리지 않는다. 화면에 城 을 세우는 자리만 바뀐다.

/** 되돌릴 한 곳. 좌표는 **원본 셀**이다(타일이 아니다). */
export interface CitySeedReseat {
  /** 지형 응답 cities[] 인덱스. */
  cityIndex: number;
  /** 그 행의 id. 인덱스가 밀렸는지 확인하는 두 번째 축이다. */
  placeId: string;
  fromCol: number;
  fromRow: number;
  toCol: number;
  toRow: number;
}

export const CITY_SEED_RESEATS: readonly CitySeedReseat[] = [
  { cityIndex: 101, placeId: 'X035', fromCol: 756, fromRow: 201, toCol: 714, toRow: 175 },
  { cityIndex: 1026, placeId: '40078', fromCol: 526, fromRow: 335, toCol: 516, toRow: 328 },
  { cityIndex: 353, placeId: '70524', fromCol: 266, fromRow: 154, toCol: 262, toRow: 164 },
  { cityIndex: 498, placeId: '42147', fromCol: 451, fromRow: 461, toCol: 445, toRow: 468 },
];

/** 되돌릴 대상 최소 모양. 지형 응답 cities[] 항목이 이보다 넓다. */
export interface ReseatableCity {
  id: string;
  col: number;
  row: number;
}

/**
 * 표대로 자리를 되돌린다. **제자리에** 고친다 — 부르는 쪽이 방금 파싱한 응답이라
 * 사본을 뜰 이유가 없다.
 *
 * 지형 파일이 바뀌어 id 나 현재 칸이 표와 어긋나면 그 줄은 **건너뛴다**. 이미 고쳐진
 * 파일을 또 옮기거나, 엉뚱한 城 을 옮기는 쪽이 훨씬 나쁘다.
 *
 * @returns 실제로 옮긴 곳 수.
 */
export function applyCitySeedReseats(cities: ReseatableCity[]): number {
  let moved = 0;
  for (const reseat of CITY_SEED_RESEATS) {
    const city = cities[reseat.cityIndex];
    if (!city || city.id !== reseat.placeId) continue;
    if (city.col !== reseat.fromCol || city.row !== reseat.fromRow) continue;
    city.col = reseat.toCol;
    city.row = reseat.toRow;
    moved += 1;
  }
  return moved;
}
