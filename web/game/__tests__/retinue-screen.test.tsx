import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { RetinueScreen } from '../components/retinue/RetinueScreen';
import { api } from '../lib/api';

vi.mock('../lib/api/retinue-hierarchy', async (load) => ({ ...await load<object>(), readRetinueHierarchy: vi.fn(async () => hierarchyFixture(true)) }));
import { hierarchyFixture } from './fixtures/retinue-hierarchy';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }) }));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => ({
        generalId: 7, isCampaignWorld: true, serverId: 'pep',
        frontInfo: { global: { year: 200, month: 3, turnPhase: 2 }, general: { name: '하후돈' } },
    }),
}));
vi.mock('../lib/api', () => ({
    api: { campaignRetinue: vi.fn(), campaignPosts: vi.fn(), campaignYuedan: vi.fn(), campaignDomestic: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const person = (retainerId: number, name: string, over: Record<string, unknown> = {}) => ({
    retainerId, generalId: 100 + retainerId, name, picture: null, imageServer: 0, loyalty: 70, roleLabel: null, taskLabel: null,
    stats: null, cost: 10, aptitudes: null, bonds: [], departureOrder: null, locationCityId: null, ...over,
});
const retinue = (people: unknown[]) => ({ status: 'READY', renown: 30, costSum: 20, overCapacity: false, people, units: [] });
const posts = {
    status: 'READY',
    cards: [{ cardId: 1, generalId: 101, name: '허저', relation: 'L', provinceId: 'p-1', placeable: true, blocked: null, active: null, pending: null, isHuman: false }],
    posts: [{ post: 'NONE', label: '해제', available: true, blocked: null, targets: null }],
};
const hrefs = { yuedan: '/game/pep/retinue/yuedan', dispatch: (id: number) => `/game/pep/court?dispatch=${id}`,
    person: (id: number) => `/game/pep/retinue/people/${id}`, flow: (i: string) => `/game/pep?do=${i}` };

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };

afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignPosts).mockResolvedValue(posts as never);
    vi.mocked(api.campaignYuedan).mockResolvedValue({ status: 'READY', stamp: null, self: null, ranking: [] } as never);
});

test('데스크톱 — 세 칸, 배치 시트에서 보내면 접수 알림 후 다시 읽는다, 조회는 부 · 배치 · 월단평 한 번씩', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<RetinueScreen hrefs={hrefs} />);
    await screen.findByRole('region', { name: '고른 인물' });
    expect(vi.mocked(api.campaignRetinue)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(api.campaignYuedan)).toHaveBeenCalledTimes(1);
    fireEvent.click(within(screen.getByRole('region', { name: '고른 인물' })).getByRole('button', { name: '자리에 배치' }));
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('배치를 접수했습니다'));
    expect(vi.mocked(api.campaignDomestic)).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'NONE' });
    await waitFor(() => expect(vi.mocked(api.campaignRetinue)).toHaveBeenCalledTimes(2));
});

test('거절이면 서버 사유를 그대로 한 줄로', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'BLOCKED', reason: '출전 중인 카드입니다.' } as never);
    render(<RetinueScreen hrefs={hrefs} />);
    fireEvent.click(await screen.findByRole('button', { name: '자리에 배치' }));
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('출전 중인 카드입니다.'));
});

test('인물 0 — 빈 상태에 인재탐색 · 등용, 누르면 명령 흐름으로', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([]) as never);
    render(<RetinueScreen hrefs={hrefs} />);
    expect(await screen.findByText('아직 거느린 인물이 없습니다')).toBeInTheDocument();
    expect(await screen.findByText('아직 직속 장수가 없습니다.')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '부 조직도' })).toHaveTextContent('가상 상관');
    fireEvent.click(screen.getByRole('button', { name: '인재탐색' }));
    expect(push).toHaveBeenCalledWith('/game/pep?do=action.search');
});

test('실패는 빈 것과 다른 모양 — 다시 시도', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValueOnce(new Error('403: Forbidden')).mockResolvedValue(retinue([person(1, '허저')]) as never);
    render(<RetinueScreen hrefs={hrefs} />);
    expect(await screen.findByText('부를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('아직 거느린 인물이 없습니다')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await screen.findByRole('region', { name: '고른 인물' });
});

test.each([false, true])('503 실패 — 안내와 복사할 오류 번호를 나누고 재시도하면 지운다 (mobile=%s)', async (mobile) => {
    setMobile(mobile);
    userEvent.setup();
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue(undefined);
    vi.mocked(api.campaignRetinue).mockRejectedValueOnce(new Error('503: Service Unavailable')).mockResolvedValue(retinue([person(1, '허저')]) as never);
    render(<RetinueScreen hrefs={hrefs} />);
    const alert = await screen.findByRole('alert');
    expect(alert.querySelector('.os-status__body')).toHaveTextContent('서버가 잠시 응답하지 않습니다. 잠시 뒤 다시 해 보세요.');
    expect(alert).not.toHaveTextContent('Service Unavailable');
    fireEvent.click(within(alert).getByRole('button', { name: '오류 번호 503 복사' }));
    expect(writeText).toHaveBeenCalledWith('503');
    fireEvent.click(within(alert).getByRole('button', { name: '다시 시도' }));
    await screen.findByRole(mobile ? 'radiogroup' : 'region', { name: mobile ? '보기' : '고른 인물' });
    expect(screen.queryByRole('button', { name: /오류 번호/ })).toBeNull();
});

test('네트워크 실패 — HTTP 오류 번호를 만들지 않는다', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<RetinueScreen hrefs={hrefs} />);
    const alert = await screen.findByRole('alert');
    expect(alert.querySelector('.os-status__body')).toHaveTextContent('서버에 닿지 않습니다.');
    expect(within(alert).queryByRole('button', { name: /오류 번호/ })).toBeNull();
});

