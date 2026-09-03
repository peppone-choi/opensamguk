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
  hierarchyPath,
  displayedOwnerName,
  ownershipMismatch = false,
  provinceOccupantNationName,
  jurisdictionOwnerNationName,
  commanderyControllerNationName,
}: CompactMapTooltipMetaInput): string | undefined {
  const conflictingOwners = [
    provinceOccupantNationName && `공간: ${provinceOccupantNationName}`,
    jurisdictionOwnerNationName && `현: ${jurisdictionOwnerNationName}`,
    commanderyControllerNationName && `군국: ${commanderyControllerNationName}`,
  ].filter((part): part is string => Boolean(part));
  const ownerSummary = ownershipMismatch && conflictingOwners.length > 0
    ? conflictingOwners.join(' / ')
    : displayedOwnerName;
  const parts = [hierarchyPath?.trim(), ownerSummary?.trim()].filter(
    (part): part is string => Boolean(part),
  );
  return parts.length > 0 ? parts.join(' · ') : undefined;
}
