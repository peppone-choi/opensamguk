// legacy hwe/ts/utilGame/formatDefenceTrain.ts 충실 포팅 — 수비 훈련도 → 기호(임계 버킷).
import { binarySearch } from './_helpers';

const defenceMap: [number, string][] = [
  [0, '△'],
  [60, '○'],
  [80, '◎'],
  [90, '☆'],
  [999, '×'],
];

export function formatDefenceTrain(defenceTrain: number): string {
  const idx = binarySearch(defenceMap, defenceTrain, ([defenceKey], needle) => defenceKey - needle);
  if (idx >= 0) {
    return defenceMap[idx][1] ?? '?';
  }
  const uidx = (~idx) - 1;
  return defenceMap[uidx][1] ?? '?';
}
