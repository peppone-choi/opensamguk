import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { SiegeScreen } from '../components/siege/SiegeScreen';
import { api } from '../lib/api';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({ api: { campaignSieges: vi.fn(), roadForts: vi.fn() } }));

const siege = (over: Record<string, unknown> = {}) => ({
    countyId: 41, countyName: '진류현', status: 'ACTIVE', endReason: null, besieger: { generalId: 7, name: '하후돈', nationId: 1, nationName: '조조' },
    defenderNationId: 2, defenderNationName: '원소', startedAt: { year: 200, month: 2, phase: 3 }, turns: 2, grain: 100, morale: 5000, garrison: 900, trust: 50,
    countySupplied: true, besiegerTroops: 2000, besiegerFed: true, canAct: true, surrenderDemandAccepted: false, timeline: [], ...over,
});
const hrefs = { flow: (i: string, t: string) => `/game/pep?do=${i}&target=${t}`, corps: '/game/pep/corps' };
const setMobile = (on: boolean) => setViewport(on ? 'mobile' : 'desktop');
beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, gates: [], forts: [
        { id: 'f1', edgeId: 'e', provinceId: 'p', row: 1, col: 2, ownerNationId: 2, wall: 800, garrison: 100, besiegerGeneralId: null, siegeProgress: 0, canBesiege: false },
    ] } as never);
});

test('데스크톱 — 첫 포위를 고른 채로, 강공은 명령 흐름(대상 미리 채움), 지휘관이 아니면 사유', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [siege(), siege({ countyId: 42, countyName: '허창', canAct: false })] } as never);
    render(<SiegeScreen hrefs={hrefs} />);
    const orders = await screen.findByRole('region', { name: '공성 명령' });
    fireEvent.click(within(orders).getByRole('button', { name: '강공 — 순 고르기' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.assault&target=siege:41');
    fireEvent.click(screen.getByRole('option', { name: /허창/ }));
    expect(within(screen.getByRole('region', { name: '공성 명령' })).getByRole('button', { name: /강공/ })).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('region', { name: '공성 명령' })).toHaveTextContent('포위 지휘관만 행동을 예약할 수 있습니다.');
    fireEvent.click(screen.getByRole('option', { name: /도로 보루/ }));
    expect(within(screen.getByRole('region', { name: '공성 명령' })).getByRole('button', { name: /보루 포위/ })).toHaveAttribute('aria-disabled', 'true');
});

test('빈 목록 · 실패', async () => {
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: false, gates: [], forts: [] } as never);
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [] } as never);
    const first = render(<SiegeScreen hrefs={hrefs} />);
    expect(await screen.findByText(/포위 중인 성이 없습니다/)).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.campaignSieges).mockRejectedValueOnce(new Error('500: x')).mockResolvedValue({ status: 'READY', sieges: [siege()] } as never);
    render(<SiegeScreen hrefs={hrefs} />);
    expect(await screen.findByText('포위를 불러오지 못했습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('region', { name: '공성 명령' })).toBeInTheDocument();
});

test('모바일 — 목록 → 누르면 상세 + 명령, 뒤로', async () => {
    setMobile(true);
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [siege()] } as never);
    render(<SiegeScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('option', { name: /진류현/ }));
    expect(screen.getByRole('article', { name: '진류현 포위' })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '← 포위 목록' }));
    expect(screen.getByRole('listbox', { name: '포위' })).toBeInTheDocument();
});
