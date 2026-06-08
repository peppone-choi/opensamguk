// legacy hwe/ts/utilGame/formatGeneralTypeCall.ts 충실 포팅 — 통/무/지 비율 기반 장수 타입 호칭.
// legacy GameConstType 의존을 필요 필드만 가진 구조형으로 대체.
export interface GeneralTypeConst {
  chiefStatMin: number;
  statGradeLevel: number;
}

export function formatGeneralTypeCall(
  leadership: number,
  strength: number,
  intel: number,
  gameConst: GeneralTypeConst,
): string {
  if (leadership < 40) {
    if (strength + intel < 40) {
      return '아둔';
    }
    if (intel >= gameConst.chiefStatMin && strength < intel * 0.8) {
      return '학자';
    }
    if (strength >= gameConst.chiefStatMin && intel < strength * 0.8) {
      return '장사';
    }
    return '명사';
  }

  const maxStat = Math.max(leadership, strength, intel);
  const sum2Stat = Math.min(leadership + strength, strength + intel, intel + leadership);
  if (maxStat >= gameConst.chiefStatMin + gameConst.statGradeLevel && sum2Stat >= maxStat * 1.7) {
    return '만능';
  }
  if (strength >= gameConst.chiefStatMin - gameConst.statGradeLevel && intel < strength * 0.8) {
    return '용장';
  }
  if (intel >= gameConst.chiefStatMin - gameConst.statGradeLevel && strength < intel * 0.8) {
    return '명장';
  }
  if (leadership >= gameConst.chiefStatMin - gameConst.statGradeLevel && strength + intel < leadership) {
    return '차장';
  }
  return '평범';
}
