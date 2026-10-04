import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
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
vi.mock('../components/GameShell', () => ({ default: ({ children, bare }: { children: ReactNode; bare?: boolean }) => <div data-shell-bare={bare ? 'true' : 'false'}>{children}</div> }));
// 지도 흉내 — 넘긴 값을 보이고, 고르기(onPick) · 레이어 판(onLayerPanelChange)은 시험이 부른다.
type MapProps = { fill?: boolean; myLocationInset?: unknown; pickedCityId?: number | null; onPick?: (pick: unknown) => void;
    layerPanel?: string | null; onLayerPanelChange?: (open: string | null) => void; onMapHandle?: (handle: unknown) => void };
const mapProps = vi.hoisted(() => ({ current: null as null | MapProps }));
vi.mock('../components/campaign/WarRoomMap', () => ({
    default: (props: MapProps) => {
        mapProps.current = props;
        return <div data-testid="war-map" data-fill={String(props.fill)} data-inset={JSON.stringify(props.myLocationInset ?? null)}
            data-picked={String(props.pickedCityId)} data-layer={String(props.layerPanel)} />;
    },
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
// 지도에서 고른 남의 현(원소 · 진류군, 첩보 3순 전) — WarRoomMap onPick 이 싣는 모양.
const pickJinliu = {
    cityId: 9, me: false, provinceRecordId: 'B',
    city: { id: 9, name: '진류현', level: 2, nationId: 2, x: 0, y: 0, state: 0, supply: true, isCapital: false, commanderyName: '진류군', isCommanderySeat: true },
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#b03a2e' }],
};
const pickMap = (pick: unknown) => act(() => mapProps.current?.onPick?.(pick));
// 세션 — 시험마다 바꾼다(불러오는 중 · 실패 · 장수 없음).
const session = vi.hoisted(() => ({ state: null as null | Record<string, unknown>, refresh: vi.fn() }));
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => session.state }));
vi.mock('../lib/api', () => {
    const fail = () => vi.fn(async () => { throw new Error('503: Service Unavailable'); });
    return { api: {
        campaignVisibility: fail(), campaignCorps: fail(), campaignSieges: fail(), campaignWorks: fail(), campaignScoutOptions: fail(),
        campaignLastTurns: fail(), deployOptions: fail(), dispatchPending: fail(), stratagemHand: fail(), campaignCounty: fail(),
        campaignRetinue: fail(), campaignYuedan: fail(), reservedCommands: fail(), mailbox: fail(), generalsList: fail(), commands: fail(),
        campaignPosts: fail(), campaignPolicies: fail(),
    } };
});

