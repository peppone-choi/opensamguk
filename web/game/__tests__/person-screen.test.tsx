import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { PersonScreen } from '../components/person/PersonScreen';
import { api } from '../lib/api';

const push = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push }), usePathname: () => '/game/pep/retinue/people/7' }));
// 도움말 고리(HelpedInputAction)는 도움말 서랍 없이도 그린다 — 여기서는 단추 · 사유만 본다.
vi.mock('../components/campaign/HelpedInputAction', async () => {
    const { InputAction } = await vi.importActual<typeof import('@opensamguk/ui')>('@opensamguk/ui');
    return { HelpedInputAction: InputAction };
});
const general = { generalId: 7, name: '하후돈', leadership: 80, strength: 85, intel: 50, politics: 40, charm: 60, injury: 0, picture: null, imageServer: 0 };
const sessionState = vi.hoisted(() => ({ noGeneral: false }));
vi.mock('../lib/campaign-session', () => ({
    useGameSession: () => (sessionState.noGeneral ? { generalId: null, loading: false, serverId: 'pep', frontInfo: null } : {
        generalId: 7, loading: false, serverId: 'pep',
        frontInfo: { global: { year: 200, month: 3, turnPhase: 2 }, general, nation: { id: 1, name: '조조', color: '#4f7fbf' }, city: { id: 3, name: '양적현' } },
    }),
}));
vi.mock('../lib/api', () => ({
    api: { campaignRetinue: vi.fn(), campaignPosts: vi.fn(), campaignDomestic: vi.fn(), personDetail: vi.fn() },
    isIntakeQueued: (o: { status: string }) => o.status === 'AVAILABLE',
    isIntakeDenied: (o: { status: string }) => o.status === 'BLOCKED' || o.status === 'UNKNOWN',
}));

const person = (retainerId: number, name: string, over: Record<string, unknown> = {}) => ({
    retainerId, generalId: 100 + retainerId, name, picture: null, imageServer: 0, loyalty: 85, roleLabel: null, taskLabel: null,
    stats: { leadership: 70, strength: 90, intel: 30, politics: 20, charm: 40 }, cost: 12,
    aptitudes: { command: 60, administration: 20, strategy: 10, envoy: 15 },
    bonds: [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true }], departureOrder: null, ...over,
});
const retinue = (people: unknown[]) => ({ status: 'READY', renown: 30, costSum: 20, overCapacity: false, people, units: [] });
const card = (cardId: number, name: string, isHuman: boolean) =>
    ({ cardId, generalId: 100 + cardId, name, relation: 'L', provinceId: 'p-1', placeable: true, blocked: null, active: null, pending: null, isHuman });
const posts = (cards: unknown[]) => ({ status: 'READY', cards, posts: [{ post: 'NONE', label: '해제', available: true, blocked: null, targets: null }] });
const hrefs = {
    people: '/game/pep/retinue/people',
    retinue: (id?: number) => (id != null ? `/game/pep/retinue?person=${id}` : '/game/pep/retinue'),
    dispatch: (id: number) => `/game/pep/court?dispatch=${id}`,
};

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks();
    sessionState.noGeneral = false;
    setMobile(false);
    vi.mocked(api.campaignRetinue).mockResolvedValue(retinue([person(1, '허저'), person(2, '순욱', { loyalty: 40 })]) as never);
    vi.mocked(api.campaignPosts).mockResolvedValue(posts([card(1, '허저', false), card(2, '순욱', true)]) as never);
    // 인물 상세(K4-13)는 늘 부른다 — 서버 경로가 아직 없으면 404(D124 미리 짓기). 위 시험들은 이 상태의 지금 동작이다.
    vi.mocked(api.personDetail).mockRejectedValue(new Error('404: Not Found'));
});

