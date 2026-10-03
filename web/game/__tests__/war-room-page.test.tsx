import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import WarRoomPage from '../components/campaign/WarRoomPage';
import { api } from '../lib/api';

const nav = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), search: '' }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game', useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => ({ push: nav.push, replace: nav.replace, back: vi.fn() }),
}));
// 셸 · 지도 · 흐름은 각자 시험이 있다 — 여기서는 작전실 틀(배치 · 넘기는 값)만 본다.
vi.mock('../components/GameShell', () => ({ default: ({ children }: { children: ReactNode }) => <div>{children}</div> }));
vi.mock('../components/campaign/WarRoomMap', () => ({
    default: (props: { fill?: boolean; myLocationInset?: unknown }) => (
        <div data-testid="war-map" data-fill={String(props.fill)} data-inset={JSON.stringify(props.myLocationInset ?? null)} />
    ),
}));
vi.mock('../components/command-flow/CommandFlow', () => ({ default: () => <div data-testid="command-flow" /> }));
vi.mock('../lib/turn-slots', async () => {
    const actual = await vi.importActual<typeof import('../lib/turn-slots')>('../lib/turn-slots');
    const slot = (turnIdx: number, state: 'empty' | 'reserved', name: string | null) =>
        ({ turnIdx, state, inputId: name ? 'action.train' : null, name, summary: null, when: '3월 하순', at: '22:40', blockedCode: null, markers: [] });
    return { ...actual, useTurnSlots: () => ({ load: { state: 'ready', slots: [slot(0, 'reserved', '훈련'), ...Array.from({ length: 11 }, (_, i) => slot(i + 1, 'empty', null))] }, reload: vi.fn() }) };
});
// 장수는 있는데 crew(옛 삼모 장수 병력)는 front-info 에 없다 — 옛 작전실 명부가 「병력 NaN」을 그리던 고정 자료.
const frontInfo = {
    global: { year: 200, month: 3, turnPhase: 2 },
    general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, picture: null, imageServer: 0 },
    nation: { id: 1, name: '조조', color: '#4f7fbf' },
    city: { id: 3, name: '양성현', level: 2, nationId: 1, region: 0 },
    recentRecord: {},
};
// 세션 — 시험마다 바꾼다(불러오는 중 · 실패 · 장수 없음).
const session = vi.hoisted(() => ({ state: null as null | Record<string, unknown>, refresh: vi.fn() }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => session.state }));
vi.mock('../lib/api', () => {
    const fail = () => vi.fn(async () => { throw new Error('503: Service Unavailable'); });
    return { api: {
        campaignVisibility: fail(), campaignCorps: fail(), campaignSieges: fail(), campaignWorks: fail(), campaignScoutOptions: fail(),
        campaignLastTurns: fail(), deployOptions: fail(), dispatchPending: fail(), stratagemHand: fail(), campaignCounty: fail(),
        campaignRetinue: fail(), campaignYuedan: fail(), reservedCommands: fail(), mailbox: fail(), generalsList: fail(), commands: fail(),
    } };
});

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks();
    nav.search = '';
    setMobile(false);
    session.state = { generalId: 7, frontInfo, loading: false, error: null, serverId: undefined, refresh: session.refresh };
});

test('읽기 실패 고정 자료 — 화면 어디에도 「NaN」이 없다(옛 장수 카드 「병력 NaN」)', async () => {
    render(<WarRoomPage />);
    expect(await screen.findByTestId('war-map')).toBeInTheDocument();
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalled());
    expect(document.body).not.toHaveTextContent('NaN');
});

