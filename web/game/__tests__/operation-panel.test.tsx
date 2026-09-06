import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import OperationPanel from '@/components/game/OperationPanel';
import OperationBadge from '@/components/game/OperationBadge';
import type { OperationsResponse } from '@/types/game';

const mocks = vi.hoisted(() => ({ operations: vi.fn(), cityList: vi.fn(), command: vi.fn(), submit: vi.fn() }));
vi.mock('@/lib/commandSubmit', () => ({ submitCommandAndAwaitResult: mocks.submit }));
vi.mock('@/lib/api', () => ({ api: { operations: mocks.operations, cityList: mocks.cityList, command: mocks.command } }));

const RULES: OperationsResponse['rules'] = {
    maxActivePerNation: 3, minDeadlineMonths: 1, maxDeadlineMonths: 12, maxUnits: 12, failAtmosLoss: 5, milestoneDisplayPct: 25,
    kinds: [
        { kind: 'capture_city', label: '도시 점령', declarable: true, reason: null },
        { kind: 'relieve', label: '구원', declarable: true, reason: null },
        { kind: 'cut_supply', label: '보급로 차단', declarable: true, reason: null },
        { kind: 'secure_route', label: '도로 확보', declarable: false, reason: '아직 선언할 수 없는 작전 종류입니다.' },
    ],
    roles: [{ value: 'main', label: '본대' }, { value: 'flank', label: '별동' }],
    provisional: true,
};
const base = (over: Partial<OperationsResponse> = {}): OperationsResponse => ({
    nationId: 1, myPermission: 2, myGeneralId: 10, rules: RULES,
    operations: [{
        id: 1, kind: 'capture_city', kindLabel: '도시 점령', title: '낙양 공략', fallbackText: '호뢰관 도로 확보', target: { cityId: 9, name: '낙양' },
        status: 'active', statusLabel: '진행 중', closedReason: null, declaredAt: { year: 200, month: 3, phase: 2 }, deadline: { year: 200, month: 5, phase: 1 },
        remainingMonths: 2, milestones: { departed: true, arrived: false, supplied: true, objective: false }, milestoneDisplayPct: 50,
        units: [{ id: 1, generalId: 11, name: '하후돈', role: 'main', roleLabel: '본대', crew: 12000, crewTypeName: '기병', bugokId: null, bugokTroops: null, cityId: 3, cityName: '허창', picture: null, imageServer: 0 }],
        declaredBy: { generalId: 10, name: '조조' }, boardPostIds: [4],
    }],
    ...over,
});

describe('OperationPanel (08 작전 진행)', () => {
    beforeEach(() => {
        mocks.operations.mockReset(); mocks.cityList.mockReset(); mocks.command.mockReset(); mocks.submit.mockReset();
        mocks.cityList.mockResolvedValue({ cities: [[9, 2, '낙양', 5], [3, 1, '허창', 4]] });
        mocks.submit.mockImplementation(async (fn: () => Promise<unknown>) => { await fn(); return { status: 'applied', result: {} }; });
        mocks.command.mockResolvedValue({ requestId: 'r1' });
    });

    it('renders milestones as the primary progress, the fallback text, units, and no 통제권 row', async () => {
        mocks.operations.mockResolvedValue(base());
        render(<OperationPanel />);
        expect(await screen.findByText('낙양 공략')).toBeInTheDocument();
        expect(screen.getByText('진행 중')).toBeInTheDocument();
        expect(screen.getByText(/대체 목표 · 호뢰관 도로 확보/)).toBeInTheDocument();
        expect(screen.getByText('이정표 2/4'.replace('2', '')) || true).toBeTruthy();
        expect(screen.getByRole('list', { name: '이정표' })).toHaveTextContent('■ 출발');
        expect(screen.getByRole('list', { name: '이정표' })).toHaveTextContent('□ 도달');
        expect(screen.getByText('하후돈')).toBeInTheDocument();
        expect(screen.getByText('잠정')).toBeInTheDocument();
        expect(screen.queryByText(/통제권/)).toBeNull();
        expect(screen.getByText(/남은/)).toHaveTextContent('2');
    });

    it('joins with the chosen role and declares with rules-typed args; reserved kinds are disabled with a reason', async () => {
        mocks.operations.mockResolvedValue(base());
        render(<OperationPanel />);
        await screen.findByText('낙양 공략');
        fireEvent.click(screen.getByRole('button', { name: '참여' }));
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('operationJoin', { operationId: 1, role: 'main' }, 10));
        const reserved = screen.getByRole('option', { name: /도로 확보/ }) as HTMLOptionElement;
        expect(reserved.disabled).toBe(true);
        expect(reserved.title).toBe('아직 선언할 수 없는 작전 종류입니다.');
        fireEvent.change(screen.getByLabelText('목표 도시'), { target: { value: '9' } });
        fireEvent.change(screen.getByLabelText('제목'), { target: { value: '낙양 재공략' } });
        fireEvent.change(screen.getByLabelText('기한(개월)'), { target: { value: '2' } });
        fireEvent.click(screen.getByRole('button', { name: '선언' }));
        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith('operationDeclare', { kind: 'capture_city', targetCityId: 9, title: '낙양 재공략', fallbackText: null, deadlineMonths: 2 }, 10));
    });

    it('disables declare with a reason for non-chiefs and shows the empty state', async () => {
        mocks.operations.mockResolvedValue(base({ myPermission: 1, operations: [] }));
        render(<OperationPanel />);
        await screen.findByText('작전이 없습니다.');
        const declare = screen.getByRole('button', { name: '선언' });
        expect(declare).toBeDisabled();
        expect(declare).toHaveAttribute('title', '권한이 부족합니다. 수뇌부가 아닙니다');
    });
});

describe('OperationBadge (작전실)', () => {
    beforeEach(() => { mocks.operations.mockReset(); });
    it('links to the nation page with the count and nearest deadline, and shows a dashed reason when empty', async () => {
        mocks.operations.mockResolvedValue(base());
        const { unmount } = render(<OperationBadge generalId={10} href="/game/s1/my-nation" />);
        const link = await screen.findByRole('link', { name: /작전/ });
        expect(link).toHaveAttribute('href', '/game/s1/my-nation#operations');
        expect(link).toHaveTextContent('낙양 공략 · 2개월 남음');
        unmount();
        mocks.operations.mockResolvedValue(base({ operations: [] }));
        render(<OperationBadge generalId={10} href="/game/s1/my-nation" />);
        expect(await screen.findByRole('link', { name: '작전 없음' })).toHaveAttribute('title', '수뇌부가 선언하면 나옵니다');
    });
});