test('주소의 번호가 올바르지 않으면 「이 인물을 찾을 수 없습니다」 + 인물 일람으로', () => {
    render(<PersonScreen generalId={null} hrefs={hrefs} />);
    expect(screen.getByText('이 인물을 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '인물 일람으로' })).toHaveAttribute('href', '/game/pep/retinue/people');
});

test('나 — front-info 로 히어로 · 5능력, 적성 · 결속 · 계책 기여 · 관직 카드는 서버 대기, 「내 부로」, 부 읽기는 부르지 않는다', async () => {
    render(<PersonScreen generalId={7} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '하후돈 인물 카드' });
    expect(within(hero).getByText('나')).toBeInTheDocument();
    expect(within(hero).getByText('조조 소속')).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: '내 부로' })).toHaveAttribute('href', '/game/pep/retinue');
    expect(screen.getByRole('region', { name: '능력' })).toHaveTextContent('85');
    expect(screen.getByRole('region', { name: '역할 적성' })).toHaveTextContent('적성 — 서버 대기');
    expect(screen.getByRole('region', { name: '계책 기여' })).toHaveTextContent('계책 기여 — 준비 중');
    expect(screen.getByRole('region', { name: '인물 관직 카드' })).toHaveTextContent('관직 카드 — 준비 중');
    expect(screen.getByRole('region', { name: '자리 · 상태' })).toHaveTextContent('양적현');
    expect(api.campaignRetinue).not.toHaveBeenCalled();
});

test('내 부 NPC — 충성 · 코스트 · 결속, 「자리에 배치」는 이 화면의 배치 시트, 접수하면 알림 후 다시 읽는다', async () => {
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'AVAILABLE' } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '허저 인물 카드' });
    expect(within(hero).getByText('충성 85')).toBeInTheDocument();
    expect(within(hero).getByText('코스트 12')).toBeInTheDocument();
    expect(within(hero).getByText('NPC')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '결속' })).toHaveTextContent('향당 · 패국 초현');
    expect(screen.getByRole('region', { name: '결속' })).toHaveTextContent('주공과 같은 고향');
    expect(within(hero).getByRole('link', { name: '부 편성에서 보기' })).toHaveAttribute('href', '/game/pep/retinue?person=1');
    fireEvent.click(within(hero).getByRole('button', { name: '자리에 배치' }));
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
    // 접수 한 줄로 찾는다 — 서버 대기 칸(StatusView)도 status 라 「status 하나」로 고르면 여럿에 걸린다(#1265 CI).
    expect(await screen.findByText(/^배치를 접수했습니다/)).toHaveAttribute('role', 'status');
    expect(api.campaignDomestic).toHaveBeenCalledWith(7, 'placement', { cardId: 1, post: 'NONE' });
    await waitFor(() => expect(api.campaignRetinue).toHaveBeenCalledTimes(2));
    expect(push).not.toHaveBeenCalled();
});

test('내 부 사람 장수 — 배치 대신 「발령은 조정에서」', async () => {
    render(<PersonScreen generalId={102} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '순욱 인물 카드' });
    expect(within(hero).getByText('사람')).toBeInTheDocument();
    expect(within(hero).getByRole('link', { name: '발령은 조정에서 →' })).toHaveAttribute('href', '/game/pep/court?dispatch=102');
    expect(within(hero).queryByRole('button', { name: '자리에 배치' })).toBeNull();
});

test('나도 내 부도 아니면 — 이름을 짓지 않고 「이 인물의 상세는 아직 볼 수 없습니다」(인물 상세 읽기 서버 대기)', async () => {
    render(<PersonScreen generalId={555} hrefs={hrefs} />);
    expect(await screen.findByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '인물 일람으로' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '능력' })).toBeNull();
});

test('부 읽기 실패 — 「없음」으로 단정하지 않고 오류 + 다시 시도', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    expect(await screen.findByText('인물을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('region', { name: '허저 인물 카드' })).toBeInTheDocument();
});

test('모바일 — 히어로 · 이름 · 칸 세로, 배치 시트는 아래', async () => {
    setMobile(true);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    expect(await screen.findByRole('heading', { name: '허저' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '허저 인물 카드' })).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: '자리에 배치' }));
    expect(await screen.findByRole('region', { name: '허저 배치' })).toBeInTheDocument();
});

