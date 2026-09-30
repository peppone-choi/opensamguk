/** 주인 없는 땅 · 城 의 이름 — 승인된 v3.1 범례 · 선택 카드 표기(「공백지」 · 「미소유」를 이것 하나로). */
export const UNOWNED_NATION_NAME = '무주';

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
