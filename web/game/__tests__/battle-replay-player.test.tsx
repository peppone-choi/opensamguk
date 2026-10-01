import { fireEvent, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BattleReplayPlayer from '@/components/game/BattleReplayPlayer';
import type { BattleReplayDetail, BattleReplaySummary } from '@/types/game';

const mocks = vi.hoisted(() => ({ battleReplay: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: { battleReplay: mocks.battleReplay } }));

const summary: BattleReplaySummary = { id: 7, year: 200, month: 3, phase: 3, attackerGeneralId: 10, attackerName: '하후돈', attackerNationId: 1, defenderCityId: 31, defenderCityName: '호뢰관', defenderNationId: 2, result: 'retreat', resultLabel: '퇴각', attackerDead: 4200, defenderDead: 1500, hasPlan: true, planStop: 'morale', planStopLabel: '사기 조건', operationId: 3 };
const detail: BattleReplayDetail = {
    summary,
    battlePhases: [
        { i: 1, defId: 201, def: '화웅', defKind: 'general', contact: true, deadA: 1900, deadD: 600, crewA: 14100, hpD: 8400 },
        { i: 2, defId: 201, def: '화웅', defKind: 'general', contact: false, deadA: 1400, deadD: 700, crewA: 12700, hpD: 7700 },
        { i: 3, defId: 31, def: '호뢰관', defKind: 'city', contact: true, deadA: 900, deadD: 200, crewA: 11800, hpD: 4000 },
    ],
    settlement: { attackerCrewBefore: 16000, attackerCrewAfter: 11800, attackerDead: 4200, defenderDead: 1500, riceUsed: 2900, conquered: false },
    plan: { stance: 'assault', stanceLabel: '돌격', retreatLossPct: null, retreatMoraleBelow: 40, planStop: 'morale', planStopLabel: '사기 조건', stopAtPhase: 3 },
    seed: { warSeed: '0'.repeat(32), inputHash: 'a'.repeat(64), replayHash: 'deadbeef' + 'b'.repeat(56), schemaVersion: 1 },
    operationId: 3,
};

describe('BattleReplayPlayer (10 리플레이)', () => {
    beforeEach(() => { mocks.battleReplay.mockReset(); });

    it('renders the 對 header, scrubs phases, marks the triggered condition, shows settlement and the hash prefix', async () => {
        mocks.battleReplay.mockResolvedValue(detail);
        render(<BattleReplayPlayer id={7} recordsHref="../records" operationHref="../my-nation#operations" />);
        expect(await screen.findByText('하후돈')).toBeInTheDocument();
        expect(screen.getByText('16,000 → 11,800 (-4,200)')).toBeInTheDocument();
        expect(screen.getByText('퇴각')).toBeInTheDocument();
        expect(screen.getByText('1 / 3')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '다음 페이즈' }));
        expect(screen.getByText('2 / 3')).toBeInTheDocument();
        expect(screen.getByLabelText('현재 페이즈')).toHaveTextContent('P2');
        expect(screen.getByText(/조건 발동 · 사기 조건/)).toBeInTheDocument();
        expect(screen.getByText(/deadbeef/)).toBeInTheDocument();
        expect(screen.getByText('-2,900')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '작전 #3' })).toHaveAttribute('href', '../my-nation#operations');
        // 지운 감찰부(K9) 대신 기록 5분류로 나간다.
        expect(screen.getByRole('link', { name: '기록' })).toHaveAttribute('href', '../records');
        fireEvent.click(screen.getByRole('button', { name: '2×' }));
        expect(screen.getByRole('button', { name: '2×' })).toHaveAttribute('aria-pressed', 'true');
    });
});