test('데스크톱 — 지도가 상자를 채우고 오른쪽 12순 열 · 맡겨 둔 일 · 「이번 순에 할 일 — 02순」, 층 실패는 칩(누르면 다시)', async () => {
    render(<WarRoomPage />);
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-fill', 'true');
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-inset', JSON.stringify({ left: 44 }));
    const aside = screen.getByRole('complementary', { name: '명령 목록 12순' });
    expect(within(aside).getByRole('heading', { name: '맡겨 둔 일' })).toBeInTheDocument();
    fireEvent.click(within(aside).getByRole('button', { name: '이번 순에 할 일 — 02순' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/\?do=$/);
    fireEvent.click(within(aside).getByRole('button', { name: '01순 — 훈련' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/slot=1/);
    const chip = await screen.findByRole('button', { name: '시야를 못 불러 안개를 비웠습니다 — 다시' });
    const before = vi.mocked(api.campaignVisibility).mock.calls.length;
    fireEvent.click(chip);
    await waitFor(() => expect(vi.mocked(api.campaignVisibility).mock.calls.length).toBeGreaterThan(before));
    // 옛 작전실의 쪽지 패널 · 요격 설명문은 머리줄 서신 · 도움말로 옮겼다.
    expect(document.body).not.toHaveTextContent('요격은 시야');
});

test('데스크톱 — 내 위치 알약 → 카드(현 상세 · 여기로 명령)', async () => {
    render(<WarRoomPage />);
    fireEvent.click(screen.getByRole('button', { name: '내 위치 — 양성현' }));
    const card = screen.getByRole('region', { name: '내 위치 — 양성현' });
    expect(within(card).getByRole('link', { name: '현 상세' })).toHaveAttribute('href', '/game/territory/county/3');
    fireEvent.click(within(card).getByRole('button', { name: '여기로 명령' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/target=county(%3A|:)3/);
});

test('모바일 — 12순 열 대신 엿보기 시트(다음 순 · 이번 순에 할 일 · 12순 전체 시트), 내 위치는 아래 알약 · 시트', async () => {
    setMobile(true);
    render(<WarRoomPage />);
    expect(screen.queryByRole('complementary', { name: '명령 목록 12순' })).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-inset', JSON.stringify({ bottom: 180 }));
    const peek = screen.getByRole('region', { name: '명령 목록 12순 — 다음 순' });
    expect(within(peek).getByRole('button', { name: '01순 — 훈련' })).toBeInTheDocument();
    fireEvent.click(within(peek).getByRole('button', { name: '12순 · 맡겨 둔 일' }));
    const sheet = await screen.findByRole('dialog', { name: '명령 목록 12순 · 맡겨 둔 일' });
    expect(within(sheet).getByRole('group', { name: '명령 목록 12순' })).toBeInTheDocument();
    fireEvent.click(within(sheet).getByRole('button', { name: '04순 — 빈 순' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/slot=4/);
    await waitFor(() => expect(screen.queryByRole('dialog', { name: '명령 목록 12순 · 맡겨 둔 일' })).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '내 위치 — 양성현' }));
    expect(await screen.findByRole('dialog', { name: '내 위치 — 양성현' })).toBeInTheDocument();
});

test.each([
    ['불러오는 중', { generalId: null, frontInfo: null, loading: true, error: null }, '불러오는 중'],
    ['장수 정보 실패', { generalId: null, frontInfo: null, loading: false, error: '서버가 잠시 응답하지 않습니다.' }, '장수 정보를 불러오지 못했습니다'],
    ['장수 없음', { generalId: null, frontInfo: null, loading: false, error: null }, '이 서버에 장수가 없습니다'],
])('모바일 · %s — 지도 바닥에 그 상태를 보인다(빈 지도만 남기지 않는다, #1232 리뷰)', async (_, over, text) => {
    setMobile(true);
    session.state = { ...session.state, ...over };
    render(<WarRoomPage />);
    const state = screen.getByRole('region', { name: '작전실 상태' });
    if (text === '불러오는 중') expect(within(state).getByRole('status')).toBeInTheDocument();
    else expect(within(state).getByText(text)).toBeInTheDocument();
    if (text === '장수 정보를 불러오지 못했습니다') {
        fireEvent.click(within(state).getByRole('button', { name: '다시 시도' }));
        expect(session.refresh).toHaveBeenCalled();
    }
});
