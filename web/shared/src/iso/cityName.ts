// 城 이름을 화면에 어떻게 적을 것인가.
//
// 「지도나 도시 출력은 중국의 군현제 안의 경우 뭐뭐현으로 통일해」(2026-09-10).
//
// 게임 도시 표에 적힌 이름은 줄기만 있다 — "장안", "상채". 그런데 이 城 들이 실제로 무엇이냐면
// 《續漢書·郡國志》에 실린 **縣** 이다. 생성기 주석이 그렇게 말한다(han.json _meta.note:
// "城=郡治 + 續漢書 郡國志에 실린 縣"). 그래서 화면에는 縣 을 붙여 "장안현", "상채현" 으로 적는다.
// 郡縣制 **밖**(이민족 거점·동이·郡國 밖 세력)은 縣 이 아니므로 손대지 않는다.
//
// 판정 근거는 지어내지 않고 두 가지 실측 값만 쓴다.
//
//   1) meta.nameCh — 그 城 자신의 행정 단위가 붙은 원 표기다("长安县", "甘陵郡", "伯濟國").
//      끝 글자가 县/縣 이면 그 城 은 縣 이다. han.json 774 중 696 이 여기 걸린다.
//      ※ meta.seat 은 쓰면 안 된다 — 그건 제 이름이 아니라 **소속 郡의 治所** 이름이다
//        (상락.meta.seat = "장안현"). 이름 짓는 데 쓰면 縣 이름이 통째로 뒤바뀐다.
//   2) level 10·11 — 생성기가 「영현」·「장현」으로 이름 붙인 등급이다(百官志 令/長 경계).
//      등급 이름 자체가 縣 이라, nameCh 가 侯國(原鹿侯国)이나 道(汶江道)로 적힌 17 곳도
//      여기서 縣 으로 잡힌다. 列侯所食縣曰國 · 有蠻夷曰道 — 둘 다 縣 한 급이다.
//
// 이 둘로 774 중 713 이 縣 이고, 남는 61 은 縣 기록이 없는 郡(감릉군)·屬國·이민족·동이다.
// 그쪽은 원 이름 그대로 나간다 — "감릉현" 같은 縣 을 새로 만들지 않는다.

/** cityDisplayName 이 보는 만큼. MapPreviewCity·PlacedCity 둘 다 이 모양을 만족한다. */
export interface CityNameInput {
  /** 게임 城 번호. **음수면 郡國 밖 세력**이라 縣 이 아니다. */
  id: number;
  name: string;
  level: number;
  /** han.json meta.nameCh. 서버가 안 실어 보내면 undefined — 그러면 level 만으로 판정한다. */
  nameCh?: string;
}

/** 「영현」·「장현」. 등급 이름이 곧 縣 이다. */
const COUNTY_LEVELS: ReadonlySet<number> = new Set([10, 11]);

/**
 * nameCh 꼬리로 縣 임이 드러나는 행정 단위. 簡體·繁體 둘 다 온다.
 *
 * 侯國은 縣 한 급이다 — 續漢書 百官志 「列侯所食縣曰國」. 실측(han-world-v3): 侯國 14 곳 중
 * 3 곳(낙평·곡양·안중)이 등급 5·6 을 달고 있어 등급 규칙으로는 안 잡힌다. 屬國은 縣 이
 * 아니라 郡 한 급이므로(龜茲屬國·遼東屬國) 여기 넣지 않는다 — 「국」으로 뭉뚱그리면
 * 屬國까지 縣 이 된다.
 */
const COUNTY_UNITS = ['县', '縣', '侯国', '侯國'] as const;

/** 이 城 이 중국 郡縣制 안의 縣 인가. */
export function isHanCounty(city: CityNameInput): boolean {
  // 郡國 밖 세력(EXTERNAL_PLACE)은 음수 번호로 온다. 縣 이 아니다.
  if (city.id < 0) return false;
  const nameCh = city.nameCh ?? '';
  if (COUNTY_UNITS.some((unit) => nameCh.endsWith(unit))) return true;
  return COUNTY_LEVELS.has(city.level);
}

/**
 * 게임 이름 뒤에 붙는 한정자 — 「의씨(河東郡)」·「영릉#123」.
 *
 * 같은 한글 독음이 겹치면 생성기가 소속 郡이나 번호를 뒤에 달아 유일하게 만든다
 * (build_han_world.py build_v3 의 name_counts/qualified_counts). 실측(han-world-v3):
 * 781 중 131 곳이 한정돼 있다. 그래서 縣 을 그냥 뒤에 붙이면 「의씨(河東郡)현」이 되어
 * 한정자가 이름 한가운데 낀 꼴이 된다. 줄기에만 붙이고 한정자는 뒤에 그대로 둔다.
 */
const QUALIFIER = /(\([^()]*\)|#\d+)$/;

/** 화면에 적을 이름. 縣 이면 「뭐뭐현」, 아니면 원 이름 그대로. */
export function cityDisplayName(city: CityNameInput): string {
  if (!isHanCounty(city)) return city.name;
  const qualifier = QUALIFIER.exec(city.name)?.[1] ?? '';
  const stem = qualifier ? city.name.slice(0, -qualifier.length) : city.name;
  if (stem.endsWith('현')) return city.name;
  return `${stem}현${qualifier}`;
}
