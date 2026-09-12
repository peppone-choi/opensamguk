/**
 * 郡國 밖 세력을 **무슨 그림으로 세울 것인가**.
 *
 * ## 무엇이 어긋나 있었나
 *
 * 지형 데이터는 `EXTERNAL_PLACE` 37 곳을 전부 `level 4` 로 둔다. 그건 등급 판정이 아니라
 * **자리표시**다 — 생성기가 그렇게 적어 두었다(`tools/map/build_external_places.py`:
 * 「도로 간선의 허브 … level 이 아니라 여기서만 표시한다」). 그런데 나중에 아이콘 표가
 * 등급 1:1 11 단이 되면서 그 4 가 곧 **이민족 야영 그림**이 됐다.
 *
 * 그리고 현행 세계 `han-world-v3` 에는 郡國 밖 세력이 게임 城 으로 **하나도 없다**(실측:
 * 프로덕션 `/api/server-map/pep` 의 城 781 곳이 전부 漢 郡縣 — 지금은 835, 여전히 전부 郡縣). 그래서 37 곳이 예외 없이
 * 자리표시 4 로 떨어져, 백제국·부여·사로국·야마일국·국내성까지 전부 같은 텐트로 섰다 —
 * 「중국 외를 이민족 아이콘으로 퉁치는건 좀 아닌 것 같아」(2026-09-10).
 *
 * ## 무엇을 근거로 가르는가
 *
 * 지어내지 않는다. 저장소가 이 질문에 답하려고 이미 들고 있는 두 축을 쓴다.
 *
 * **축 1 — `administrativeSystem`.** `han-tiles.json` 의 `provinceRecords[]` 가 郡國 밖
 * 세력마다 정체를 적어 둔다(BAEKJE · GOGURYEO · XIONGNU · WA …). 37 곳 전부 이 표에
 * 닿는다(실측: cityIndex 로 가리키는 record 가 정확히 37).
 *
 * **축 2 — 세계 생성기의 등급.** `tools/scenario/build_han_world.py` 는 동이 세력을
 * 三國志 魏書 東夷傳의 戶數로 재서 다른 郡과 같은 사다리에 올리고, 유목·산지 세력만
 * '이'(4)로 둔다.
 *
 * **두 축이 정확히 겹친다.** 세계가 '이'로 매긴 7 곳(西羌·白馬氐·哀牢·山越·烏桓·鮮卑·
 * 南匈奴)이 아래 `TRIBAL_SYSTEMS` 7 계열과 1:1 이고, 나머지는 전부 '소' 이상이다. 서로
 * 다른 곳에서 만들어진 두 분류가 같은 답을 냈으므로 이 갈래는 우연이 아니다
 * (`__tests__/externalPlaceTier.test.ts` 가 han.json 과 대조해 어긋나면 빨개진다).
 *
 * ## 남는 흐린 자리 — 지어내지 않고 그대로 둔다
 *
 *  - 挹婁(YILOU): 세계가 등급을 안 매겼고 '이' 일곱에도 없다. 기본값(城)으로 둔다.
 *  - 왜 소국 5 곳(一大國·伊都國·奴國·末盧國·對馬國): 저장소에 戶數 근거가 없다. 다만
 *    이름 그대로 國 이라 城 으로 세운다. 末盧國만 basis 에 「有四千餘戶」가 있지만
 *    나머지를 맞출 근거가 없어 사다리에 대지 않는다.
 */

/** 세계 생성기가 '이'(4)로 매긴 유목·산지 세력. 이 7 계열만 야영으로 선다. */
const TRIBAL_SYSTEMS: ReadonlySet<string> = new Set([
  'XIONGNU',  // 南匈奴
  'XIANBEI',  // 鮮卑
  'WUHUAN',   // 烏桓
  'QIANG',    // 西羌
  'DI',       // 白馬氐
  'SHANYUE',  // 山越
  'AILAO',    // 哀牢 — 南蠻
]);

/**
 * 東夷傳 戶數가 다른 郡의 '중'(6) 자리까지 올려 놓은 두 곳.
 * 夫餘 「戶八萬」 · 邪馬壹國 「可七萬餘戶」. 세계 생성기가 실제로 그렇게 매긴다.
 * 같은 WA 계열이라도 邪馬壹國과 奴國은 크기가 다르므로 계열이 아니라 이름으로 가른다.
 */
const MID_TIER_NAMES: ReadonlySet<string> = new Set(['夫餘', '邪馬壹國']);

/** 郡國 밖이지만 유목이 아닌 곳의 기본 그림 — 城(郡治급). */
const SETTLED_LEVEL = 5;
const MID_LEVEL = 6;
const TRIBAL_LEVEL = 4;

export interface ExternalPlaceInput {
  /** `han-tiles.json` cities[].nameCh — 「伯濟國」·「南匈奴」. */
  nameCh?: string;
  /** 그 城 을 가리키는 provinceRecord 의 `administrativeSystem`. 없으면 기본값으로 간다. */
  administrativeSystem?: string;
}

/**
 * 郡國 밖 세력이 설 등급(=아이콘 단). 지형이 실어 보낸 자리표시 4 를 대신한다.
 *
 * **게임 城 이 있으면 여기 오지 않는다** — 그때는 세계가 매긴 등급이 이미 있고
 * `placeGameCities` 가 그것을 쓴다. 이 함수는 게임 城 이 없는 곳에만 붙는다.
 */
export function externalPlaceLevel(place: ExternalPlaceInput): number {
  const system = place.administrativeSystem;
  if (system && TRIBAL_SYSTEMS.has(system)) return TRIBAL_LEVEL;
  if (place.nameCh && MID_TIER_NAMES.has(place.nameCh)) return MID_LEVEL;
  return SETTLED_LEVEL;
}
