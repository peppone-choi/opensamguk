import { afterEach, describe, expect, it, vi } from 'vitest';

// B4: 전략 지도 투영 빌드 플래그. 비어 있으면(기본) 탑다운 그대로, 'dimetric' 이면 2:1.
describe('STRATEGIC_PROJECTION (NEXT_PUBLIC_STRATEGIC_PROJECTION)', () => {
  afterEach(() => {
    vi.unstubAllEnvs();
    vi.resetModules();
  });

  it.each([
    [undefined, 'topdown'],
    ['', 'topdown'],
    ['topdown', 'topdown'],
    ['dimetric', 'dimetric'],
  ])('플래그 %s → %s', async (flag, expected) => {
    vi.stubEnv('NEXT_PUBLIC_STRATEGIC_PROJECTION', flag);
    const { STRATEGIC_PROJECTION } = await import('@/lib/strategic-projection');
    // 모듈을 다시 읽으므로 투영 객체 자체가 아니라 종류로 본다
    expect(STRATEGIC_PROJECTION.kind).toBe(expected);
  });
});
