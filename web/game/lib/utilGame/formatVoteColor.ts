// legacy hwe/ts/utilGame/formatVoteColor.ts 충실 포팅 — 투표 항목 type → 색.
// legacy는 css-color-names 패키지로 7색 이름→hex를 조회한다. web/game엔 패키지가 없어
// 동일 7색의 표준 CSS hex를 직접 vendor(faithful: css-color-names 반환값과 동일).
const colors: string[] = [
  '#ff0000', // red
  '#ffa500', // orange
  '#ffff00', // yellow
  '#008000', // green
  '#0000ff', // blue
  '#000080', // navy
  '#800080', // purple
];

export function formatVoteColor(type: number): string {
  return colors[type % colors.length];
}
