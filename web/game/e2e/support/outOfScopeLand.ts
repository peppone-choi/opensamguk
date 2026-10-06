import { readFileSync } from 'node:fs';
import { join } from 'node:path';

/** 지도 바탕색(격자 바깥 · 아직 안 그린 곳). */
export const MAP_BACKGROUND = [0x0c, 0x0f, 0x0e];

/**
 * 범위 밖 땅(격자 안의 그리지 않는 칸)의 흐린 땅색(D42) — shared `types.ts`의 OUT_OF_SCOPE_LAND와 같은 규칙
 * (키트 낮 팔레트 14번 × 0.45)을 시험 키트 팔레트로 계산한다. 규칙을 바꾸면 여기도 같이 바꾼다.
 */
export function outOfScopeLandColour(kitDir: string): number[] {
  const palettes = JSON.parse(readFileSync(join(kitDir, 'palettes.json'), 'utf8')) as { dayBank: number; banks: number[][][] };
  return palettes.banks[palettes.dayBank][14].map((value) => Math.round(value * 0.45));
}
