import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { SiegeScreen } from '../components/siege/SiegeScreen';
import { api } from '../lib/api';

const nav = vi.hoisted(() => ({ push: vi.fn() }));
// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내. 입력은 router.push 로 명령 흐름을 연다.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/corps/siege', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: nav.push, replace: vi.fn(), back: vi.fn() }) }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({ generalId: 9, frontInfo: { global: { year: 190, month: 2, turnPhase: 1 }, general: { name: '하후돈' }, nation: { id: 1 } } }) }));
vi.mock('../lib/api', () => ({ api: { campaignSieges: vi.fn(), roadForts: vi.fn(), mapPreview: vi.fn() } }));

const siege = {
    countyId: 12, countyName: '초현', status: 'ACTIVE', endReason: null,
    besieger: { generalId: 9, name: '하후돈', nationId: 1, nationName: '조조' },
    defenderNationId: 2, defenderNationName: '원소', startedAt: { year: 190, month: 1, phase: 1 },
    turns: 3, grain: 500, morale: 4200, garrison: 180, trust: 30,
    countySupplied: false, besiegerTroops: 600, besiegerFed: true, canAct: true,
    surrenderDemandAccepted: true,
    timeline: [{ year: 190, month: 1, phase: 1, event: 'START', morale: 5000, garrison: 180 }, { year: 190, month: 1, phase: 2, event: 'NEW_SERVER_EVENT' }],
};
const fort = {
    id: 'land-boundary:gate@12,34', edgeId: 'land-boundary:gate', provinceId: 'p1', row: 12, col: 34,
    ownerNationId: 2, wall: 80, garrison: 30, besiegerGeneralId: null, siegeProgress: 0, canBesiege: true,
};
const hrefs = { flow: (inputId: string) => `/game/pep?do=${inputId}`, stratagem: '/game/pep/stratagem' };
const provinceName = (id: string) => (id === 'p1' ? '호뢰 구역' : null);

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    setMobile(false);
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [siege] } as never);
    vi.mocked(api.roadForts).mockResolvedValue({ status: 'READY', roadMode: true, forts: [], gates: [] } as never);
    vi.mocked(api.mapPreview).mockResolvedValue({ mapCode: 'x', width: 1, height: 1, cities: [], nations: [{ id: 2, name: '원소', color: '#888' }] } as never);
});

