// 선전포고(court.declareWar) — 옵션은 legacy-court-options, 제출 본문은 정확히 { targetNationId }, 안내 문구는 정책 상수에서.
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/lib/api', () => ({ api: { legacyCourtOptions: vi.fn(), courtLegacy: vi.fn() } }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: vi.fn((send: () => Promise<unknown>) => send()) }));
import { api } from '@/lib/api';
import { DECLARE_WAR, declareWarWarning } from '../lib/diplomacy/declare-war';
import { DECLARE_WAR_POLICY, proposalsFor } from '../lib/diplomacy/relations';

beforeEach(() => vi.clearAllMocks());

describe('선전포고 입력', () => {
    it('옵션은 court.declareWar 로 그 장수 기준으로 읽는다', async () => {
        vi.mocked(api.legacyCourtOptions).mockResolvedValue({ inputId: 'court.declareWar', available: true, choices: [] } as never);
        await DECLARE_WAR.options(7);
        expect(api.legacyCourtOptions).toHaveBeenCalledWith('court.declareWar', 7);
    });

    it('제출은 court.declareWar 한 번, 본문은 정확히 { targetNationId }', async () => {
        vi.mocked(api.courtLegacy).mockResolvedValue({ status: 'AVAILABLE', requestId: 'r' } as never);
        await DECLARE_WAR.submit(7, 2);
        expect(api.courtLegacy).toHaveBeenCalledTimes(1);
        expect(api.courtLegacy).toHaveBeenCalledWith('court.declareWar', 7, { targetNationId: 2 });
    });

    it('위험 확인 문구 — 교전까지 기간은 정책 상수, 비용 · 사자 문구 없음, 불가침 중이면 불가침이 끝남을 알린다', () => {
        expect(DECLARE_WAR_POLICY).toEqual({ termMonths: 24, allowDuringNonAggression: true });
        const plain = declareWarWarning('[갑]', false);
        expect(plain).toContain('[갑]에 선전포고합니다');
        expect(plain).toContain(`게임 시간 ${DECLARE_WAR_POLICY.termMonths}개월 뒤 교전이 시작됩니다`);
        expect(plain).not.toMatch(/비용|사자|금|쌀/);
        expect(plain).not.toContain('불가침');
        expect(declareWarWarning('[을]', true)).toContain('맺고 있는 불가침은 선포와 함께 끝납니다');
        expect(DECLARE_WAR.confirm?.message('[을]', 'nonAggression')).toBe(declareWarWarning('[을]', true));
        expect(DECLARE_WAR.confirm?.message('[갑]', 'none')).toBe(plain);
    });

    it('단추는 관계 없음 · 불가침에서만 — 교전 · 선포 유예 · 모름에는 없다', () => {
        const has = (r: Parameters<typeof proposalsFor>[0]) => proposalsFor(r).some((p) => p.inputId === 'court.declareWar');
        expect(has('none')).toBe(true);
        expect(has('nonAggression')).toBe(true);
        expect(has('war')).toBe(false);
        expect(has('declared')).toBe(false);
        expect(has('unknown')).toBe(false);
    });
});
