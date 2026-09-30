import { fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { setViewport } from './helpers/viewport';
import { PersonScreen } from '../components/people/PersonScreen';
import { api } from '../lib/api';

vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({
        generalId: 7, isCampaignWorld: true,
        frontInfo: { global: {}, general: { name: '하후돈', leadership: 80, strength: 70, intel: 60, politics: 50, charm: 40, picture: null, imageServer: 0 },
            nation: { id: 1, name: '조조', color: '#123456' }, city: { id: 2, name: '양적현' } },
    }),
}));
vi.mock('../lib/api', () => ({
    api: { campaignRetinue: vi.fn(), campaignPosts: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const hrefs = { myRetinue: '/game/pep/retinue', records: (id: number) => `/game/pep/records?generalId=${id}`,
    letter: (id: number) => `/game/pep/letters/new?to=${id}`, people: '/game/pep/retinue/people', dispatch: (id: number) => `/game/pep/court?dispatch=${id}` };
beforeEach(() => {
    vi.clearAllMocks();
    setViewport('desktop');
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'READY', renown: 30, costSum: 10, overCapacity: false, units: [], people: [
        { retainerId: 1, generalId: 101, name: '허저', picture: null, imageServer: 0, loyalty: 90, roleLabel: null, taskLabel: null,
            stats: { leadership: 70, strength: 95, intel: 30, politics: 20, charm: 40 }, cost: 12, aptitudes: null, bonds: [], departureOrder: null, locationCityId: null },
    ] } as never);
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'READY', cards: [{ cardId: 1, generalId: 101, name: '허저', relation: 'L', provinceId: 'p', placeable: true,
        blocked: null, active: null, pending: null, isHuman: false }], posts: [{ post: 'NONE', label: '해제', available: true, blocked: null, targets: null }] } as never);
});

test('나 — front-info 로 5능력 · 위치, 내 부로 · 기록 단추, 배치 단추 없음', async () => {
    render(<PersonScreen generalId={7} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '하후돈 인물 카드' });
    expect(hero).toHaveTextContent('조조 소속');
    expect(within(hero).getByRole('link', { name: '내 부로' })).toHaveAttribute('href', '/game/pep/retinue');
    expect(screen.getByRole('group', { name: '능력' })).toHaveTextContent('통솔80');
    expect(screen.getByRole('region', { name: '자리 · 상태' })).toHaveTextContent('양적현');
    expect(screen.queryByRole('button', { name: '자리에 배치' })).toBeNull();
});

test('내 부 인물 — 충성 보임, 배치 시트로 보내면 접수 알림', async () => {
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    const place = await screen.findByRole('button', { name: '자리에 배치' });
    expect(screen.getByRole('region', { name: '자리 · 상태' })).toHaveTextContent('충성90');
    fireEvent.click(place);
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    expect(await screen.findByText(/배치를 접수했습니다/)).toBeInTheDocument();
});

test('내 부 밖 인물 — 짐작해 채우지 않고 서버 대기 + 인물 일람 고리', async () => {
    render(<PersonScreen generalId={555} hrefs={hrefs} />);
    expect(await screen.findByText('이 인물의 카드 — 준비 중')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '인물 일람' })).toHaveAttribute('href', '/game/pep/retinue/people');
});

test('내 부의 사람 장수(K4-18 true) — 배치 단추 대신 「발령은 조정에서 →」', async () => {
    vi.mocked(api.campaignPosts).mockResolvedValue({ status: 'READY', cards: [{ cardId: 1, generalId: 101, name: '허저', relation: 'L', provinceId: 'p', placeable: false,
        blocked: { code: 'HUMAN_CARD', reason: '사람 장수는 조정에서 발령합니다.' }, active: null, pending: null, isHuman: true }], posts: [] } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    expect(await screen.findByRole('link', { name: '발령은 조정에서 →' })).toHaveAttribute('href', '/game/pep/court?dispatch=101');
    expect(screen.queryByRole('button', { name: /자리에 배치/ })).toBeNull();
});