test('데스크톱 — 목록 · 형편 6칸 · 기록(모르는 사건 코드는 원문 대신 「공성 사건」), 강공 · 항복 권고는 명령 흐름을 연다', async () => {
    render(<SiegeScreen hrefs={hrefs} provinceName={provinceName} />);
    const state = await screen.findByRole('region', { name: '형편' });
    expect(state).toHaveTextContent('초현');
    expect(state).toHaveTextContent('조조 → 원소 · 포위 3순째 · 강공 가능');
    expect(state).toHaveTextContent('성 안 사기42%');
    expect(state).toHaveTextContent('성 안 쌀500');
    const log = screen.getByRole('list', { name: '포위 기록' });
    expect(log).toHaveTextContent('190년 1월 상순포위 시작 · 사기 50% · 수비 180');
    expect(log).toHaveTextContent('공성 사건');
    expect(document.body).not.toHaveTextContent('NEW_SERVER_EVENT');
    expect(screen.getByRole('region', { name: '항복 권고' })).toHaveTextContent('지금 권하면 받아들입니다.');
    expect(screen.getByRole('region', { name: '함락되면' })).toHaveTextContent('초현 전체가 넘어갑니다(새 주인 조조)');
    fireEvent.click(screen.getByRole('button', { name: '강공 — 순 고르기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.assault');
    fireEvent.click(screen.getByRole('button', { name: '항복 권고 — 순 고르기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.demandSurrender');
    expect(screen.getByRole('link', { name: '계책 덱에서 공성 계책 쓰기 →' })).toHaveAttribute('href', '/game/pep/stratagem');
});

test('포위 1순째 — 강공은 점선 + 서버 사유(ASSAULT_NOT_READY), 항복 권고는 된다 · 지휘관이 아니면 둘 다 사유', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [{ ...siege, turns: 1 }] } as never);
    const view = render(<SiegeScreen hrefs={hrefs} />);
    const commands = await screen.findByRole('region', { name: '명령' });
    expect(screen.getByRole('region', { name: '형편' })).toHaveTextContent('강공까지 2순');
    expect(commands.querySelector('button[data-input-id="action.assault"]')).toHaveAccessibleDescription(/포위한 지 한 달\(3순\)이 지나야 강공할 수 있습니다/);
    fireEvent.click(within(commands).getByRole('button', { name: /강공/ }));
    expect(nav.push).not.toHaveBeenCalled();
    fireEvent.click(within(commands).getByRole('button', { name: '항복 권고 — 순 고르기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.demandSurrender');
    view.unmount();

    nav.push.mockClear();
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [{ ...siege, canAct: false, besieger: { ...siege.besieger, generalId: 3, name: '조인' } }] } as never);
    render(<SiegeScreen hrefs={hrefs} />);
    const other = await screen.findByRole('region', { name: '명령' });
    for (const id of ['action.assault', 'action.demandSurrender']) {
        expect(other.querySelector(`button[data-input-id="${id}"]`)).toHaveAccessibleDescription(/포위 지휘관만 명령할 수 있습니다/);
    }
});

test('끝난 포위 — 목록 칩 「포위 해제」, 고르면 명령 대신 「끝난 포위」', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [siege, { ...siege, countyId: 13, countyName: '패현', status: 'LIFTED', canAct: false }] } as never);
    render(<SiegeScreen hrefs={hrefs} />);
    const list = await screen.findByRole('list', { name: '포위' });
    const lifted = within(list).getByRole('button', { name: /패현/ });
    expect(lifted).toHaveTextContent('포위 해제');
    fireEvent.click(lifted);
    expect(lifted).toHaveAttribute('aria-pressed', 'true');
    expect(await screen.findByRole('region', { name: '끝난 포위' })).toHaveTextContent('포위 해제 — 명령을 넣을 수 없습니다.');
    expect(screen.queryByRole('region', { name: '명령' })).toBeNull();
});

test('도로 보루 — 구역 · 세력 이름(구역 id · 좌표 · 세력 번호 0), 보루 포위는 명령 흐름 · 우리 보루는 단추 없음', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [] } as never);
    vi.mocked(api.roadForts).mockResolvedValueOnce({ status: 'READY', roadMode: true, gates: [],
        forts: [fort, { ...fort, id: 'mine', ownerNationId: 1, canBesiege: false }] } as never);
    render(<SiegeScreen hrefs={hrefs} provinceName={provinceName} />);
    const list = await screen.findByRole('list', { name: '포위' });
    await waitFor(() => expect(within(list).getAllByRole('button')[0]).toHaveTextContent('도로 보루 · 원소'));
    expect(within(list).getAllByRole('button')[0]).toHaveTextContent('보루 — 호뢰 구역');
    expect(within(list).getAllByRole('button')[1]).toHaveTextContent('우리 보루');
    expect(document.body).not.toHaveTextContent('p1');
    expect(document.body).not.toHaveTextContent('12, 34');
    fireEvent.click(screen.getByRole('button', { name: '보루 포위 — 순 고르기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.siegeRoadFort');
    fireEvent.click(within(list).getAllByRole('button')[1]);
    expect(screen.queryByRole('button', { name: '보루 포위 — 순 고르기' })).toBeNull();
});

