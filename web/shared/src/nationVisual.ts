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
