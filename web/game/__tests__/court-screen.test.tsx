import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { CourtScreen } from '../components/court/CourtScreen';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({
    api: {
        dispatchPending: vi.fn(), dispatchOptions: vi.fn(), politicalConsentOptions: vi.fn(), campaignRetinue: vi.fn(), legacyCourtOptions: vi.fn(),
        courtDispatch: vi.fn(), courtReward: vi.fn(), courtLegacy: vi.fn(), courtDispatchReply: vi.fn(), courtPoliticalConsent: vi.fn(),
    },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const phase = { year: 200, month: 3, phase: 2 };
const hrefs = { territory: '/game/pep/territory', office: '/game/pep/court/offices', diplomacy: '/game/pep/court/diplomacy' };

beforeEach(() => {
    vi.clearAllMocks();
    setViewport('desktop');
    vi.mocked(api.dispatchPending).mockResolvedValue({ result: true, dispatches: [
        { dispatchId: 'd1', issuerId: 1, targetId: 7, countyId: 2, issuerLabel: '조조', countyLabel: '양적현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
        { dispatchId: 'd2', issuerId: 7, targetId: 21, countyId: 3, targetLabel: '순욱', countyLabel: '허현', issuedAt: phase, dueAt: phase, status: 'PENDING' },
    ] } as never);
    vi.mocked(api.politicalConsentOptions).mockResolvedValue([] as never);
    vi.mocked(api.dispatchOptions).mockImplementation(async (_g: number, target?: number) => (target == null
        ? { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [] }
        : { result: true, targets: [{ generalId: 21, label: '순욱' }], counties: [{ countyId: 129, label: '양성현', available: true }] }) as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, people: [], units: [] } as never);
    vi.mocked(api.legacyCourtOptions).mockImplementation(async (inputId: string) => (inputId === 'court.moveCapital'
        ? { inputId, available: true, choices: [{ label: '진류현 (41)', arguments: { cityId: 41 }, available: true }] }
        : { inputId, available: false, reason: '군주만 할 수 있습니다.', choices: [] }) as never);
});

test('데스크톱 — 받은 요청 띠(대기만) · 내린 발령 · 조정 결정(서버 사유), 천도는 시트에서 id 꼬리 없이 접수', async () => {
    vi.mocked(api.courtLegacy).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    const band = await screen.findByRole('region', { name: '받은 요청' });
    await waitFor(() => expect(band).toHaveTextContent('조조'));
    expect(await screen.findByRole('list', { name: '내린 발령' })).toHaveTextContent('순욱 → 허현');
    const decisions = screen.getByRole('region', { name: '조정 결정' });
    await waitFor(() => expect(within(decisions).getByRole('button', { name: /현 포기/ })).toHaveAttribute('aria-disabled', 'true'));
    expect(decisions).toHaveTextContent('군주만 할 수 있습니다.');
    fireEvent.click(within(decisions).getByRole('button', { name: '천도 — 고르기' }));
    const sheet = await screen.findByRole('region', { name: '천도' });
    fireEvent.click(within(sheet).getByRole('option', { name: '진류현' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이대로 접수' }));
    await waitFor(() => expect(vi.mocked(api.courtLegacy)).toHaveBeenCalledWith('court.moveCapital', 7, { cityId: 41 }));
    expect(await screen.findByText('천도 — 접수했습니다. 다음 개인 턴에 처리합니다.')).toBeInTheDocument();
});

test('새 발령 — 사람을 고르면 그 사람 기준 현 후보를 다시 받아 보낸다', async () => {
    vi.mocked(api.courtDispatch).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CourtScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '새 발령' }));
    fireEvent.click(await screen.findByRole('option', { name: /순욱/ }));
    const list = await screen.findByRole('listbox', { name: '발령할 현' });
    expect(vi.mocked(api.dispatchOptions)).toHaveBeenCalledWith(7, 21);
    fireEvent.click(within(list).getByRole('option', { name: /양성현/ }));
    fireEvent.click(screen.getByRole('button', { name: '이 현으로 발령' }));
    await waitFor(() => expect(vi.mocked(api.courtDispatch)).toHaveBeenCalledWith(7, { targetGeneralId: 21, countyId: 129 }));
});

test('모바일 — 조정 결정 목록(받은 요청 대기 수 · 원장 행 없는 것은 없음), 누르면 받은 요청 시트', async () => {
    setViewport('mobile');
    render(<CourtScreen hrefs={hrefs} />);
    const list = await screen.findByRole('list', { name: '조정 결정' });
    await waitFor(() => expect(within(list).getAllByRole('listitem')[0]).toHaveTextContent('응답 대기 1'));
    expect(list).toHaveTextContent('외교');
    fireEvent.click(within(within(list).getAllByRole('listitem')[0]).getByRole('button'));
    expect(await screen.findByTestId('incoming-requests')).toHaveTextContent('조조');
});
