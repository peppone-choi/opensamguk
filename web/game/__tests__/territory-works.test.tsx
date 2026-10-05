import { fireEvent, render, screen, within } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { REDUCE_RULE, WorkSheet, WorksPanel } from '../components/territory/WorkParts';
import type { CountyWorks, Works } from '../lib/campaign-reads';
import { stockChips, stopText, workBody, workChoices, workRows } from '../lib/territory-view';

// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }) }));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const county = (over: Partial<CountyWorks>): CountyWorks => ({
    countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군', warehouse: { ...zero, money: 500, timber: 80 },
    active: null, completed: [], startable: [], ...over,
});
const works: Works = {
    status: 'READY',
    provisional: 'DRAFT',
    counties: [
        county({
            active: { work: 'WAREHOUSE', label: '창고', percent: 40, remainingPhases: 3, remainingCost: { ...zero, money: 120 },
                stopReasonText: 'NEW_UNKNOWN_CODE', startsAtNextBoundary: true },
            completed: [{ work: 'FORTIFICATION', label: '성방', edgeId: null, completedAt: { year: 200, month: 2, phase: 1 } }],
            startable: [
                { work: 'FARM', label: '둔전', available: true, blocked: null, cost: { ...zero, money: 200, grain: 50 }, estimatedPhases: 6 },
                { work: 'ROAD', label: '도로', available: true, blocked: null, cost: { ...zero, timber: 100 }, estimatedPhases: 9 },
                { work: 'MARKET', label: '시장수운', available: false, blocked: { code: 'NO_RIVER', reason: '물길이 없는 현입니다.' }, cost: zero, estimatedPhases: 12 },
            ],
        }),
        county({ countyId: 130, name: '허현', warehouse: null, active: { work: 'REPAIR', label: '수리', percent: 90, remainingPhases: 1,
            remainingCost: zero, stopReasonText: '창고의 자재가 모자랍니다.', startsAtNextBoundary: false } }),
    ],
};

test('보기 모델 — 0 자원은 빼고, 모르는 멈춤 코드는 「멈춤(사유 준비 중)」, 완공 시각, 성방 여부', () => {
    expect(stockChips({ ...zero, money: 200, grain: 50 })).toEqual(['금 200', '쌀 50']);
    expect(stopText('NEW_UNKNOWN_CODE')).toBe('멈춤(사유 준비 중)');
    expect(stopText('창고의 자재가 모자랍니다.')).toBe('창고의 자재가 모자랍니다.');
    const [a, b] = workRows(works);
    expect(a.active).toMatchObject({ percent: 40, stop: '멈춤(사유 준비 중)', nextBoundary: true, remainingCost: ['금 120'] });
    expect(a.completed).toEqual(['성방 · 200년 2월 상순']);
    expect(a.hasFortification).toBe(true);
    expect(b.hasFortification).toBe(false);
    expect(workChoices(works.counties[0]).map((c) => [c.label, c.available, c.reason, c.cost.join(','), c.phases])).toEqual([
        ['둔전', true, null, '금 200,쌀 50', 6],
        ['도로', true, null, '목재 100', 9],
        ['시장수운', false, '물길이 없는 현입니다.', '', 12],
    ]);
    expect(workBody(129, 'ROAD', { edgeId: 'e-1' })).toEqual({ countyId: 129, work: 'ROAD', edgeId: 'e-1' });
});

test('공사 칸 — 진척 막대 · 멈춤 · 잠정, 성방 허물기는 준비 중(점선) + 규칙 문구, 영문 코드는 안 보인다', () => {
    const onNewWork = vi.fn();
    render(<WorksPanel works={works} startAvailabilityOf={() => ({ inputId: 'work.start', status: 'AVAILABLE' })}
        reduceAvailability={{ inputId: 'work.reduce', status: 'NOT_DELIVERED' }} onNewWork={onNewWork} onReduce={() => {}} />);
    expect(screen.getByText('잠정')).toBeInTheDocument();
    const [yang, heo] = screen.getAllByRole('listitem');
    expect(within(yang).getByRole('meter')).toHaveAttribute('aria-valuenow', '40');
    expect(yang).toHaveTextContent('멈춤(사유 준비 중)');
    expect(yang).not.toHaveTextContent('NEW_UNKNOWN_CODE');
    const reduce = within(yang).getByRole('button', { name: /성방 허물기/ });
    expect(reduce).toHaveAttribute('data-input-status', 'NOT_DELIVERED');
    expect(yang).toHaveTextContent(REDUCE_RULE);
    expect(within(heo).queryByRole('button', { name: /성방 허물기/ })).toBeNull();
    expect(heo).toHaveTextContent('창고의 자재가 모자랍니다.');
    fireEvent.click(within(heo).getByRole('button', { name: '새 공사' }));
    expect(onNewWork).toHaveBeenCalledWith(expect.objectContaining({ countyId: 130 }));
});

test('새 공사 시트 — 불가 공사는 사유, 도로는 접경을 고르기 전 막힘, 고르면 몸통에 더해 보낸다', () => {
    const onSubmit = vi.fn();
    const extraFor = (w: string) => (w === 'ROAD' ? { node: <p>지도</p>, body: null, missing: '잇는 접경을 지도에서 고르세요.' } : null);
    const { rerender } = render(<WorkSheet county={works.counties[0]} busy={false} onSubmit={onSubmit} onCancel={() => {}} extraFor={extraFor} />);
    const list = screen.getByRole('listbox', { name: '공사' });
    expect(within(list).getByRole('option', { name: /시장수운/ })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(list).getByRole('option', { name: /도로/ }));
    expect(screen.getByText('지도')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');

    const picked = (w: string) => (w === 'ROAD' ? { node: <p>지도</p>, body: { edgeId: 'e-7' }, missing: '' } : null);
    rerender(<WorkSheet county={works.counties[0]} busy={false} onSubmit={onSubmit} onCancel={() => {}} extraFor={picked} />);
    fireEvent.click(screen.getByRole('button', { name: '이 공사로' }));
    expect(onSubmit).toHaveBeenCalledWith({ countyId: 129, work: 'ROAD', edgeId: 'e-7' });
});
