import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { CommanderyScreen } from '../components/commandery/CommanderyScreen';
import { api } from '../lib/api';

// 도움말 고리(HelpedInputAction)는 도움말 서랍 없이도 그린다 — 여기서는 단추 · 사유만 본다.
vi.mock('../components/campaign/HelpedInputAction', async () => {
    const { InputAction } = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
    return { HelpedInputAction: InputAction };
});

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

let viewport: ReturnType<typeof installViewport> | null = null;
const setViewport = (kind: 'mobile' | 'desktop') => { viewport?.restore(); viewport = installViewport(kind === 'mobile' ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    setViewport('desktop');
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

test('방침 조회 실패 — 군주에게 「군주가 정합니다」로 단정하지 않고 실패 한 줄 + 다시 시도, 요약은 「?곳」(#1274 리뷰)', async () => {
    vi.mocked(api.campaignPolicies).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    vi.mocked(api.warehouses).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    render(<CommanderyScreen commanderyId="c-yc" hrefs={hrefs} />);
    const card = await screen.findByRole('region', { name: '군 방침' });
    expect(await within(card).findByText('군 방침을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(card).not.toHaveTextContent('군주가 정합니다');
    const summary = screen.getByRole('list', { name: '군 요약' });
    expect(summary).toHaveTextContent('빈 현령 ?곳 — 불러오지 못했습니다');
    expect(summary).toHaveTextContent('고립 ?곳 — 불러오지 못했습니다');
    expect(summary).not.toHaveTextContent('빈 현령 0곳');
    expect(screen.getByRole('row', { name: /양성현/ })).toHaveTextContent('?');
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies(true) as never);
    fireEvent.click(within(card).getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('button', { name: '군 방침 바꾸기' })).toBeInTheDocument();
});

test('현 목록 PARTIAL — 「일부 값을 읽지 못했습니다」 한 줄(#1274 리뷰)', async () => {
    vi.mocked(api.campaignPolicies).mockResolvedValue(policies(true) as never);
    vi.mocked(api.counties).mockResolvedValue({ ...dir('COMMANDERY'), status: 'PARTIAL' } as never);
    render(<CommanderyScreen commanderyId="c-yc" hrefs={hrefs} />);
    expect(await screen.findByRole('note')).toHaveTextContent('일부 값을 읽지 못했습니다');
});
