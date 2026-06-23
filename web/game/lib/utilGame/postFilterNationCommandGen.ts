// legacy hwe/ts/utilGame/postFilterNationCommandGen.ts 충실 포팅 — che_발령 brief 후처리.
// 부대 발령 턴 객체의 brief를 《부대명》【도시명】로 발령 형태로 변환.
// legacy GameConstStore/JosaUtil 의존을 web/game 자립 구조형으로 대체.

import type { CityConstItem } from './formatCityName';

/**
 * JosaUtil.pick(name, "로") 충실 재현 — legacy hwe/ts/util/JosaUtil.ts `checkCode(code, isRo=true)`:
 *   jongsung = (code - 0xAC00) % 28; 받침 없음(0) **또는 ㄹ받침(8)** → "로", 그 외 받침 → "으로".
 * (ㄹ받침 예외가 핵심 — 단순 `%28 !== 0` 근사는 "산월" 같은 ㄹ받침 도시에서 "으로"로 오역됨.)
 * 도메인: scenario_1010 도시명은 전부 한글 음절(Hanja/ASCII 종성 없음) → checkCode 한글 경로로 충분.
 */
function josaRo(cityName: string): string {
  const code = cityName.charCodeAt(cityName.length - 1);
  const jongsung = (code - 0xac00) % 28;
  return jongsung === 0 || jongsung === 8 ? '로' : '으로';
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
