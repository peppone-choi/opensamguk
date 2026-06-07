// legacy hwe/ts/utilGame/calcInjury.ts 충실 포팅 — 부상 반영 실효 스탯 = round(baseStat * (100-injury)/100).
// legacy GeneralListItemP0 의존을 web/game 자립 구조형(필요 필드만)으로 대체.
export interface InjuryGeneral {
  leadership: number;
  strength: number;
  intel: number;
  injury: number;
}

export function calcInjury(statKey: 'leadership' | 'strength' | 'intel', general: InjuryGeneral): number {
  const baseStat = general[statKey];
  return Math.round(baseStat * (100 - general.injury) / 100);
}
