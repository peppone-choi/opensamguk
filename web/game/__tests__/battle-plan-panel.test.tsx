import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import BattlePlanPanel from '@/components/game/BattlePlanPanel';
import type { MyBattlePlansResponse } from '@/types/game';

const mocks = vi.hoisted(() => ({ myPage: vi.fn(), city: vi.fn(), myBattlePlans: vi.fn(), reservedCommands: vi.fn(), simulateBattle: vi.fn(), command: vi.fn(), submit: vi.fn() }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/lib/api', () => ({ api: { myPage: mocks.myPage, city: mocks.city, myBattlePlans: mocks.myBattlePlans, reservedCommands: mocks.reservedCommands, simulateBattle: mocks.simulateBattle, command: mocks.command } }));

const RULES: MyBattlePlansResponse['rules'] = {
    stances: [
        { value: 'assault', label: '돌격', description: '오늘의 출병과 같다', enabled: true, reason: null },
        { value: 'probe', label: '탐색', description: '첫 접촉 뒤 퇴각', enabled: true, reason: null },
        { value: 'advance', label: '전진', description: '', enabled: false, reason: '이 절편에서는 지원하지 않습니다.' },
        { value: 'defend', label: '방어', description: '', enabled: false, reason: '이 절편에서는 지원하지 않습니다.' },
    ],
    retreatLossPctMin: 10, retreatLossPctMax: 90, retreatMoraleMin: 0, retreatMoraleMax: 100, provisional: true,
};
const me = { generalId: 10, name: '하후돈', nationId: 1, cityId: 7, officerLevel: 5, crew: 12000, train: 84, atmos: 77, picture: null, imageServer: 0 };
const city = (over: Record<string, unknown> = {}) => ({ id: 31, name: '호뢰관', nationId: 2, showDetailedInfo: true, generals: [{ no: 201, name: '화웅', crew: 9000, crewTypeName: '기병', ourGeneral: false, isNPC: true }], ...over });
const plans = (over: Partial<MyBattlePlansResponse> = {}): MyBattlePlansResponse => ({ generalId: 10, plans: [], rules: RULES, ...over });

describe('BattlePlanPanel (09 명령 봉인)', () => {
    beforeEach(() => {
        Object.values(mocks).forEach((m) => m.mockReset());
        mocks.myPage.mockResolvedValue(me); mocks.city.mockResolvedValue(city()); mocks.reservedCommands.mockResolvedValue({ slots: [], turnTime: '2026-09-06 23:00:00', turnTerm: 60 });
        mocks.submit.mockImplementation(async (fn: () => Promise<unknown>) => { await fn(); return { status: 'applied', result: {} }; });
        mocks.command.mockResolvedValue({ requestId: 'r1' });
        mocks.simulateBattle.mockResolvedValue({ result: true, repeatCnt: 20, killed: 600, maxKilled: 900, minKilled: 300, dead: 1900, maxDead: 2500, minDead: 1200 });
    });

    it('renders two enabled stances, reserved stances disabled with the reason, and the no-pursuit control disabled with its reason', async () => {
        mocks.myBattlePlans.mockResolvedValue(plans());
        render(<BattlePlanPanel cityId={31} battleCenterHref="battle-center" />);
        expect(await screen.findByText('하후돈')).toBeInTheDocument();
        expect(screen.getByRole('radio', { name: /돌격/ })).toBeEnabled();
        expect(screen.getByRole('radio', { name: /탐색/ })).toBeEnabled();
        expect(screen.getByRole('radio', { name: /전진/ })).toBeDisabled();
        expect(screen.getAllByText(/이 절편에서는 지원하지 않습니다/).length).toBeGreaterThanOrEqual(2);
        expect(screen.getByText(/엔진에 추격이 없습니다/)).toBeInTheDocument();
        expect(screen.getByText('목록 첫 수비자')).toBeInTheDocument();
        expect(screen.getByText('계획 없음')).toBeInTheDocument();
        expect(screen.getByText(/봉인까지 \d\d:\d\d:\d\d|턴 시각 미확인/)).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '봉인' })).toBeDisabled();
        expect(screen.getByRole('button', { name: '봉인' })).toHaveAttribute('data-reason', '먼저 저장하세요');
    });

    it('saves typed args (null for unchecked conditions), estimates from the first listed defender', async () => {
        mocks.myBattlePlans.mockResolvedValue(plans());
        render(<BattlePlanPanel cityId={31} battleCenterHref="battle-center" />);
        await screen.findByText('하후돈');
        fireEvent.click(screen.getByRole('radio', { name: /탐색/ }));
        fireEvent.click(screen.getAllByRole('checkbox')[0]);
        fireEvent.change(screen.getByLabelText('퇴각 손실 %'), { target: { value: '30' } });
        fireEvent.click(screen.getByRole('button', { name: '저장' }));
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('battlePlanSave', { targetCityId: 31, stance: 'probe', retreatLossPct: 30, retreatMoraleBelow: null }, 10));
        fireEvent.click(screen.getByRole('button', { name: '예상 (결정론 시뮬)' }));
        await waitFor(() => expect(mocks.simulateBattle).toHaveBeenCalledWith({ attackerGeneralId: 10, defenderGeneralId: 201, repeatCnt: 20 }));
        expect(await screen.findByText('300 / 600 / 900')).toBeInTheDocument();
        expect(screen.getByText(/목록 첫 수비자 1인 기준 예상/)).toBeInTheDocument();
    });

    it('a sealed plan locks the form with the reason and shows the sealed chip; no defenders disables the estimate with a reason', async () => {
        mocks.myBattlePlans.mockResolvedValue(plans({ plans: [{ id: 5, targetCityId: 31, targetCityName: '호뢰관', stance: 'probe', stanceLabel: '탐색', retreatLossPct: 30, retreatMoraleBelow: null, sealed: true, sealedAt: 'x', sealedDate: { year: 200, month: 3, phase: 2 }, resolved: false, version: 2 }] }));
        mocks.city.mockResolvedValue(city({ generals: [] }));
        render(<BattlePlanPanel cityId={31} battleCenterHref="battle-center" />);
        await screen.findByText('하후돈');
        expect(screen.getByText('봉인됨 · 200年 3月 중순')).toBeInTheDocument();
        expect(screen.getByRole('radio', { name: /탐색/ })).toBeDisabled();
        await waitFor(() => expect(screen.getByRole('radio', { name: /탐색/ })).toBeChecked());
        for (const name of ['저장', '봉인', '삭제']) expect(screen.getByRole('button', { name })).toHaveAttribute('data-reason', '봉인된 계획입니다');
        expect(screen.getByRole('button', { name: '예상 (결정론 시뮬)' })).toHaveAttribute('data-reason', '수비 장수 없음 — 성 방어만');
        expect(screen.getByText(/해결까지/)).toBeInTheDocument();
    });
});
