import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { expectServerWait, expectServerWaitGone, installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { CountyScreen } from '../components/county/CountyScreen';
import { api } from '../lib/api';
import { countyHrefs } from '../app/game/(campaign)/territory/county/county-hrefs';

const nav = vi.hoisted(() => ({ push: vi.fn() }));
// 결정 단추가 도움말 고리(useReasonHelp → useOpenHelp)를 쓴다 — 지금 경로 · 쿼리 · router 흉내. 바꾸기는 router.push 로 영지 · 흐름을 연다.
vi.mock('next/navigation', () => ({ usePathname: () => '/game/pep/territory/county/3', useSearchParams: () => new URLSearchParams(), useRouter: () => ({ push: nav.push, replace: vi.fn(), back: vi.fn() }) }));
const frontCity = {
    id: 3, name: '양성현', level: 2, nationId: 1, region: 0, population: 900, populationMax: 1000, agriculture: 50, agricultureMax: 100,
    commerce: 40, commerceMax: 100, security: 30, securityMax: 100, defense: 20, defenseMax: 100, wall: 10, wallMax: 100, trust: 45, trade: null,
};
let session: { city: unknown } = { city: frontCity };
vi.mock('../lib/campaign-session', () => ({ useGameSession: () => ({
    generalId: 7, frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { name: '하후돈' }, nation: { id: 1 }, city: session.city },
}) }));
vi.mock('../lib/api', () => ({ api: {
    mapPreview: vi.fn(), campaignCounty: vi.fn(), campaignPolicies: vi.fn(), campaignWorks: vi.fn(), warehouses: vi.fn(), campaignVisibility: vi.fn(),
    countyDetail: vi.fn(),
} }));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const preview = {
    mapCode: 'x', width: 1, height: 1, serverName: 's', year: 200, month: 3,
    cities: [
        { id: 3, name: '양성현', level: 2, nationId: 1, x: 0, y: 0, commanderyName: '영천군', state: 0, supply: true, isCapital: false },
        { id: 12, name: '진류현', level: 2, nationId: 2, x: 0, y: 0, commanderyName: '진류군', isCommanderySeat: true, state: 0, supply: true, isCapital: false, provinceId: 5 },
    ],
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#9c4a3f' }],
    // 진류현 구역(번호 5)의 서버 id — 「여기로 명령」이 구역 대상으로 간다. 양성현은 구역 번호가 없어 현 대상 그대로.
    provinceOccupancy: [{ provinceRecordId: '200050', provinceIndex: 5, nationId: 2 }],
};
const hrefs = countyHrefs('pep');
let viewport: ReturnType<typeof installViewport> | null = null;
const setMobile = (on: boolean) => { viewport?.restore(); viewport = installViewport(on ? 390 : 1440); };
afterEach(() => { viewport?.restore(); viewport = null; });

beforeEach(() => {
    vi.clearAllMocks();
    session = { city: frontCity };
    setMobile(false);
    vi.mocked(api.mapPreview).mockResolvedValue(preview as never);
    vi.mocked(api.campaignCounty).mockResolvedValue({ status: 'READY', cityId: 3, name: '양성현', specialties: [{ resource: 'iron', label: '철', monthly: 0, ledgerMonthly: 120 }] } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [],
        counties: [{ countyId: 3, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: { policy: 'FARM', label: '권농', source: 'DEFAULT' }, seat: null, settable: true, blocked: null }] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValue({ status: 'READY', counties: [{ countyId: 3, provinceId: null, provinceIds: [], name: '양성현', commanderyName: '영천군', warehouse: null,
        active: { work: 'IRRIGATION', label: '수리', percent: 35, remainingPhases: 4, remainingCost: zero, stopReasonText: '자재 부족 — 멈춤', startsAtNextBoundary: false },
        completed: [{ work: 'FORT', label: '성방', edgeId: null }], startable: [] }] } as never);
    vi.mocked(api.warehouses).mockResolvedValue({ status: 'READY', warehouses: [{ cityId: 3, name: '양성현', commanderyName: null, isCapital: false, supplied: true, stock: { ...zero, money: 300 } }] } as never);
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [
        { no: 1, id: 'yingchuan', name: '영천군', tier: 'FULL' }, { no: 2, id: 'chenliu', name: '진류군', tier: 'INTEL', ageTurns: 3 },
    ] } as never);
    // 현 상세(K4-04)는 늘 부른다 — 서버 경로가 아직 없으면 404(D124 미리 짓기).
    vi.mocked(api.countyDetail).mockRejectedValue(new Error('404: Not Found'));
});

