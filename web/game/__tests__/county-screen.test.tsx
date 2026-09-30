import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { CountyScreen } from '../components/county/CountyScreen';
import { api } from '../lib/api';

const push = vi.fn();
let cityHere = 2;
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({ generalId: 7, isCampaignWorld: true,
        frontInfo: { global: {}, general: { name: '하후돈' }, nation: { id: 1, name: '조조', color: '#123456' }, city: { id: cityHere, name: '양적현' } } }),
}));
vi.mock('../lib/api', () => ({
    api: { campaignCounty: vi.fn(), warehouses: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), campaignPosts: vi.fn(), counties: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const hrefs = { records: '/game/pep/records?cityId=2', flow: (i: string, t: string) => `/game/pep?do=${i}&target=${t}` };
beforeEach(() => {
    vi.clearAllMocks();
    cityHere = 2;
    window.matchMedia = ((query: string) => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false })) as unknown as typeof window.matchMedia;
    vi.mocked(api.campaignCounty).mockResolvedValue({ status: 'READY', cityId: 2, name: '양적현', specialties: [{ resource: 'IRON', label: '철', monthly: 0, ledgerMonthly: 12 }] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [{ cityId: 2, name: '양적현', commanderyName: null, isCapital: false, supplied: false, stock: { ...zero, money: 50 } }] } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }], corpsOptions: [], defaultPolicy: null, corps: [],
        counties: [{ countyId: 2, name: '양적현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null }] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [] } as never);
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'READY', cards: [{ cardId: 1, generalId: 101, name: '허저', relation: 'L', provinceId: 'p', placeable: true, blocked: null, active: null, pending: null }],
        posts: [{ post: 'MAGISTRATE', label: '현령', available: true, blocked: null, targets: [{ countyId: 2, name: '양적현', commanderyName: '영천군', occupied: false }] }] } as never);
    vi.mocked(api.counties).mockResolvedValue({ status: 'READY', scope: 'NATION', commandery: null, period: 'GAME_MONTH', basis: 'x', stamp: null,
        counties: [{ cityId: 2, name: '양적현', commanderyId: 'c', visibility: 'FULL', income: null }] } as never);
});

test('우리 현 — 소속 · 고립 · 지금 여기, 특산 0 까닭(끊김), 7지표 준비 중, 현령 앉히기는 이 현을 미리 채운 배치 시트', async () => {
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CountyScreen cityId={2} hrefs={hrefs} />);
    const head = await screen.findByRole('banner');
    await waitFor(() => expect(head).toHaveTextContent('양적현영천군조조고립지금 여기'));
    expect(screen.getByText('이번 달 0 — 수도와 끊긴 현입니다.')).toBeInTheDocument();
    expect(screen.getByText('형편 — 준비 중')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '현령 앉히기' }));
    fireEvent.click(await screen.findByRole('option', { name: '허저' }));
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    expect(within(sheet).getByRole('option', { name: '현령' })).toHaveAttribute('aria-selected', 'true');
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(vi.mocked(api.campaignDomestic)).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'MAGISTRATE', countyId: 2 }));
});

test('남의 현 — 소속 칩을 짐작하지 않고, 이 현에 없으면 직접 행동 대신 한 줄 + 첩보(대상 미리 채움)', async () => {
    cityHere = 99;
    vi.mocked(api.counties).mockResolvedValue({ status: 'READY', scope: 'NATION', commandery: null, period: 'GAME_MONTH', basis: 'x', stamp: null, counties: [] } as never);
    render(<CountyScreen cityId={2} hrefs={hrefs} />);
    const head = await screen.findByRole('banner');
    await waitFor(() => expect(head).not.toHaveTextContent('조조'));
    expect(head).not.toHaveTextContent('무주');
    expect(screen.getByText('내정 · 군사 행동은 내 장수가 이 현에 있을 때만 할 수 있습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '첩보 — 명령 목록에 넣기' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.scout&target=county:2');
});

test('없는 현은 404 모양 · 다른 실패는 다시 시도', async () => {
    vi.mocked(api.campaignCounty).mockRejectedValueOnce(new Error('404: Not Found'));
    const first = render(<CountyScreen cityId={777} hrefs={hrefs} />);
    expect(await screen.findByText('찾는 화면이 없습니다')).toBeInTheDocument();
    first.unmount();
    vi.mocked(api.campaignCounty).mockRejectedValueOnce(new Error('500: x'));
    render(<CountyScreen cityId={2} hrefs={hrefs} />);
    expect(await screen.findByText('현을 불러오지 못했습니다')).toBeInTheDocument();
});
