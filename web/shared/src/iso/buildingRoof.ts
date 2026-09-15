// 城 지붕 높이 — 깃대를 건물에 꽂을 자리.
//
// 예전에는 등급과 상관없이 타일 중심에서 세계 96px 위에 깃대를 세웠다. 도성(지붕 140px)에는
// 깃발이 성벽 속에 묻히고, 장현(지붕 44px)에는 지붕 위로 52px 떠서 확대하면 화면에서
// 100px 넘게 떨어진 깃발이 됐다 — 「성과 깃발의 위치를 좀 가깝게」(2026-09-15).

/**
 * iso2d 오브젝트 스프라이트(256×256)에서 **깃대가 서는 가운데 열**(x 120..136)의 알파(>40)
 * 윗변 y. 스프라이트를 다시 뽑으면 buildingRoof.test.ts 가 PNG 를 직접 읽어 빨개진다.
 */
export const SPRITE_ROOF_TOP_PX: Readonly<Record<string, number>> = {
  water: 134,
  garrison: 122,
  pass: 98,
  tribal: 132,
  commandery: 114,
  'commandery-mid': 82,
  'commandery-major': 74,
  'commandery-grand': 66,
  capital: 36,
  county: 120,
  'county-small': 132,
};

/** 스프라이트 밑면 다이아몬드의 중심 y. 이 줄이 타일 중심에 온다(IsoMap2D OBJECT_* 주석). */
export const SPRITE_GROUND_CENTER_Y = 176;

/**
 * 지붕 꼭대기가 城 칸 중심에서 몇 **화면 px** 위에 있는가.
 * objectScale 은 스프라이트에 곱해 그린 배율(OBJECT_SCALE × drawScale), viewScale 은 지도 배율이다.
 */
export function spriteRoofLift(file: string, objectScale: number, viewScale: number): number | null {
  const top = SPRITE_ROOF_TOP_PX[file];
  if (top === undefined) return null;
  return (SPRITE_GROUND_CENTER_Y - top) * objectScale * viewScale;
}

/**
 * 깃대 밑동의 화면 y. 지붕에 조금 박아 세운다 — 딱 붙이면 1px 틈이 떠 보인다.
 * 지붕을 모르면(그림이 없는 城) 예전 규칙으로 돌아간다.
 */
export function cityFlagBase(groundY: number, roofLift: number | null, viewScale: number): number {
  if (roofLift === null) return groundY - Math.max(11, 96 * viewScale);
  const planted = roofLift - Math.min(4, roofLift * 0.15);
  return groundY - Math.max(3, planted);
}
