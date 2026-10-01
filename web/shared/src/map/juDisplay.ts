// 13주 화면 이름(원장 D25, 사용자 결정 2026-10-01). 데이터 키(`JU_NAMES` 「사예」 · 「량주」)는 서버 ju 색인
// `juByParent`와 대조하는 값이라 그대로 두고, 화면에 보일 때만 이 표로 바꾼다.
// 涼州 「량주」는 揚州 「양주」와 소리가 겹쳐(보드 「양주(涼)」 · 「양주(揚)」) 「서량」으로, 司隸는 「사례」로 적는다.

const JU_DISPLAY: Readonly<Record<string, string>> = {
  사예: '사례',
  량주: '서량',
};

/** 州 데이터 키 → 정사 한자. 한자 병기 자리(places 규칙)에서 「서량(涼州)」처럼 붙인다. */
const JU_HANJA: Readonly<Record<string, string>> = {
  사예: '司隸', 예주: '豫州', 기주: '冀州', 연주: '兗州', 서주: '徐州', 청주: '青州', 형주: '荊州',
  양주: '揚州', 익주: '益州', 량주: '涼州', 병주: '并州', 유주: '幽州', 교주: '交州',
};

/** 州 데이터 키(「량주」)를 화면 이름(「서량」)으로. 표에 없는 키는 그대로 돌려준다. */
export function juDisplayName(key: string): string {
  return JU_DISPLAY[key] ?? key;
}

/** 州 데이터 키의 정사 한자(「량주」 → 「涼州」). 모르는 키는 null. */
export function juHanja(key: string): string | null {
  return JU_HANJA[key] ?? null;
}
