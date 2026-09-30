import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { rememberProvinceNames } from '@opensamguk/ui';
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
const setMobile = (on: boolean) => setViewport(on ? 'mobile' : 'desktop');

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

test('도로 공사 — 접경 후보(한글 구역 이름, 내부 id 안 보임 · 이름 모름은 빼지 않음), 고르기 전엔 막힘, 고르면 edgeId 를 더해 보낸다', async () => {
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, forts: [], gates: [
        { edgeId: 'edge-yang-xu', fromProvinceId: 'p-yang', toProvinceId: 'p-xu', active: false, buildable: true, historicalRouteIds: ['route-slug'], fortCells: [] },
        { edgeId: 'edge-yang-x', fromProvinceId: 'p-x', toProvinceId: 'p-yang', active: true, buildable: true, historicalRouteIds: [], fortCells: [] },
    ] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: ['p-yang'], name: '양성현', commanderyName: '영천군',
        warehouse: null, active: null, completed: [], startable: [{ work: 'ROAD', label: '도로', available: true, blocked: null, cost: zero, estimatedPhases: 9 }] }] } as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    const names: Record<string, string> = { 'p-yang': '양성 북', 'p-xu': '허현 서' };
    render(<TerritoryScreen hrefs={hrefs} provinceName={(id) => names[id] ?? null} />);
    fireEvent.click(await within(screen.getByRole('region', { name: '공사' })).findByRole('button', { name: '새 공사' }));
    const sheet = await screen.findByRole('region', { name: '양성현 새 공사' });
    fireEvent.click(within(sheet).getByRole('option', { name: /도로/ }));
    const list = within(sheet).getByRole('listbox', { name: '도로를 낼 접경' });
    expect(within(list).getByRole('option', { name: /양성 북 ↔ 허현 서 접경/ })).toBeInTheDocument();
    expect(within(list).getByRole('option', { name: /양성 북 ↔ 이름 모를 구역 접경/ })).toHaveAttribute('aria-disabled', 'true');
    expect(sheet).not.toHaveTextContent('edge-');
    expect(sheet).not.toHaveTextContent('route-slug');
    expect(within(sheet).getByRole('button', { name: '이 공사로' })).toHaveAttribute('aria-disabled', 'true');
    fireEvent.click(within(list).getByRole('option', { name: /허현 서/ }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 공사로' }));
    await waitFor(() => expect(vi.mocked(api.campaignDomestic)).toHaveBeenCalledWith(7, 'work', { countyId: 129, work: 'ROAD', edgeId: 'edge-yang-xu' }));
});

test('모바일 — 배치 · 방침 · 공사 세그먼트', async () => {
    setMobile(true);
    render(<TerritoryScreen hrefs={hrefs} />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(await screen.findByText('배치할 NPC 인물이 없습니다(사람 장수는 발령).')).toBeInTheDocument();
    fireEvent.click(within(seg).getByRole('radio', { name: '공사' }));
    expect(await screen.findByRole('button', { name: '새 공사' })).toBeInTheDocument();
});

test('구역 이름 prop 을 안 넘기면 공용 지도 캐시(useProvinceName)에서 읽는다', async () => {
    rememberProvinceNames({ provinceRecords: [{ id: 'p-yang', displayName: '양성 북' }, { id: 'p-xu', displayName: '허현 서' }] } as never, null);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, forts: [], gates: [
        { edgeId: 'edge-yang-xu', fromProvinceId: 'p-yang', toProvinceId: 'p-xu', active: false, buildable: true, historicalRouteIds: [], fortCells: [] },
    ] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [{ countyId: 129, provinceId: null, provinceIds: ['p-yang'], name: '양성현', commanderyName: '영천군',
        warehouse: null, active: null, completed: [], startable: [{ work: 'ROAD', label: '도로', available: true, blocked: null, cost: zero, estimatedPhases: 9 }] }] } as never);
    render(<TerritoryScreen hrefs={hrefs} />);
    fireEvent.click(await within(screen.getByRole('region', { name: '공사' })).findByRole('button', { name: '새 공사' }));
    const sheet = await screen.findByRole('region', { name: '양성현 새 공사' });
    fireEvent.click(within(sheet).getByRole('option', { name: /도로/ }));
    expect(within(sheet).getByRole('option', { name: /양성 북 ↔ 허현 서 접경/ })).toBeInTheDocument();
});