test('모바일 — 인물 · 부대 · 결속 세그먼트, 인물을 누르면 인물 상세로', async () => {
    setMobile(true);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    render(<RetinueScreen hrefs={hrefs} />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(within(seg).getAllByRole('radio').map((r) => r.textContent)).toEqual(['인물1', '부대0', '결속']);
    fireEvent.click(screen.getByRole('option', { name: /허저/ }));
    expect(push).toHaveBeenCalledWith('/game/pep/retinue/people/101');
    expect(screen.queryByRole('region', { name: '고른 인물' })).toBeNull();
});

test('인물 상세 화면(P-R03)이 아직 없으면 — 장수 카드도 이 화면 안 카드로 열고, 「인물 상세」 링크는 없다', async () => {
    setMobile(true);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    const { person: _omit, ...noPerson } = hrefs;
    render(<RetinueScreen hrefs={noPerson} />);
    fireEvent.click(await screen.findByRole('option', { name: /허저/ }));
    expect(push).not.toHaveBeenCalled();
    const card = await screen.findByRole('dialog', { name: '허저 인물 카드' });
    expect(within(card).queryByRole('link', { name: '인물 상세' })).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
});

test('작전실 장수 목록의 ?person= — 그 인물을 처음부터 고른다', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저'), person(2, '전위')]) as never);
    render(<RetinueScreen hrefs={hrefs} initialPerson={2} />);
    const detail = await screen.findByRole('region', { name: '고른 인물' });
    await waitFor(() => expect(detail).toHaveTextContent('전위'));
});

test('모바일 · 인물 상세가 있어도 주소(?person=)로 연 인물은 이 화면 카드 시트로 연다 — 거기서 배치(작전실 · 인물 상세 「부 편성에서 보기」)', async () => {
    setMobile(true);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저'), person(2, '전위')]) as never);
    render(<RetinueScreen hrefs={hrefs} initialPerson={1} />);
    const card = await screen.findByRole('dialog', { name: '허저 인물 카드' });
    expect(within(card).getByRole('button', { name: '자리에 배치' })).toBeInTheDocument();
    fireEvent.click(within(card).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    // 목록에서 다른 장수를 누르면 인물 상세로
    fireEvent.click(screen.getByRole('option', { name: /전위/ }));
    expect(push).toHaveBeenCalledWith('/game/pep/retinue/people/102');
});

test('모바일 배치 — 인물 카드 시트에서 배치하면 접수 한 줄이 보이고 카드 시트는 다시 열리지 않는다', async () => {
    setMobile(true);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    const { person: _omit, ...noPerson } = hrefs;
    render(<RetinueScreen hrefs={noPerson} initialPerson={1} />);
    const card = await screen.findByRole('dialog', { name: '허저 인물 카드' });
    fireEvent.click(within(card).getByRole('button', { name: '자리에 배치' }));
    const sheet = await screen.findByRole('dialog', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('배치를 접수했습니다'));
    expect(screen.queryByRole('dialog')).toBeNull();
});

test('배치 자리 읽기 실패 — 「자리에 배치」는 가능으로 그리지 않고(사유), 다시 시도하면 시트를 열 수 있다', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    vi.mocked(api.campaignPosts).mockRejectedValueOnce(new TypeError('Failed to fetch'));
    render(<RetinueScreen hrefs={hrefs} />);
    const detail = await screen.findByRole('region', { name: '고른 인물' });
    const assign = await within(detail).findByRole('button', { name: /자리에 배치/ });
    await waitFor(() => expect(assign).toHaveAttribute('aria-disabled', 'true'));
    expect(detail).toHaveTextContent('배치 자리를 불러오지 못했습니다.');
    expect(document.body).not.toHaveTextContent('Failed to fetch');
    fireEvent.click(assign);
    expect(screen.queryByRole('region', { name: '허저 배치' })).toBeNull();
    vi.mocked(api.campaignPosts).mockResolvedValue(posts as never);
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    await waitFor(() => expect(within(screen.getByRole('region', { name: '고른 인물' })).getByRole('button', { name: '자리에 배치' })).not.toHaveAttribute('aria-disabled', 'true'));
    fireEvent.click(within(screen.getByRole('region', { name: '고른 인물' })).getByRole('button', { name: '자리에 배치' }));
    expect(await screen.findByRole('region', { name: '허저 배치' })).toBeInTheDocument();
});

test('배치 자리를 읽는 중 — 「자리에 배치」를 눌러도 아무 일 없고, 읽은 뒤 시트가 저절로 열리지 않는다', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    let resolvePosts: (v: unknown) => void = () => {};
    vi.mocked(api.campaignPosts).mockReturnValueOnce(new Promise((r) => { resolvePosts = r; }) as never);
    render(<RetinueScreen hrefs={hrefs} />);
    const detail = await screen.findByRole('region', { name: '고른 인물' });
    const assign = await within(detail).findByRole('button', { name: /자리에 배치/ });
    expect(assign).toHaveAttribute('aria-busy', 'true');
    fireEvent.click(assign);
    resolvePosts(posts);
    await waitFor(() => expect(within(screen.getByRole('region', { name: '고른 인물' })).getByRole('button', { name: '자리에 배치' })).not.toHaveAttribute('aria-busy'));
    expect(screen.queryByRole('region', { name: '허저 배치' })).toBeNull();
});

test('모바일 ?person= 이 부에 없는 인물이면 — 다른 인물 카드를 열지 않는다', async () => {
    setMobile(true);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저')]) as never);
    const { person: _omit, ...noPerson } = hrefs;
    render(<RetinueScreen hrefs={noPerson} initialPerson={999} />);
    expect(await screen.findByRole('option', { name: /허저/ })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).toBeNull();
});
