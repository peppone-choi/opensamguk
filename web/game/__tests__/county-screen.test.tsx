import { fireEvent, render, screen, within } from '@testing-library/react';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { CountyScreen } from '../components/county/CountyScreen';
import { api } from '../lib/api';

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
} }));

const zero = { money: 0, grain: 0, iron: 0, timber: 0, horses: 0 };
const preview = {
    mapCode: 'x', width: 1, height: 1, serverName: 's', year: 200, month: 3,
    cities: [
        { id: 3, name: '양성현', level: 2, nationId: 1, x: 0, y: 0, commanderyName: '영천군', state: 0, supply: true, isCapital: false },
        { id: 12, name: '진류현', level: 2, nationId: 2, x: 0, y: 0, commanderyName: '진류군', isCommanderySeat: true, state: 0, supply: true, isCapital: false },
    ],
    nations: [{ id: 1, name: '조조', color: '#4f7fbf' }, { id: 2, name: '원소', color: '#9c4a3f' }],
};
const hrefs = {
    territory: (view?: string) => (view ? `/game/pep/territory?view=${view}` : '/game/pep/territory'),
    court: '/game/pep/court?tab=orders', records: '/game/pep/records', flow: (q: string) => `/game/pep?${q}`,
};
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
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep/territory?view=placement');
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
    expect(screen.getByRole('link', { name: '여기로 명령' })).toHaveAttribute('href', '/game/pep?target=county:12');
    fireEvent.click(screen.getByRole('button', { name: '다시 첩보 — 명령 목록에 넣기' }));
    expect(nav.push).toHaveBeenLastCalledWith('/game/pep?do=action.scout&target=commandery:chenliu');
});

test('특산 공개 범위(D40) — 남의 현은 설계값만, 이번 달 실제 몫은 우리 현만', async () => {
    vi.mocked(api.campaignCounty).mockResolvedValue({ status: 'READY', cityId: 12, name: '진류현',
        specialties: [{ resource: 'iron', label: '철', monthly: 37, ledgerMonthly: 120 }, { resource: 'horses', label: '말', monthly: 5, ledgerMonthly: null }] } as never);
    const other = render(<CountyScreen cityId={12} hrefs={hrefs} />);
    const state = await screen.findByRole('region', { name: '형편' });
    expect(await within(state).findByText('철 설계 120/월')).toBeInTheDocument();
    expect(within(state).getByText('말 설계 ?/월')).toBeInTheDocument();
    expect(state).not.toHaveTextContent('37');
    expect(state).not.toHaveTextContent('5/월');
    other.unmount();

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
