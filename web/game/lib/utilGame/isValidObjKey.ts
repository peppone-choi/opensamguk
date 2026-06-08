// legacy hwe/ts/utilGame/isValidObjKey.ts 충실 포팅 — 'None'/undefined/null 키 무효 판정.
export function isValidObjKey<T>(key: T | 'None' | undefined | null): boolean {
  if (key === 'None' || key === undefined || key === null) {
    return false;
  }
  return true;
}
