// legacy hwe/ts/utilGame/formatRefreshScore.ts 충실 포팅 — 리프레시 점수 → 텍스트(임계 버킷).
import { binarySearch } from './_helpers';

const refreshScoreMap: [number, string][] = [
  [0, '안함'],
  [50, '무관심'],
  [100, '보통'],
  [200, '가끔'],
  [400, '자주'],
  [800, '열심'],
  [1600, '중독'],
  [3200, '폐인'],
  [6400, '경고'],
  [12800, '헐...'],
];

export function formatRefreshScore(refreshScore: number | null): string {
  if (!refreshScore) refreshScore = 0;
  const idx = binarySearch(refreshScoreMap, refreshScore, ([key], needle) => key - needle);
  if (idx >= 0) {
    return refreshScoreMap[idx][1] ?? '?';
  }
  const uidx = (~idx) - 1;
  return refreshScoreMap[uidx][1];
}