let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });
beforeEach(() => {
    vi.clearAllMocks();
    mapProps.current = null;
    // 시험이 READY 로 바꾼 읽기를 실패로 되돌린다(clearAllMocks 는 구현을 지우지 않는다)
    for (const read of [api.campaignVisibility, api.campaignCorps, api.campaignCounty]) {
        vi.mocked(read).mockImplementation(async () => { throw new Error('503: Service Unavailable'); });
    }
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

test('데스크톱 — 지도가 상자를 채우고 오른쪽 12순 열 · 맡겨 둔 일 · 「이번 순에 할 일 — 02순」, 층 실패는 칩 하나(누르면 다시)', async () => {
    render(<WarRoomPage />);
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-fill', 'true');
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-inset', JSON.stringify({ left: 44 }));
    // 데스크톱 郡 정보 줄은 오른쪽 아래 작은 지도(176 + 12 + 8) 왼쪽에서 멈춘다
    expect(screen.getByRole('region', { name: '지도' }).style.getPropertyValue('--commandery-info-right')).toBe('196px');
    const aside = screen.getByRole('complementary', { name: '명령 목록 12순' });
    expect(within(aside).getByRole('heading', { name: '맡겨 둔 일' })).toBeInTheDocument();
    // 맡겨 둔 일 6칸 — 읽기가 다 실패하면 칸마다 「?」(0 · 없음으로 그리지 않는다), 누르면 그 화면
    const tiles = await within(aside).findAllByRole('link', { name: /못 읽음$/ });
    expect(tiles.map((t) => t.getAttribute('aria-label'))).toEqual(['출병 ? · 못 읽음', '배치 ? · 못 읽음', '방침 ? · 못 읽음', '공사 ? · 못 읽음', '계책 ? · 못 읽음', '발령 ? · 못 읽음']);
    expect(tiles[1]).toHaveAttribute('href', '/game/territory?view=placement');
    fireEvent.click(within(aside).getByRole('button', { name: '이번 순에 할 일 — 02순' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/\?do=$/);
    fireEvent.click(within(aside).getByRole('button', { name: '01순 — 훈련' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/slot=1/);
    // 층 셋이 다 실패해도 칩은 하나(층마다 세우면 쌓여 지도 점 · 보기 단추를 덮었다 — #1232 CI)
    const chip = await screen.findByRole('button', { name: '시야 · 군단 · 공사 · 포위를 못 불러왔습니다 — 다시' });
    expect(screen.queryAllByRole('button', { name: /못 불러/ })).toHaveLength(1);
    const before = vi.mocked(api.campaignVisibility).mock.calls.length;
    fireEvent.click(chip);
    await waitFor(() => expect(vi.mocked(api.campaignVisibility).mock.calls.length).toBeGreaterThan(before));
    // 옛 작전실의 쪽지 패널 · 요격 설명문은 머리줄 서신 · 도움말로 옮겼다.
    expect(document.body).not.toHaveTextContent('요격은 시야');
});

test('데스크톱 — 「내 위치」 알약은 내 城을 고르고(지도 테두리), 오른쪽 위 카드에 현 상세 · 여기로 명령, 다시 누르면 푼다', async () => {
    render(<WarRoomPage />);
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    const pill = screen.getByRole('button', { name: '내 위치 — 양성현' });
    fireEvent.click(pill);
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-picked', '3');
    const card = screen.getByRole('region', { name: '고른 현 — 양성현' });
    expect(card).toHaveAttribute('data-testid', 'war-room-pick');
    expect(within(card).getByText('내 위치')).toBeInTheDocument();
    expect(within(card).getByRole('link', { name: '현 상세' })).toHaveAttribute('href', '/game/territory/county/3');
    // 우리 현이라 첩보 단추는 없다
    expect(within(card).queryByRole('button', { name: '첩보' })).toBeNull();
    fireEvent.click(pill);
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-picked', 'null');
    fireEvent.click(pill);
    fireEvent.click(within(screen.getByTestId('war-room-pick')).getByRole('button', { name: '여기로 명령' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/target=county(%3A|:)3/);
    // 명령 흐름으로 넘어가면 카드를 닫는다
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
});

test('데스크톱 — 새 지도가 있으면 「내 위치」 알약은 지도를 내 城으로 옮겨 고른다(focusCity → 지도 고르기 → 카드)', async () => {
    render(<WarRoomPage />);
    const focusCity = vi.fn(() => true);
    act(() => mapProps.current?.onMapHandle?.({ focusCity }));
    fireEvent.click(screen.getByRole('button', { name: '내 위치 — 양성현' }));
    expect(focusCity).toHaveBeenCalledWith(3);
    // 지도가 고르기를 돌려줄 때까지 카드를 따로 세우지 않는다(미리보기 행이 실린 고르기 하나만)
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    pickMap({ ...pickJinliu, cityId: 3, city: { ...pickJinliu.city, id: 3, name: '양성현', nationId: 1, commanderyName: '영천군' } });
    const card = screen.getByRole('region', { name: '고른 현 — 양성현' });
    expect(card).toHaveTextContent('영천군');
    expect(card).toHaveTextContent('내 위치');
    expect(card).toHaveTextContent('이어짐');
});

test('관을 고르면(지도 pick.pass, K2 #1322) 카드 머리에 「관」 칩 — 보통 城에는 없다, 모바일 알약도 같은 칩', async () => {
    render(<WarRoomPage />);
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalled());
    pickMap({ ...pickJinliu, pass: { cityId: 9 } });
    expect(within(screen.getByRole('region', { name: '고른 현 — 진류현' })).getByText('관')).toBeInTheDocument();
    pickMap(pickJinliu);
    expect(within(screen.getByRole('region', { name: '고른 현 — 진류현' })).queryByText('관')).toBeNull();
});

test('모바일 — 관을 고르면 선택 알약에도 「관」 칩', async () => {
    setMobile(true);
    render(<WarRoomPage />);
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalled());
    pickMap({ ...pickJinliu, pass: { cityId: 9 } });
    expect(within(screen.getByRole('button', { name: '고른 현 — 진류현' })).getByText('관')).toBeInTheDocument();
});

test('데스크톱 — 지도에서 남의 현을 고르면 카드(소속 · 보급 안 보임 · 주둔 · 첩보 3순 전 · 특산 설계값 D40), 첩보 → 흐름', async () => {
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [{ no: 2, id: 'P2', name: '진류군', tier: 'INTEL', ageTurns: 3 }] });
    vi.mocked(api.campaignCorps).mockResolvedValue({ status: 'READY', corps: [{ corpsId: 'c', ownerGeneralId: 9, commanderGeneralId: 9, commanderName: '안량',
        nationId: 2, provinceId: 'B', commanderyNo: 2, visibility: 'INTEL', own: false }] });
    vi.mocked(api.campaignCounty).mockResolvedValue({ status: 'READY', specialties: [{ resource: 'iron', label: '철', monthly: 40, ledgerMonthly: 30 }] } as never);
    render(<WarRoomPage />);
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalled());
    pickMap(pickJinliu);
    const card = screen.getByRole('region', { name: '고른 현 — 진류현' });
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-picked', '9');
    expect(card).toHaveTextContent('진류군');
    expect(card).toHaveTextContent('군 치소');
    expect(card).toHaveTextContent('원소');
    expect(card).toHaveTextContent('안 보임');
    expect(await within(card).findByText('안량 군단')).toBeInTheDocument();
    expect(await within(card).findByText('첩보 3순 전')).toBeInTheDocument();
    // 남의 현 특산은 설계값만 — 이번 달 실제 몫(40)은 보이지 않는다(D40)
    expect(await within(card).findByText('특산 철 설계 30/월')).toBeInTheDocument();
    expect(card).not.toHaveTextContent('40');
    expect(card).toHaveTextContent('다른 현의 형편 수치는 아직 서버가 주지 않습니다');
    expect(within(card).queryByText('내 위치')).toBeNull();
    fireEvent.click(within(card).getByRole('button', { name: '첩보' }));
    const href = String(nav.push.mock.calls.at(-1)?.[0]);
    expect(href).toMatch(/do=action\.scout/);
    expect(href).toMatch(/target=commandery(%3A|:)P2/);
});

test('데스크톱 — 「여기로 명령」은 고른 城의 구역 id 를 알면 구역 대상(이동 · 출병 「어디로」를 채운다), 모르면 현 대상', async () => {
    render(<WarRoomPage />);
    pickMap(pickJinliu);
    fireEvent.click(within(screen.getByTestId('war-room-pick')).getByRole('button', { name: '여기로 명령' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/target=province(%3A|:)B/);
    pickMap({ ...pickJinliu, provinceRecordId: null });
    fireEvent.click(within(screen.getByTestId('war-room-pick')).getByRole('button', { name: '여기로 명령' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/target=county(%3A|:)9/);
});

test('데스크톱 — 내 위치 표지를 누르면 내 장수 카드(보드 me_card: 소속 · 城 · 군 · 자리 · 다음 개인 턴), 이번 순에 할 일 → 흐름', async () => {
    render(<WarRoomPage />);
    pickMap({ ...pickJinliu, cityId: 3, me: true, city: { ...pickJinliu.city, id: 3, name: '양성현', nationId: 1, commanderyName: '영천군' } });
    const card = screen.getByRole('region', { name: '내 장수 — 하후돈' });
    expect(card).toHaveAttribute('data-testid', 'war-room-pick');
    expect(card).toHaveTextContent('조조 소속 · 양성현 · 영천군');
    expect(card).toHaveTextContent('성 안');
    expect(card).toHaveTextContent('22:40');
    // 보드 칸은 빼지 않는다(K0 10-03): 귀환 성은 값 자리에 「서버 대기」, 「장수 상세」는 인물 상세(P-R03) 전까지 사유 있는 비활성
    expect(card).toHaveTextContent('귀환 성');
    expect(card).toHaveTextContent('서버 대기');
    const detail = within(card).getByRole('button', { name: '장수 상세' });
    expect(detail).toHaveAttribute('aria-disabled', 'true');
    expect(detail).toHaveAccessibleDescription(/장수 상세 화면은 아직 준비 중입니다/);
    fireEvent.click(detail);
    expect(nav.push).not.toHaveBeenCalled();
    // 단추는 보드 me_card 그대로 둘(이번 순에 할 일 · 장수 상세) — 현 상세는 城 선택 카드에 있다
    expect(within(card).queryByRole('link', { name: '현 상세' })).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-picked', '3');
    fireEvent.click(within(card).getByRole('button', { name: '이번 순에 할 일' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/\?do=$/);
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
});

test('데스크톱 — 카드와 레이어 · 범례 판은 나중에 연 것이 이전 것을 닫는다, Esc · 빈 땅 · 닫기는 카드를 닫는다', async () => {
    render(<WarRoomPage />);
    pickMap(pickJinliu);
    expect(screen.getByTestId('war-room-pick')).toBeInTheDocument();
    act(() => mapProps.current?.onLayerPanelChange?.('layers'));
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-layer', 'layers');
    pickMap(pickJinliu);
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-layer', 'null');
    fireEvent.keyDown(within(screen.getByTestId('war-room-pick')).getByRole('button', { name: '닫기' }), { key: 'Escape' });
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    pickMap(pickJinliu);
    pickMap(null);
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    pickMap(pickJinliu);
    fireEvent.click(within(screen.getByTestId('war-room-pick')).getByRole('button', { name: '닫기' }));
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
});

test('모바일 — 지도에서 고르면 선택 알약만 바뀐다(시트를 저절로 열지 않는다), 알약 → 시트, × → 내 위치로', async () => {
    setMobile(true);
    render(<WarRoomPage />);
    pickMap(pickJinliu);
    expect(screen.queryByRole('dialog')).toBeNull();
    const pill = screen.getByRole('button', { name: '고른 현 — 진류현' });
    expect(pill).toHaveAttribute('data-testid', 'war-room-pick');
    expect(pill).toHaveTextContent('진류군 치소');
    fireEvent.click(pill);
    const sheet = await screen.findByRole('dialog', { name: '고른 현 — 진류현' });
    expect(within(sheet).getByRole('link', { name: '현 상세' })).toHaveAttribute('href', '/game/territory/county/9');
    fireEvent.click(within(sheet).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    fireEvent.click(screen.getByRole('button', { name: '고르기 풀기 — 진류현' }));
    expect(screen.getByRole('button', { name: '내 위치 — 양성현' })).toBeInTheDocument();
    expect(screen.queryByTestId('war-room-pick')).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-picked', 'null');
});

test('모바일 — 내 위치 표지를 누르면 알약이 「내 장수」, 누르면 내 장수 시트', async () => {
    setMobile(true);
    render(<WarRoomPage />);
    pickMap({ ...pickJinliu, cityId: 3, me: true, city: { ...pickJinliu.city, id: 3, name: '양성현', nationId: 1, commanderyName: '영천군' } });
    expect(screen.queryByRole('dialog')).toBeNull();
    const pill = screen.getByRole('button', { name: '내 장수 — 하후돈' });
    expect(pill).toHaveTextContent('조조 소속 · 양성현 · 영천군');
    expect(pill).toHaveTextContent('내 위치');
    fireEvent.click(pill);
    const sheet = await screen.findByRole('dialog', { name: '내 장수 — 하후돈' });
    expect(sheet).toHaveTextContent('22:40');
    fireEvent.click(within(sheet).getByRole('button', { name: '이번 순에 할 일' }));
    expect(nav.push.mock.calls.at(-1)?.[0]).toMatch(/\?do=$/);
});

test('모바일 — 12순 열 대신 엿보기 시트(다음 순 · 이번 순에 할 일 · 12순 전체 시트), 내 위치는 아래 알약 · 시트', async () => {
    setMobile(true);
    render(<WarRoomPage />);
    expect(screen.queryByRole('complementary', { name: '명령 목록 12순' })).toBeNull();
    expect(screen.getByTestId('war-map')).toHaveAttribute('data-inset', JSON.stringify({ bottom: 180 }));
    // 郡 정보 줄 · 보기 단추는 엿보기 시트 + 선택 알약 위(124 + 56 + 12)
    const mapRegion = screen.getByRole('region', { name: '지도' });
    expect(mapRegion.style.getPropertyValue('--map-viewbar-bottom')).toBe('192px');
    expect(mapRegion.style.getPropertyValue('--commandery-info-bottom')).toBe('192px');
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

test('모바일 — 「지난 순」 칩은 셸 머리줄 칩 자리(#shell-page-chips)에 꽂는다, 제목 줄은 없고 제목은 화면 읽기용(보드 V31K4MWarRoom)', async () => {
    const slot = document.createElement('span');
    slot.id = 'shell-page-chips';
    document.body.appendChild(slot);
    try {
        setMobile(true);
        render(<WarRoomPage />);
        await waitFor(() => expect(within(slot).getByRole('button', { name: /^지난 순/ })).toBeInTheDocument());
        expect(screen.getAllByRole('button', { name: /^지난 순/ })).toHaveLength(1);
        // 제목 줄 없음 — GameShell bare(보드 작전실은 제목 줄이 없다; 제목은 GameShell 이 화면 읽기용으로만 둔다)
        expect(document.querySelector('[data-shell-bare]')).toHaveAttribute('data-shell-bare', 'true');
    } finally {
        slot.remove();
    }
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
    // 상태 판(높이 124)이 지도 보기 단추(주 · 군 · 현 · + · −)를 덮지 않게 보기 단추를 판 위로 올린다(#1232 리뷰).
    expect(state.style.height).toBe('124px');
    expect(screen.getByRole('region', { name: '지도' }).style.getPropertyValue('--map-viewbar-bottom')).toBe('136px');
    // 郡 정보 줄(「첩보 보내기」)도 상태 판 위로 — 보기 단추와 같은 높이
    expect(screen.getByRole('region', { name: '지도' }).style.getPropertyValue('--commandery-info-bottom')).toBe('136px');
    if (text === '불러오는 중') expect(within(state).getByRole('status')).toBeInTheDocument();
    else expect(within(state).getByText(text)).toBeInTheDocument();
    if (text === '장수 정보를 불러오지 못했습니다') {
        fireEvent.click(within(state).getByRole('button', { name: '다시 시도' }));
        expect(session.refresh).toHaveBeenCalled();
    }
});
