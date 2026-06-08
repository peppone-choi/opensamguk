// legacy hwe/ts/utilGame/formatCityName.ts 충실 포팅 — 도시 id → 이름.
// legacy GameConstStore 의존을 web/game 자립 구조형(필요 필드만)으로 대체.

export interface CityConstItem {
  id: number;
  name: string;
}

export interface WithCityID {
  city: number;
}

/**
 * 도시 id 또는 {city: id} 객체를 받아 도시 이름을 반환.
 * cityConst 배열은 BE GetConstResponse.cityConst(List<CityConstItem>)를 그대로 전달.
 * city 미존재 시 legacy는 throw하지만, FE에서는 graceful하게 빈 문자열 반환.
 */
export function formatCityName(
  target: number | WithCityID,
  cityConst: readonly CityConstItem[],
): string {
  const cityID = typeof target === 'number' ? target : target.city;
  const city = cityConst.find((c) => c.id === cityID);
  if (city === undefined) {
    return '';
  }
  return city.name;
}
