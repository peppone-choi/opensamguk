// legacy hwe/ts/utilGame/tournament.ts 충실 포팅 — 토너먼트 텀 = clamp(turnTerm, 5, 120).
import { clamp } from './_helpers';

export function calcTournamentTerm(turnTerm: number): number {
  return clamp(turnTerm, 5, 120);
}
