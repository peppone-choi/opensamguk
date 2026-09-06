import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import RetinuePanels from '@/components/game/RetinuePanels';
import RetinueSlot from '@/components/game/RetinueSlot';
import type { RetinueResponse } from '@/types/game';

const mocks = vi.hoisted(() => ({
    myRetinue: vi.fn(),
    command: vi.fn(),
    submit: vi.fn(),
}));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/lib/api', () => ({ api: { myRetinue: mocks.myRetinue, command: mocks.command } }));

const RULES: RetinueResponse['rules'] = {
    maxRetainers: 5, maxBugok: 2, pledgeCostGold: 500, minBugokTroops: 100, retainerUpkeepGold: 30, retainerUpkeepRice: 30,
    payGoldPer100Troops: 10, provisionPerTroopMonth: 1, commanderMoraleBonus: 6,
    relations: [{ value: 'staff', label: '막료' }, { value: 'lieutenant', label: '부장' }, { value: 'guest', label: '문객' }],
    roles: [{ value: 'NONE', label: '없음' }, { value: 'GUARD', label: '호위' }],
    tasks: [{ value: 'none', label: '없음' }, { value: 'train', label: '훈련' }],
    provisional: true,
};

const base = (over: Partial<RetinueResponse> = {}): RetinueResponse => ({
    generalId: 10, generalName: '유비', crew: 1000, rice: 3000, gold: 2000, crewTypeId: 1100, crewTypeName: '보병',
    retainers: [{ id: 3, name: '홍길동', origin: 'RECRUITED', relation: 'lieutenant', relationLabel: '부장', role: 'GUARD', roleLabel: '호위', loyalty: 51, task: 'train', taskLabel: '훈련', hasOwnBugok: false }],
    bugoks: [{ id: 2, name: '부곡 1', troops: 300, crewTypeId: 1100, crewTypeName: '보병', training: 70, morale: 66, fatigue: 5, provisions: 900, provisionMonths: 3, commanderRetainerId: 3 }],
    rules: RULES,
    ...over,
});

describe('RetinuePanels (07 휘하 인물 · 부곡)', () => {
    beforeEach(() => {
        mocks.myRetinue.mockReset(); mocks.command.mockReset(); mocks.submit.mockReset();
        mocks.submit.mockImplementation(async (fn: () => Promise<unknown>) => { await fn(); return { status: 'applied', result: {} }; });
        mocks.command.mockResolvedValue({ requestId: 'r1' });
    });

    it('renders rows from the API with labels, rules-driven counts, and the 잠정 chip', async () => {
        mocks.myRetinue.mockResolvedValue(base());
        render(<RetinuePanels generalId={10} />);
        expect((await screen.findAllByText('홍길동')).length).toBeGreaterThan(0);
        expect(screen.getAllByText('부장').length).toBeGreaterThan(0);
        expect(screen.getAllByText('호위').length).toBeGreaterThan(0);
        expect(screen.getByText('1 / 5')).toBeInTheDocument();
        expect(screen.getByText('잠정')).toBeInTheDocument();
        expect(screen.getByText('부곡 1')).toBeInTheDocument();
        expect(screen.getByText('(3개월)')).toBeInTheDocument();
        expect(screen.getByRole('combobox', { name: '부곡 1 지휘관' })).toHaveValue('3');
        expect(screen.getByText(/비용/)).toHaveTextContent('500');
    });

    it('submits pledge and form through the intake with rules-typed args and refetches', async () => {
        mocks.myRetinue.mockResolvedValue(base());
        const onChanged = vi.fn();
        render(<RetinuePanels generalId={10} onChanged={onChanged} />);
        await screen.findAllByText('홍길동');
        fireEvent.change(screen.getByLabelText('이름'), { target: { value: '임꺽정' } });
        fireEvent.change(screen.getByLabelText('관계'), { target: { value: 'guest' } });
        fireEvent.click(screen.getByRole('button', { name: '서약' }));
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('retainerPledge', { name: '임꺽정', relation: 'guest', role: 'NONE' }, 10));
        fireEvent.change(screen.getByLabelText('병력'), { target: { value: '300' } });
        fireEvent.change(screen.getByLabelText('군량'), { target: { value: '900' } });
        fireEvent.click(screen.getByRole('button', { name: '편성' }));
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('bugokForm', { troops: 300, rice: 900 }, 10));
        fireEvent.change(screen.getByRole('combobox', { name: '부곡 1 지휘관' }), { target: { value: '' } });
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('bugokAssignCommander', { bugokId: 2, retainerId: null }, 10));
        expect(onChanged).toHaveBeenCalled();
        expect(mocks.myRetinue.mock.calls.length).toBeGreaterThan(1);
    });

    it('disables pledge and form with reasons when full or short, and shows the intake reason on rejection', async () => {
        mocks.myRetinue.mockResolvedValue(base({ gold: 10, crew: 50, retainers: [], bugoks: [] }));
        mocks.submit.mockResolvedValue({ status: 'rejected', reason: '자금이 부족합니다.' });
        render(<RetinuePanels generalId={10} />);
        await screen.findByText('휘하 인물이 없습니다.');
        const pledge = screen.getByRole('button', { name: '서약' });
        expect(pledge).toBeDisabled();
        expect(pledge).toHaveAttribute('title', '자금이 부족합니다');
        const form = screen.getByRole('button', { name: '편성' });
        expect(form).toBeDisabled();
        expect(form).toHaveAttribute('title', '병력이 100 미만입니다');
    });
});

describe('RetinueSlot (D3-17)', () => {
    beforeEach(() => { mocks.myRetinue.mockReset(); });

    it('shows a dashed reason when empty and a badge link when populated', async () => {
        mocks.myRetinue.mockResolvedValue(base({ retainers: [], bugoks: [] }));
        const { unmount } = render(<RetinueSlot generalId={10} href="/game/s1/my" />);
        expect(await screen.findByRole('button', { name: '휘하 없음' })).toHaveAttribute('title', '서약하면 여기 나옵니다');
        unmount();
        mocks.myRetinue.mockResolvedValue(base());
        render(<RetinueSlot generalId={10} href="/game/s1/my" />);
        const link = await screen.findByRole('link', { name: /휘하/ });
        expect(link).toHaveAttribute('href', '/game/s1/my#retinue');
        expect(link).toHaveTextContent('1명 · 부곡 1');
    });
});
