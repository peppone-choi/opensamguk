// legacy hwe/ts/utilGame/nextExpLevelRemain.ts 충실 포팅 — [현재경험치-기준, 다음레벨까지 폭] 반환.
export function nextExpLevelRemain(exp: number, expLevel: number): [number, number] {
  if (exp < 1000) {
    return [exp - expLevel * 100, 100];
  }

  const expBase = 10 * expLevel ** 2;
  const expNext = 10 * (expLevel + 1) ** 2;
  return [exp - expBase, expNext - expBase];
}
