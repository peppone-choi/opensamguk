// legacy hwe/ts/utilGame/postFilterNationCommandGen.ts 충실 포팅 — che_발령 brief 후처리.
// 부대 발령 턴 객체의 brief를 《부대명》【도시명】로 발령 형태로 변환.
// legacy GameConstStore/JosaUtil 의존을 web/game 자립 구조형으로 대체.

import type { CityConstItem } from './formatCityName';

/** JosaUtil.pick("로") 충실 재현 — 받침 유무에 따른 조사 선택. */
function josaRo(cityName: string): string {
  // 받침 있으면 "으로", 없으면 "로"
  const lastChar = cityName.charCodeAt(cityName.length - 1);
  const hasJong = (lastChar - 0xac00) % 28 !== 0;
  return hasJong ? '으로' : '로';
}

export interface TurnObj {
  action: string;
  brief: string;
  arg: {
    destGeneralID?: number;
    destCityID?: number;
    [key: string]: unknown;
  };
  tooltip?: string;
}

/**
 * 부대 발령(che_발령) 턴 객체의 brief를 후처리.
 * troopList: {부대장ID: 부대명} 맵.
 * cityConst: BE GetConstResponse.cityConst 배열.
 *
 * legacy 동작:
 *   - action != 'che_발령' → 그대로 반환
 *   - destGeneralID가 troopList에 없음 → 그대로 반환
 *   - 있으면 brief = 《부대명》【도시명】${조사} 발령
 *   - tooltip = 《부대명》${원본brief}
 */
export function postFilterNationCommandGen<T extends TurnObj>(
  troopList: Record<number, string>,
  cityConst: readonly CityConstItem[],
): (turnObj: T) => T {
  return function (turnObj: T): T {
    if (turnObj.action !== 'che_발령') {
      return turnObj;
    }

    const destGeneralID = turnObj.arg.destGeneralID;
    if (destGeneralID === undefined || !(destGeneralID in troopList)) {
      return turnObj;
    }

    const troopName = troopList[destGeneralID];
    const destCityID = turnObj.arg.destCityID;
    if (destCityID === undefined) {
      return turnObj;
    }

    const city = cityConst.find((c) => c.id === destCityID);
    if (city === undefined) {
      return turnObj;
    }

    const destCityName = city.name;
    const ro = josaRo(destCityName);
    const brief = `《${troopName}》【${destCityName}】${ro} 발령`;
    const tooltip = `《${troopName}》${turnObj.brief}`;

    return {
      ...turnObj,
      brief,
      tooltip,
    };
  };
}
