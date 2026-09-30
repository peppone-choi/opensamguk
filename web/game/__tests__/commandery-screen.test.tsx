import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { beforeEach, expect, test, vi } from 'vitest';
import { CommanderyScreen } from '../components/county/CommanderyScreen';
import { api } from '../lib/api';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 7, isCampaignWorld: true, frontInfo: null }) }));
vi.mock('../lib/api', () => ({
    api: { counties: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const hrefs = { county: (id: number) => `/game/pep/territory/county/${id}`, flow: (i: string, t: string) => `/game/pep?do=${i}&target=${t}` };
const dir = (scope: string) => ({ status: 'READY', scope, commandery: { id: 'c-yc', name: '영천군' }, period: 'GAME_MONTH', basis: 'x', stamp: null,
    counties: [{ cityId: 129, name: '양성현', commanderyId: 'c-yc', visibility: 'FULL', income: { money: 10, grain: 5 } }] });
const policies = (withCommandery: boolean) => ({ status: 'READY', countyOptions: [{ code: 'FARM', label: '농업' }], corpsOptions: [], defaultPolicy: null, corps: [],
    counties: [{ countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null }],
    commanderies: withCommandery ? [{ commanderyId: 'c-yc', name: '영천군', countyIds: [129], active: null, pending: null, settable: true, blocked: null }] : [] });

beforeEach(() => {
    vi.clearAllMocks();
    window.matchMedia = ((query: string) => ({ matches: false, media: query, onchange: null, addListener() {}, removeListener() {},
        addEventListener() {}, removeEventListener() {}, dispatchEvent: () => false })) as unknown as typeof window.matchMedia;
    vi.mocked(api.counties).mockImplementation(async (_g: number, scope: string) => dir(scope) as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [] } as never);
});

test('군주 — 군 방침을 시트에서 걸고, 범위를 우리 세력 전체로 바꾸면 그 범위로 다시 읽는다', async () => {
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies(true) as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<CommanderyScreen commanderyId="c-yc" hrefs={hrefs} />);
    expect(await screen.findByRole('heading', { name: '영천군' })).toBeInTheDocument();
    expect(screen.getByRole('row', { name: /양성현/ })).toHaveTextContent('빈 현령');
    fireEvent.click(await screen.findByRole('button', { name: '군 방침 바꾸기' }));
    const sheet = await screen.findByRole('region', { name: '영천군 방침' });
    fireEvent.click(within(sheet).getByRole('option', { name: '농업' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 방침으로' }));
    await waitFor(() => expect(vi.mocked(api.campaignDomestic)).toHaveBeenCalledWith(7, 'policy', { scope: 'COMMANDERY', commanderyId: 'c-yc', policy: 'FARM' }));
    expect(await screen.findByText(/군 방침을 접수했습니다/)).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '범위' })).getByRole('radio', { name: '우리 세력 전체' }));
    await waitFor(() => expect(vi.mocked(api.counties).mock.calls.at(-1)?.slice(1, 3)).toEqual(['NATION', null]));
});

test('군주가 아니면(서버가 군 방침을 안 줌) 군 방침 단추는 사유로 막힌다, 첩보는 대상 군을 미리 채운다', async () => {
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies(false) as never);
    render(<CommanderyScreen commanderyId="c-yc" hrefs={hrefs} />);
    const btn = await screen.findByRole('button', { name: /군 방침 바꾸기/ });
    expect(btn).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByRole('region', { name: '군 방침' })).toHaveTextContent('군 방침은 군주가 정합니다.');
    fireEvent.click(screen.getByRole('button', { name: '첩보 — 명령 목록에 넣기' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.scout&target=commandery:c-yc');
});
