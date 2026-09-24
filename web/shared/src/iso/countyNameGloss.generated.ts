// 생성물 — tools/map/build_county_display_name_collisions.py 가 쓴다. 손으로 고치지 마라(--check 가 적색이 된다).
// 원본 목록: data/curated/han/county-display-name-collisions-v1.json (이슈 #838).
// han-tiles sha256 99b7ba370e7c60b1f7087972cb1e050bc60524fd988333b72508c7bbbd8bac2f

/** 같은 郡 안에서 한글 표시명이 겹치는 관할(jurisdictionId) → 漢字 병기(繁體, 县/縣 꼬리 없음). */
export const COUNTY_GLOSS_BY_JURISDICTION_ID: Readonly<Record<string, string>> = {
  "41955": "泠道",
  "41996": "營道",
  "43379": "安風",
  "82575": "安豐",
  "82841": "新成",
  "82893": "陽城",
  "83031": "襄城",
  "83168": "愼陽",
  "87638": "下雒",
  "gc-g0000-018": "新城",
  "gc-g0008-002": "新陽",
  "gc-g0090-008": "下落",
};

/** 같은 목록의 簡體 어간 → 繁體 병기. 서버 표시명 꼬리 「(阳城)」를 병기로 바꿀 때 쓴다. */
export const COUNTY_GLOSS_BY_SIMPLIFIED_STEM: Readonly<Record<string, string>> = {
  "下洛": "下雒",
  "下落": "下落",
  "安丰": "安豐",
  "安风": "安風",
  "慎阳": "愼陽",
  "新城": "新城",
  "新成": "新成",
  "新陽": "新陽",
  "泠道": "泠道",
  "营道": "營道",
  "襄城": "襄城",
  "阳城": "陽城",
};