test('우리 현 · 내 장수가 선 곳 — 7지표 · 특산(설계값) · 창고 · 현령 빈자리 · 방침 · 공사 진행, 바꾸기는 영지 칸으로 · 여기서 할 일은 흐름', async () => {
    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    expect(await screen.findByRole('heading', { name: '양성현' })).toBeInTheDocument();
    expect(screen.getByText('지금 여기')).toBeInTheDocument();
    const state = screen.getByRole('region', { name: '형편' });
    expect(within(state).getAllByRole('meter')).toHaveLength(7);
    expect(state).toHaveTextContent('철 0/월 · 설계 120');
    expect(state).toHaveTextContent('금 300');
    const gov = screen.getByRole('region', { name: '다스림' });
    expect(gov).toHaveTextContent('현령 — 빈자리');
    expect(gov).toHaveTextContent('권농');
    expect(gov).toHaveTextContent('기본');
    fireEvent.click(within(gov).getByRole('button', { name: '바꾸기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep/territory?view=policy');
    fireEvent.click(within(gov).getByRole('button', { name: '현령 앉히기 — 배치' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep/territory?view=placement&countyId=3');
    const works = screen.getByRole('region', { name: '공사' });
    expect(works).toHaveTextContent('수리 35% · 4순 남음');
    expect(works).toHaveTextContent('자재 부족 — 멈춤');
    expect(works).toHaveTextContent('성방');
    fireEvent.click(within(works).getByRole('button', { name: '새 공사 — 영지 공사 칸에서' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep/territory?view=work');
    expect(screen.getByRole('link', { name: '여기서 할 일 — 명령 목록에 넣기' })).toHaveAttribute('href', '/game/pep?target=county:3');
    expect(screen.getByText('수비군 — 서버 대기')).toBeInTheDocument();
    expect(screen.getByText('최근 사건 — 서버 대기')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /다시 첩보/ })).toBeNull();
    expect(document.body).not.toHaveTextContent('IRRIGATION');
    // 계절 사건 띠(P-K07) — 사건 없음 → 띠 없음. 읽기(K8-08 · K8-EV)가 붙기 전에는 「서버 대기」 띠도 늘 띄우지 않는다(K4 10-05 합의).
    expect(screen.queryByRole('status', { name: '이 현의 계절 사건' })).toBeNull();
    expect(document.body).not.toHaveTextContent('계절 사건');
});

test('남의 현 · 첩보 3순 전 — 형편 서버 대기 · 창고 안 보임, 입력은 점선 「우리 현이 아닙니다」, 다시 첩보는 그 군을 대상으로 흐름', async () => {
    render(<CountyScreen cityId={12} hrefs={hrefs} />);
    expect(await screen.findByRole('heading', { name: '진류현' })).toBeInTheDocument();
    expect(screen.getByText('첩보 3순 전')).toBeInTheDocument();
    expect(screen.getByText('원소')).toBeInTheDocument();
    expect(screen.getByText('군 치소')).toBeInTheDocument();
    expect(screen.getByText('형편 7지표 — 서버 대기')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '형편' })).toHaveTextContent('안 보임 — 우리 현이 아닙니다');
    for (const id of ['placement.assign', 'policy.set', 'work.start']) {
        expect(document.querySelector(`button[data-input-id="${id}"]`)).toHaveAccessibleDescription(/우리 현이 아닙니다/);
    }
    fireEvent.click(screen.getByRole('button', { name: '바꾸기' }));
    expect(nav.push).not.toHaveBeenCalled();
    expect(screen.getByRole('link', { name: '여기로 명령' })).toHaveAttribute('href', '/game/pep?target=province:200050');
    fireEvent.click(screen.getByRole('button', { name: '다시 첩보 — 명령 목록에 넣기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.scout&target=commandery:chenliu');
});

test('특산 공개 범위(D40) — 남의 현은 설계값만, 이번 달 실제 몫은 우리 현만', async () => {
    vi.mocked(api.campaignCounty).mockResolvedValue({ status: 'READY', cityId: 12, name: '진류현',
        specialties: [{ resource: 'iron', label: '철', monthly: 37, ledgerMonthly: 120 }, { resource: 'horses', label: '말', monthly: 5, ledgerMonthly: null }] } as never);
    const other = render(<CountyScreen cityId={12} hrefs={hrefs} />);
    const state = await screen.findByRole('region', { name: '형편' });
    expect(await within(state).findByText('철 설계 120/월')).toBeInTheDocument();
    // 설계값을 모르는 남의 현 특산은 칩을 그리지 않는다(CEO 10-03) — 「?」 칩도 없다
    expect(within(state).queryByText(/^말/)).toBeNull();
    expect(state).not.toHaveTextContent('?/월');
    expect(state).not.toHaveTextContent('37');
    expect(state).not.toHaveTextContent('5/월');
    other.unmount();

    // 남은 칩이 하나도 없으면 「—」
    vi.mocked(api.campaignCounty).mockResolvedValueOnce({ status: 'READY', cityId: 12, name: '진류현',
        specialties: [{ resource: 'horses', label: '말', monthly: 5, ledgerMonthly: null }] } as never);
    const none = render(<CountyScreen cityId={12} hrefs={hrefs} />);
    const empty = await screen.findByRole('region', { name: '형편' });
    const row = within(empty).getByText('특산').parentElement!;
    await waitFor(() => expect(row).toHaveTextContent(/^특산—$/));
    none.unmount();

    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    expect(await within(await screen.findByRole('region', { name: '형편' })).findByText('철 37/월 · 설계 120')).toBeInTheDocument();
});

test('없는 현 · 지도 읽기 실패 · 일부 읽기 실패 — 「찾을 수 없음」, 공용 오류 번호(원문 0), 실패 줄 · 다시 읽기', async () => {
    const a = render(<CountyScreen cityId={999} hrefs={hrefs} />);
    expect(await screen.findByText('이 현을 찾을 수 없습니다')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '영지로' })).toHaveAttribute('href', '/game/pep/territory');
    a.unmount();
    const b = render(<CountyScreen cityId={null} hrefs={hrefs} />);
    expect(await screen.findByText('이 현을 찾을 수 없습니다')).toBeInTheDocument();
    b.unmount();

    vi.mocked(api.mapPreview).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    const c = render(<CountyScreen cityId={3} hrefs={hrefs} />);
    expect(await screen.findByText('현을 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '오류 번호 503 복사' })).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('Service Unavailable');
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(await screen.findByRole('heading', { name: '양성현' })).toBeInTheDocument();
    c.unmount();

    vi.mocked(api.campaignCounty).mockRejectedValueOnce(new Error('500: boom'));
    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    expect(await screen.findByText('특산을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.getByText('일부를 불러오지 못했습니다.')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '다시 읽기' }));
    expect(await screen.findByText('철 0/월 · 설계 120')).toBeInTheDocument();
    expect(document.body).not.toHaveTextContent('boom');
});

test('방침 읽기 실패 · 읽는 중 · 서버 상태 — 우리 현을 「빈자리」로 단정하지 않는다, 빈자리는 READY 일 때만(#1222 리뷰)', async () => {
    vi.mocked(api.campaignPolicies).mockRejectedValueOnce(new Error('500: boom'));
    vi.mocked(api.campaignWorks).mockRejectedValueOnce(new Error('500: boom'));
    const failed = render(<CountyScreen cityId={3} hrefs={hrefs} />);
    const gov = await screen.findByRole('region', { name: '다스림' });
    expect(await within(gov).findByText('현령 — 확인하지 못했습니다')).toBeInTheDocument();
    expect(within(gov).queryByText('빈자리')).toBeNull();
    expect(gov).not.toHaveTextContent('기본 방침으로 스스로 돌아갑니다');
    expect(screen.getByRole('region', { name: '공사' })).toHaveTextContent('이 현의 공사를 확인하지 못했습니다.');
    expect(screen.getByRole('region', { name: '공사' })).not.toHaveTextContent('진행 중인 공사가 없습니다.');
    failed.unmount();

    vi.mocked(api.campaignPolicies).mockReturnValueOnce(new Promise(() => {}));
    const loading = render(<CountyScreen cityId={3} hrefs={hrefs} />);
    const gov2 = await screen.findByRole('region', { name: '다스림' });
    expect(within(gov2).getByText('현령 — 불러오는 중')).toBeInTheDocument();
    expect(within(gov2).queryByText('빈자리')).toBeNull();
    loading.unmount();

    vi.mocked(api.campaignPolicies).mockResolvedValueOnce({ status: 'UNSUPPORTED_WORLD_FORMAT', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [], counties: [] } as never);
    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    const gov3 = await screen.findByRole('region', { name: '다스림' });
    expect(await within(gov3).findByText('현령 — 확인하지 못했습니다')).toBeInTheDocument();
    expect(within(gov3).queryByText('빈자리')).toBeNull();
});

test('READY 인데 이 현 줄이 없음(군주 · 관할자가 아님) — 빈자리 · 기본 방침 안내 · 「확인하지 못했습니다」가 아니라 권한 밖 문구, 바꾸기 · 새 공사는 점선(#1222 리뷰)', async () => {
    vi.mocked(api.campaignPolicies).mockResolvedValueOnce({ status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, corps: [], counties: [] } as never);
    vi.mocked(api.campaignWorks).mockResolvedValueOnce({ status: 'READY', counties: [] } as never);
    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    const gov = await screen.findByRole('region', { name: '다스림' });
    expect(await within(gov).findByText('현령 — 군주 · 관할자만 봅니다')).toBeInTheDocument();
    expect(within(gov).queryByText('빈자리')).toBeNull();
    expect(gov).not.toHaveTextContent('기본 방침으로 스스로 돌아갑니다');
    expect(gov).not.toHaveTextContent('확인하지 못했습니다');
    const works = screen.getByRole('region', { name: '공사' });
    expect(works).toHaveTextContent('이 현의 공사는 군주 · 관할자만 봅니다.');
    expect(works).not.toHaveTextContent('확인하지 못했습니다');
    for (const id of ['policy.set', 'work.start']) {
        expect(document.querySelector(`button[data-input-id="${id}"]`)).toHaveAccessibleDescription(/현령 · 군주만/);
    }
    fireEvent.click(within(gov).getByRole('button', { name: '바꾸기' }));
    expect(nav.push).not.toHaveBeenCalled();
});

test('모바일 — 머리 · 칩 · 「형편 · 다스림 · 공사 · 사람 · 사건」 세그먼트 · 아래 「여기로 명령」', async () => {
    setMobile(true);
    session = { city: null };
    render(<CountyScreen cityId={3} hrefs={hrefs} />);
    const seg = await screen.findByRole('radiogroup', { name: '보기' });
    expect(within(seg).getAllByRole('radio').map((r) => r.textContent)).toEqual(['형편', '다스림', '공사', '사람', '사건']);
    expect(screen.getByText('형편 7지표 — 서버 대기')).toBeInTheDocument();
    fireEvent.click(within(seg).getByRole('radio', { name: '다스림' }));
    expect(screen.getByText('현령 — 빈자리')).toBeInTheDocument();
    fireEvent.click(within(seg).getByRole('radio', { name: '사건' }));
    expect(screen.getByRole('link', { name: '기록 전체 보기 →' })).toHaveAttribute('href', '/game/pep/records');
    expect(screen.getByRole('link', { name: '여기로 명령' })).toHaveAttribute('href', '/game/pep?target=county:3');
    expect(screen.getByText('하후돈은 지금 이 현에 없습니다. 내정 · 징병 같은 직접 행동은 이 현에 서 있을 때만 됩니다.')).toBeInTheDocument();
});

// ── D124 미리 짓기: 현 상세 읽기(K4-04)를 늘 부른다 ──
test('현 상세 서버 경로가 없으면(404) — 화면 오류 없이 상세 칸만 서버 대기(7지표 · 사람 · 수비군)', async () => {
    const { container } = render(<CountyScreen cityId={12} hrefs={hrefs} />);
    expect(await screen.findByRole('heading', { name: '진류현' })).toBeInTheDocument();
    await waitFor(() => expect(api.countyDetail).toHaveBeenCalledWith(7, 12, expect.anything()));
    expect(screen.queryByText(/불러오지 못했습니다/)).toBeNull();
    expect(screen.getByText('형편 7지표 — 서버 대기')).toBeInTheDocument();
    expect(screen.getByText('수비군 — 서버 대기')).toBeInTheDocument();
    expectServerWait(container, ['K4-04', 'K5-07']);
});

test('현 상세 서버가 주면 — 남의 현도 7지표 · 등급 · 수비군이 보이고, 그 칸의 서버 대기 표지는 사라진다', async () => {
    const it = (value: number, max: number) => ({ value, max, trend: null });
    vi.mocked(api.countyDetail).mockResolvedValue({
        status: 'PARTIAL', cityId: 12,
        indicators: { population: it(7000, 9000), agriculture: it(300, 1000), commerce: it(250, 1000), security: it(40, 100),
            trust: { value: 61.4, max: 100, trend: null }, defence: it(500, 1000), wall: it(800, 1000) },
        grade: { code: 5, label: '중현' }, garrison: { troops: 1200, training: 60, morale: 75 }, peopleHere: null,
        unavailableReasons: { '/peopleHere': 'NO_SOURCE', '/income': 'NOT_AUTHORIZED' },
    } as never);
    render(<CountyScreen cityId={12} hrefs={hrefs} />);
    expect(await screen.findByRole('meter', { name: '호구' })).toHaveTextContent('7000 / 9000');
    expect(screen.getByRole('meter', { name: '민심' })).toHaveTextContent('61 / 100');
    expect(screen.getByText('중현')).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '수비군' })).toHaveTextContent('병력1,200');
    expect(screen.queryByText('형편 7지표 — 서버 대기')).toBeNull();
    expectServerWaitGone(screen.getByRole('region', { name: '형편' }), ['K4-04'], { value: '7000 / 9000' });
    // 이 현의 사람은 첫 판 null — 그 칸만 서버 대기로 남는다.
    expect(screen.getByText('이 현에 있는 사람 · 군단 — 서버 대기')).toBeInTheDocument();
});

test('현 상세가 404 가 아닌 실패(503 등)면 — 서버 대기로 덮지 않고 「일부를 불러오지 못했습니다 · 다시 읽기」, 상세 칸도 실패로(#1392 리뷰 메모)', async () => {
    vi.mocked(api.countyDetail).mockRejectedValueOnce(new Error('503: Service Unavailable'));
    render(<CountyScreen cityId={12} hrefs={hrefs} />);
    expect(await screen.findByText('일부를 불러오지 못했습니다.')).toBeInTheDocument();
    // 남의 현이라 front-info 7지표도 없다 — 7지표 · 이 현의 사람 · 수비군 세 칸이 모두 실패 문구다.
    expect(screen.getAllByText('불러오지 못했습니다 — 위 「다시 읽기」로 다시 읽습니다.')).toHaveLength(3);
    expect(screen.queryByText('수비군 — 서버 대기')).toBeNull();
    const calls = vi.mocked(api.countyDetail).mock.calls.length;
    fireEvent.click(screen.getByRole('button', { name: '다시 읽기' }));
    await waitFor(() => expect(vi.mocked(api.countyDetail).mock.calls.length).toBeGreaterThan(calls));
    expect(await screen.findByText('수비군 — 서버 대기')).toBeInTheDocument();
    expect(screen.queryByText('일부를 불러오지 못했습니다.')).toBeNull();
});
