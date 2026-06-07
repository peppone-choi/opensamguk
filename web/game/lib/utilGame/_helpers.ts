// utilGame/_helpers — legacy 의존 npm(binary-search, lodash clamp)을 web/game 자립 구현으로 대체.
// 동작은 원본 패키지와 동일(faithful) — 포매터 버킷 조회/클램프 결과가 바뀌면 표시 패러티가 깨진다.

// lodash clamp 충실 재현: value 를 [lower, upper] 로 클램프.
export function clamp(value: number, lower: number, upper: number): number {
  return Math.min(Math.max(value, lower), upper);
}

// npm `binary-search` 충실 재현: 정확히 일치하면 인덱스(>=0), 아니면 ~삽입점(= -(삽입점)-1) 반환.
// 호출부는 미스 시 (~idx)-1 로 needle 이하 최대 키 버킷을 얻는다(legacy 패턴).
export function binarySearch<T>(
  arr: readonly T[],
  needle: number,
  cmp: (el: T, needle: number) => number,
): number {
  let lo = 0;
  let hi = arr.length - 1;
  while (lo <= hi) {
    const mid = (lo + hi) >>> 1;
    const c = cmp(arr[mid], needle);
    if (c < 0) lo = mid + 1;
    else if (c > 0) hi = mid - 1;
    else return mid;
  }
  return ~lo;
}
