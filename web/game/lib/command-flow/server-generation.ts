// The one boundary from front-info's numeric server generation to the cancellation journal's canonical string.
// Only a safe finite integer >= 0 is a generation; anything else (absent, null, fraction, negative, NaN, Infinity,
// unsafe, non-number) is null. Never inferred from serverCnt, worldId, scenario, year or a cookie.
export function normalizeServerGeneration(value: unknown): string | null {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 0) return null;
  return String(value);
}