test('장수가 없는 세션 — 부 읽기를 부르지 않으니 뼈대에 머물지 않고 「아직 볼 수 없습니다」(#1265 리뷰)', async () => {
    sessionState.noGeneral = true;
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    expect(await screen.findByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeInTheDocument();
});

// ── 특성 시험(D107 ② 읽기 · 접수를 훅으로 옮기기 전) — 화면이 api 를 직접 부르던 때의 동작을 그대로 고정한다 ──
const openPlacement = async () => {
    const hero = await screen.findByRole('region', { name: '허저 인물 카드' });
    fireEvent.click(within(hero).getByRole('button', { name: '자리에 배치' }));
    const sheet = await screen.findByRole('region', { name: '허저 배치' });
    fireEvent.click(within(sheet).getByRole('option', { name: '자리에서 풀기' }));
    fireEvent.click(within(sheet).getByRole('button', { name: '이 자리로' }));
};

test('배치 거절 — 서버 사유를 한 줄로, 시트는 열어 두고 다시 읽지 않는다', async () => {
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'BLOCKED', reason: '이미 다른 자리에 있습니다' } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    await openPlacement();
    expect(await screen.findByText('이미 다른 자리에 있습니다')).toHaveAttribute('role', 'status');
    expect(screen.getByRole('region', { name: '허저 배치' })).toBeInTheDocument();
    expect(api.campaignRetinue).toHaveBeenCalledTimes(1);
});

test('배치 거절인데 사유가 비었으면 — 「배치를 받지 못했습니다.」', async () => {
    vi.mocked(api.campaignDomestic).mockResolvedValue({ status: 'BLOCKED', reason: '  ' } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    await openPlacement();
    expect(await screen.findByText('배치를 받지 못했습니다.')).toHaveAttribute('role', 'status');
});

test('배치 전송 실패 — 「다시 해 보세요」 한 줄, 시트는 그대로, 단추가 다시 눌린다', async () => {
    vi.mocked(api.campaignDomestic).mockRejectedValue(new Error('502: Bad Gateway'));
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    await openPlacement();
    expect(await screen.findByText('배치를 보내지 못했습니다 — 다시 해 보세요.')).toHaveAttribute('role', 'status');
    const sheet = screen.getByRole('region', { name: '허저 배치' });
    await waitFor(() => expect(within(sheet).getByRole('button', { name: '이 자리로' })).not.toHaveAttribute('aria-busy'));
    expect(api.campaignRetinue).toHaveBeenCalledTimes(1);
});

test('배치 자리 읽기 실패 — 사람 · NPC 판정이 posts 카드에서 오므로 모른다: 배치 · 발령 단추 없이 판정 대기 줄(지금 동작 그대로)', async () => {
    vi.mocked(api.campaignPosts).mockRejectedValue(new Error('500: boom'));
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '허저 인물 카드' });
    await waitFor(() => expect(api.campaignPosts).toHaveBeenCalled());
    expect(await within(hero).findByText(/^사람 장수는 조정에서 발령합니다/)).toHaveAttribute('data-waiting', 'human-flag');
    expect(within(hero).queryByRole('button', { name: '자리에 배치' })).toBeNull();
    expect(within(hero).queryByRole('link', { name: '발령은 조정에서 →' })).toBeNull();
    expect(within(hero).getByRole('link', { name: '부 편성에서 보기' })).toBeInTheDocument();
});

