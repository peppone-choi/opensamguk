// legacy hwe/ts/utilGame/getNPCColor.ts 충실 포팅 — NPC 타입별 표시 색.
export function getNPCColor(
  npcType: number,
): 'skyblue' | 'cyan' | 'deepskyblue' | 'darkcyan' | 'mediumaquamarine' | 'gold' | undefined {
  if (npcType == 7) {
    return 'gold';
  }
  if (npcType == 6) {
    return 'mediumaquamarine';
  }
  if (npcType == 5) {
    return 'darkcyan';
  }
  if (npcType == 4) {
    return 'deepskyblue';
  }
  if (npcType >= 2) {
    return 'cyan';
  }
  if (npcType == 1) {
    return 'skyblue';
  }
  return undefined;
}