test('빈 · 오류 — 포위 0이면 출병 흐름, 첫 읽기 실패는 공용 오류 번호만(서버 원문 0)', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValueOnce({ status: 'READY', sieges: [] } as never);
    const view = render(<SiegeScreen hrefs={hrefs} />);
    expect(await screen.findByText('포위 중인 성이 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '출병 — 명령 목록에 넣기' })).toHaveAttribute('href', '/game/pep?do=action.deploy');
    view.unmount();

    vi.mocked(api.campaignSieges).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    render(<SiegeScreen hrefs={hrefs} />);
    expect(await screen.findByText('포위를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 503 복사' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
});

test('포위 0 + 도로 보루 읽기 실패 — 빈 상태(「포위 중인 성이 없습니다」)가 아니라 실패 줄 · 다시 시도(#1205 리뷰)', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [] } as never);
    vi.mocked(api.roadForts).mockRejectedValueOnce(new Error('503: Service Unavailable'))
        .mockResolvedValueOnce({ status: 'READY', roadMode: true, gates: [], forts: [fort] } as never);
    render(<SiegeScreen hrefs={hrefs} provinceName={provinceName} />);
    expect(await screen.findByText('도로 보루를 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('포위 중인 성이 없습니다')).toBeNull();
    expect(screen.queryByRole('link', { name: '출병 — 명령 목록에 넣기' })).toBeNull();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
    fireEvent.click(screen.getByRole('button', { name: '보루 다시 읽기' }));
    expect(await within(await screen.findByRole('list', { name: '포위' })).findByRole('button', { name: /보루 — 호뢰 구역/ })).toBeInTheDocument();
    expect(screen.queryByText('도로 보루를 불러오지 못했습니다.')).toBeNull();
});

test('모바일 · 포위 0 + 도로 보루 읽기 실패 — 목록 화면에 실패 줄, 보루 수는 「?」', async () => {
    setMobile(true);
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [] } as never);
    vi.mocked(api.roadForts).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    render(<SiegeScreen hrefs={hrefs} />);
    expect(await screen.findByText('도로 보루를 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.getByText('포위 0곳 · 보루 ?')).toBeInTheDocument();
    expect(screen.queryByText('포위 중인 성이 없습니다')).toBeNull();
    expect(screen.getByRole('button', { name: '보루 다시 읽기' })).toBeInTheDocument();
});

test('모바일 — 목록 카드 → 상세(형편 · 기록) + 아래 단추 줄, 「← 포위 목록」으로 돌아온다', async () => {
    setMobile(true);
    vi.mocked(api.roadForts).mockResolvedValueOnce({ status: 'READY', roadMode: true, gates: [], forts: [fort] } as never);
    render(<SiegeScreen hrefs={hrefs} provinceName={provinceName} />);
    const list = await screen.findByRole('list', { name: '포위' });
    fireEvent.click(within(list).getByRole('button', { name: /초현/ }));
    expect(await screen.findByRole('heading', { name: '초현' })).toBeInTheDocument();
    expect(screen.getByText('성 안 수비')).toBeInTheDocument();
    fireEvent.click(within(screen.getByRole('radiogroup', { name: '보기' })).getByRole('radio', { name: '기록' }));
    expect(screen.getByRole('list', { name: '포위 기록' })).toHaveTextContent('포위 시작');
    fireEvent.click(screen.getByRole('button', { name: '강공' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.assault');
    fireEvent.click(screen.getByRole('button', { name: '← 포위 목록' }));
    expect(await screen.findByRole('list', { name: '포위' })).toBeInTheDocument();
});

test('주소 ?county= — 그 포위를 처음부터 고른다(모바일은 바로 상세), 없는 현이면 목록 그대로', async () => {
    vi.mocked(api.campaignSieges).mockResolvedValue({ status: 'READY', sieges: [siege, { ...siege, countyId: 13, countyName: '패현', status: 'LIFTED', canAct: false }] } as never);
    const view = render(<SiegeScreen hrefs={hrefs} initialCounty={13} />);
    expect(await screen.findByRole('region', { name: '형편' })).toHaveTextContent('패현');
    view.unmount();
    setMobile(true);
    const again = render(<SiegeScreen hrefs={hrefs} initialCounty={13} />);
    expect(await screen.findByRole('heading', { name: '패현' })).toBeInTheDocument();
    again.unmount();
    render(<SiegeScreen hrefs={hrefs} initialCounty={999} />);
    expect(await screen.findByRole('list', { name: '포위' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '← 포위 목록' })).toBeNull();
});