test('부 읽기가 READY 가 아니면 — 그 상태의 쉬운 말로 「아직 볼 수 없습니다」', async () => {
    vi.mocked(api.campaignRetinue).mockResolvedValue({ status: 'UNAVAILABLE' } as never);
    render(<PersonScreen generalId={101} hrefs={hrefs} />);
    expect(await screen.findByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeInTheDocument();
    expect(screen.getByText('저장된 값을 읽을 수 없습니다.')).toBeInTheDocument();
});

test('나 — 배치 자리 읽기도 부르지 않는다', async () => {
    render(<PersonScreen generalId={7} hrefs={hrefs} />);
    await screen.findByRole('region', { name: '하후돈 인물 카드' });
    expect(api.campaignPosts).not.toHaveBeenCalled();
    expect(api.campaignDomestic).not.toHaveBeenCalled();
});

// ── 인물 상세 읽기(K4-13, D124 미리 짓기) ──
const other = {
    status: 'READY', relation: 'OTHER', generalId: 555, name: '안량', portrait: { picture: null, imageServer: 0 },
    affiliation: { nationId: 2, name: '원소', color: '#9c4a3f' },
    stats: { leadership: 80, strength: 92, intel: 30, politics: 25, charm: 40 }, aptitudes: { command: 70, administration: 10, strategy: 15, envoy: 20 },
    role: null, lordGeneralId: null, location: null, bonds: null, injured: null, retinue: null,
    unavailableReasons: { '/location': 'NOT_AUTHORIZED', '/bonds': 'NOT_AUTHORIZED', '/injured': 'NOT_AUTHORIZED' },
};

test('인물 상세 서버가 주면 — 다른 세력 인물도 이름 · 소속 · 능력 · 적성, 사적인 칸은 「내 부 인물만」, 배치 · 발령 단추 없음', async () => {
    vi.mocked(api.personDetail).mockResolvedValue(other as never);
    render(<PersonScreen generalId={555} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '안량 인물 카드' });
    expect(api.personDetail).toHaveBeenCalledWith(7, 555, expect.anything());
    expect(within(hero).getByText('다른 세력')).toBeInTheDocument();
    expect(within(hero).getByText('원소 소속')).toBeInTheDocument();
    expect(within(hero).queryByText('적')).toBeNull();
    expect(screen.getByRole('region', { name: '능력' })).toHaveTextContent('92');
    expect(screen.getByRole('region', { name: '역할 적성' })).not.toHaveTextContent('서버 대기');
    expect(screen.getByRole('region', { name: '결속' })).toHaveTextContent('결속은 내 장수 · 내 부 인물만 보입니다.');
    expect(screen.getByRole('region', { name: '자리 · 상태' })).toHaveTextContent('내 부 인물만');
    expect(within(hero).queryByRole('button', { name: '자리에 배치' })).toBeNull();
    expect(within(hero).queryByRole('link', { name: '발령은 조정에서 →' })).toBeNull();
    expect(screen.queryByText('이 인물의 상세는 아직 볼 수 없습니다')).toBeNull();
});

test('부 읽기가 실패해도 인물 상세가 오면 — 오류 대신 상세로 그린다', async () => {
    vi.mocked(api.campaignRetinue).mockRejectedValue(new Error('503: Service Unavailable'));
    vi.mocked(api.personDetail).mockResolvedValue({ ...other, relation: 'SAME_NATION', affiliation: { nationId: 1, name: '조조', color: '#4f7fbf' } } as never);
    render(<PersonScreen generalId={555} hrefs={hrefs} />);
    const hero = await screen.findByRole('region', { name: '안량 인물 카드' });
    expect(within(hero).getByText('같은 세력')).toBeInTheDocument();
    expect(screen.queryByText('인물을 불러오지 못했습니다')).toBeNull();
});

test('나 — 인물 상세(SELF)가 적성 · 결속을 채운다(서버 대기가 사라진다)', async () => {
    vi.mocked(api.personDetail).mockResolvedValue({
        ...other, relation: 'SELF', generalId: 7, name: '하후돈', affiliation: { nationId: 1, name: '조조', color: '#4f7fbf' },
        bonds: [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: false }], unavailableReasons: {},
    } as never);
    render(<PersonScreen generalId={7} hrefs={hrefs} />);
    await screen.findByRole('region', { name: '하후돈 인물 카드' });
    await waitFor(() => expect(screen.getByRole('region', { name: '역할 적성' })).not.toHaveTextContent('적성 — 서버 대기'));
    expect(screen.getByRole('region', { name: '결속' })).toHaveTextContent('향당 · 패국 초현');
    expect(api.campaignRetinue).not.toHaveBeenCalled();
});
