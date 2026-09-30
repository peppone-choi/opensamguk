import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { TerritoryScreen } from '../components/territory/TerritoryScreen';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({
    api: { campaignPosts: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(), roadForts: vi.fn(), campaignRetinue: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const hrefs = { supply: '/game/pep/territory/supply', court: '/game/pep/court' };
function setMobile(matches: boolean) {
    window.matchMedia = ((query: string) => ({ matches, media: query, onchange: null, addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false })) as unknown as typeof window.matchMedia;
}

beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'READY', cards: [], posts: [] } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }], corpsOptions: [], defaultPolicy: null, corps: [],
        counties: [{ countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null }] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군',
        warehouse: null, active: null, completed: [], startable: [{ work: 'ROAD', label: '도로', available: true, blocked: null, cost: zero, estimatedPhases: 9 }] }] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [{ cityId: 3, name: '허현', commanderyName: null, isCapital: true, supplied: true, stock: { ...zero, money: 900 } }] } as never);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, forts: [], gates: [] } as never);
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 1, costSum: 0, overCapacity: false, people: [], units: [] } as never);
});

test('세 칸 · 머리 띠, 한 칸 실패는 그 칸만(다시 시도), 방침은 시트에서 「이 방침으로」 → 접수 알림', async () => {
    vi.mocked(api.campaignPosts).mockRejectedValueOnce(new Error('500: x'));
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<TerritoryScreen hrefs={hrefs} />);
    expect(await within(screen.getByRole('region', { name: '배치' })).findByText('배치를 불러오지 못했습니다')).toBeInTheDocument();
    expect(await within(screen.getByRole('region', { name: '방침' })).findByText('양성현')).toBeInTheDocument();
    expect(screen.getByText(/금 900/)).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('region', { name: '방침' })).getByRole('button', { name: '바꾸기' }));
    const sheet = await screen.findByRole('region', { name: '양성현 방침' });
    fireEvent.click(within(sheet).getByRole('option', { name: '농업' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 방침으로' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('방침을 접수했습니다'));
    expect(vi.mocked(api.campaignDomestic)).toHaveBeenCalledWith(7, 'policy', { scope: 'COUNTY', countyId: 129, policy: 'FARM' });
});

test('도로 모드의 도로 공사는 지도 고르기 전까지 사유로 막힌다(보내지 않는다)', async () => {
    render(<TerritoryScreen hrefs={hrefs} />);
    fireEvent.click(await within(screen.getByRole('region', { name: '공사' })).findByRole('button', { name: '새 공사' }));
    const sheet = await screen.findByRole('region', { name: '양성현 새 공사' });
    fireEvent.click(within(sheet).getByRole('option', { name: /도로/ }));
    expect(within(sheet).getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');
    expect(sheet).toHaveTextContent('지도에서 접경 · 길목을 고르는 칸이 곧 들어옵니다.');
    expect(vi.mocked(api.campaignDomestic)).not.toHaveBeenCalled();
});

test('모바일 — 배치 · 방침 · 공사 세그먼트', async () => {
    setMobile(true);
    render(<TerritoryScreen hrefs={hrefs} />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(await screen.findByText('배치할 NPC 인물이 없습니다(사람 장수는 발령).')).toBeInTheDocument();
    fireEvent.click(within(seg).getByRole('radio', { name: '공사' }));
    expect(await screen.findByRole('button', { name: '새 공사' })).toBeInTheDocument();
});
