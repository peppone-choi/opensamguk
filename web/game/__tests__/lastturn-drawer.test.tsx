import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { LastTurnDrawerBody } from '../components/lastturn/LastTurnDrawerBody';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: { global: { year: 200, month: 3, turnPhase: 2 } } }),
}));
vi.mock('../lib/api', () => ({
    api: { campaignLastTurns: vi.fn(), dispatchPending: vi.fn(), politicalConsentOptions: vi.fn(), courtDispatchReply: vi.fn(), courtPoliticalConsent: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const phase = { year: 200, month: 3, phase: 1 };
const hrefs = { yuedan: '/y', county: (id: number) => `/c/${id}`, records: '/r', court: '/game/pep/court' };
beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [
        { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 2, issuerLabel: '조조', countyLabel: '양적현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
    ] } as never);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([] as never);
});

test('응답하기 — 받은 요청 카드 한 장을 그 항목 아래에 펼치고, 조정 고리를 둔다', async () => {
    vi.mocked(api.campaignLastTurns).mockResolvedValue({ status: 'READY', nationSummary: [], turns: [
        { year: 200, month: 3, phase: 1, phaseLabel: '상순', entries: [{ kind: 'court.dispatchReceived', text: '조조가 양적현 현령으로 발령했습니다.', refs: { dispatchId: 'd1', countyId: 2 } }] },
    ] } as never);
    render(<LastTurnDrawerBody hrefs={hrefs} />);
    expect(screen.getByRole('region', { name: '지난 순' })).toHaveTextContent('– 200년 3월 상순');
    const reply = await screen.findByRole('button', { name: '응답하기' });
    expect(screen.getByText('응답 대기')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '수락' })).toBeNull();
    fireEvent.click(reply);
    expect(await screen.findByRole('button', { name: '수락' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '조정에서 모두 보기 →' })).toHaveAttribute('href', '/game/pep/court');
    fireEvent.click(reply);
    expect(screen.queryByRole('button', { name: '수락' })).toBeNull();
});

test('실패는 다시 시도', async () => {
    vi.mocked(api.campaignLastTurns).mockRejectedValueOnce(new Error('500: x')).mockResolvedValue({ status: 'READY', turns: [], nationSummary: [] } as never);
    render(<LastTurnDrawerBody hrefs={hrefs} />);
    expect(await screen.findByText('지난 순을 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await within(screen.getByRole('region', { name: '지난 순' })).findByText(/최근 12순에 남은 기록이 없습니다/)).toBeInTheDocument();
});
