/** 주인 없는 땅 · 城 의 이름 — 승인된 v3.1 범례 · 선택 카드 표기(「공백지」 · 「미소유」를 이것 하나로). */
export const UNOWNED_NATION_NAME = '무주';

/** 국가색이 아닐 때(없음 · #rrggbb 가 아님) 쓰는 색 — --muted 와 같은 값(지도 「내 위치」 표지의 세력 없음 색과 같다). */
export const NO_NATION_COLOR = '#8e8879';

/**
 * style · SVG fill 에 넣는 국가색 — `#rrggbb` 만 그대로, 그 밖은 `fallback`. 서버에서 온 색 문자열을 검사 없이 화면 속성에 넣지 않는다
 * (K3 2026-10-04, 원장 D90). 지도 그림은 isOwnedNationVisual 이 같은 형식을 이미 요구한다.
 */
export function safeNationColor(color: unknown, fallback: string = NO_NATION_COLOR): string {
  return typeof color === 'string' && /^#[0-9a-fA-F]{6}$/.test(color) ? color : fallback;
}

export function isOwnedNationVisual(
  nationId: unknown,
  nationColor: unknown,
): nationColor is string {
  return typeof nationId === 'number'
    && Number.isInteger(nationId)
    && nationId > 0
    && typeof nationColor === 'string'
    && /^#[0-9a-fA-F]{6}$/.test(nationColor);
}

export function parseNationColor(color: string): [number, number, number] {
  return [
    Number.parseInt(color.slice(1, 3), 16),
    Number.parseInt(color.slice(3, 5), 16),
    Number.parseInt(color.slice(5, 7), 16),
  ];
}

export interface CompactMapTooltipMetaInput {
  hierarchyPath?: string;
  displayedOwnerName?: string;
  ownershipMismatch?: boolean;
  provinceOccupantNationName?: string;
  jurisdictionOwnerNationName?: string;
  commanderyControllerNationName?: string;
}

export function formatCompactMapTooltipMeta({
  displayedOwnerName,
}: CompactMapTooltipMetaInput): string | undefined {
  return displayedOwnerName?.trim() || undefined;
}
