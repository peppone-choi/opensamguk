// 게임 관리(P-A03) — 보드 V31K5GameAdmin · GameAdminNations · MGameAdmin. 있는 서버 읽기만 실제 값으로, 없는 것은 서버 대기.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { DirectoryPerson, NationSummary, PeoplePage } from '@/lib/directory-reads';

const mocks = vi.hoisted(() => ({
    nations: vi.fn(),
    people: vi.fn(),
    gameSettings: vi.fn(),
    serverStatus: vi.fn(),
    role: 'ADMIN' as string | null,
    loading: false,
    tab: null as string | null,
    viewport: null as string | null,
}));
vi.mock('@/lib/api', () => ({
    api: { admin: { nations: mocks.nations, people: mocks.people, gameSettings: mocks.gameSettings, serverStatus: mocks.serverStatus } },
}));
vi.mock('@/lib/auth-context', () => ({
    useAuthOptional: () => ({ loading: mocks.loading, user: mocks.role ? { id: 1, username: 'op', nickname: 'op', role: mocks.role } : null }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', generalId: null }) }));
vi.mock('next/navigation', () => ({ useSearchParams: () => new URLSearchParams(mocks.tab ? `tab=${mocks.tab}` : '') }));
vi.mock('@/components/GameShell', () => ({
    default: ({ title, screens, screenOn, children }: { title: string; screens?: { label: string; path: string }[]; screenOn?: string; children: React.ReactNode }) => (
        <div>
            <h2>{title}</h2>
            {screens ? (
                <nav aria-label="하위 화면">
                    {screens.map((s) => <a key={s.label} href={`/game/pep/${s.path}`} aria-current={s.label === screenOn ? 'page' : undefined}>{s.label}</a>)}
                </nav>
            ) : null}
            {children}
        </div>
    ),
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));

import GameAdminPage from '@/app/game/admin/page';
import { adminTabOf } from '@/components/admin/GameAdminScreen';
import { sortNations } from '@/components/admin/AdminNations';
import { readAllAdminPeople } from '@/lib/admin-reads';

const nation = (id: number, name: string, over: Partial<NationSummary> = {}): NationSummary => ({
    status: 'READY',
    nation: { id, name, color: '#4f7fbf' },
    lord: null,
    capitalCityId: 11,
    countyCount: 3,
    retinueCount: 12,
    stockTotal: { money: 12000, grain: 34000, iron: 0, timber: 0, horses: 0 } as NationSummary['stockTotal'],
    population: 123456,
    troops: { city: 5000, bugok: 1200 },
    ...over,
});
const NATIONS = { status: 'READY', nations: [nation(1, '조조'), nation(2, '원소', { countyCount: 9, stockTotal: null, troops: { city: 800, bugok: null } }), nation(3, '유비', { countyCount: 1 })] };

const person = (generalId: number, name: string, nationName: string | null): DirectoryPerson => ({
    generalId, name, portrait: { picture: null, imageServer: 0 },
    affiliation: nationName ? { nationId: 1, name: nationName, color: '#4f7fbf' } : null,
    role: null, lordGeneralId: null,
    stats: { leadership: 90, strength: 95, intel: 40, politics: 30, charm: 60 },
    aptitudes: null, locationCityId: null, bonds: null,
});
const PAGE1: PeoplePage = { status: 'READY', people: [person(10, '허저', '조조'), person(11, '이전', '조조')], nextCursor: 'c2' };
const PAGE2: PeoplePage = { status: 'READY', people: [person(12, '여포', null)], nextCursor: null };

const SETTINGS = {
    msg: '', logWritable: false, scenarioCode: 's1', scenarioText: '군웅할거', year: 200, month: 3, turnPhaseText: '중순',
    status: 'OPEN', starttime: null, startyear: 190, maxgeneral: null, maxnation: null, turntime: '2026-10-04 05:00:00', turnterm: 60,
    turnOptions: [], blockedWrites: [],
};

async function settle() {
    await act(async () => { await new Promise((r) => setTimeout(r, 0)); });
}

beforeEach(() => {
    for (const fn of [mocks.nations, mocks.people, mocks.gameSettings, mocks.serverStatus]) fn.mockReset();
    mocks.role = 'ADMIN';
    mocks.loading = false;
    mocks.tab = null;
    mocks.viewport = null;
    mocks.nations.mockResolvedValue(NATIONS);
    mocks.people.mockImplementation(async (cursor: string | null) => (cursor === null ? PAGE1 : PAGE2));
    mocks.gameSettings.mockResolvedValue(SETTINGS);
    mocks.serverStatus.mockResolvedValue({ result: true, status: 'CLOSED' });
});

describe('게임 관리 — 권한 · 탭', () => {
    it('운영자가 아니면 「관리자 권한이 필요합니다.」, 탭 · 읽기 없음', async () => {
        mocks.role = 'USER';
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('관리자 권한이 필요합니다.')).toBeInTheDocument();
        expect(screen.queryByRole('navigation', { name: '하위 화면' })).toBeNull();
        expect(mocks.nations).not.toHaveBeenCalled();
    });

    it('운영자 — 제목 「게임 관리 · pep」, 머리 탭 다섯, 기본은 세력 개요', async () => {
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByRole('heading', { level: 2, name: '게임 관리 · pep' })).toBeInTheDocument();
        const nav = screen.getByRole('navigation', { name: '하위 화면' });
        expect(within(nav).getAllByRole('link').map((a) => a.textContent)).toEqual(['세력 개요', '인물 조치', '인물 기록', '외교 관계', '서버 상태']);
        expect(within(nav).getByRole('link', { name: '세력 개요' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('tabpanel', { name: '세력 개요' })).toBeInTheDocument();
    });

    it('옛 탭 이름으로 들어와도 새 탭을 연다', () => {
        expect(adminTabOf('generals')).toBe('people');
        expect(adminTabOf('stats')).toBe('nations');
        expect(adminTabOf('logs')).toBe('records');
        expect(adminTabOf('settings')).toBe('status');
        expect(adminTabOf('diplomacy')).toBe('diplomacy');
        expect(adminTabOf('nope')).toBe('nations');
    });
});

describe('세력 개요(GET /api/admin/nations)', () => {
    it('열 일곱 — 창고는 「창고 합」, 병력은 성 + 부곡, 못 읽은 값은 「—」 + 일부 알림', async () => {
        mocks.nations.mockResolvedValue({ ...NATIONS, status: 'PARTIAL' });
        render(<GameAdminPage />);
        await settle();
        const table = screen.getByRole('table');
        expect(within(table).getAllByRole('columnheader').map((h) => h.textContent?.replace(/[▲▼]/g, ''))).toEqual(['세력', '현', '소속 인물', '창고 합 금', '창고 합 쌀', '병력', '호구']);
        expect(within(table).queryByText(/수도 창고/)).toBeNull();
        const cao = within(table).getByRole('row', { name: /조조/ });
        expect(within(cao).getAllByRole('cell').map((c) => c.textContent)).toEqual(['3', '12', '12,000', '34,000', '6,200', '123,456']);
        const yuan = within(table).getByRole('row', { name: /원소/ });
        expect(within(yuan).getAllByRole('cell').map((c) => c.textContent)).toEqual(['9', '12', '—', '—', '—', '123,456']);
        expect(screen.getByRole('note')).toBeInTheDocument();
    });

    it('열 머리를 누르면 높은 순 → 낮은 순, 못 읽은 값은 늘 뒤', async () => {
        render(<GameAdminPage />);
        await settle();
        const names = () => screen.getAllByRole('rowheader').map((h) => h.textContent);
        expect(names()).toEqual(['조조', '원소', '유비']);
        fireEvent.click(screen.getByRole('button', { name: /^현/ }));
        expect(names()).toEqual(['원소', '조조', '유비']);
        expect(screen.getByRole('columnheader', { name: /^현/ })).toHaveAttribute('aria-sort', 'descending');
        fireEvent.click(screen.getByRole('button', { name: /^현/ }));
        expect(names()).toEqual(['유비', '조조', '원소']);
        fireEvent.click(screen.getByRole('button', { name: /창고 합 금/ }));
        expect(names()).toEqual(['조조', '유비', '원소']);
        fireEvent.click(screen.getByRole('button', { name: /창고 합 금/ }));
        expect(names()).toEqual(['조조', '유비', '원소']);
    });

    it('sortNations — 같은 값은 서버 순서를 지킨다', () => {
        const rows = [nation(1, 'a', { countyCount: 2 }), nation(2, 'b', { countyCount: 2 }), nation(3, 'c', { countyCount: null })];
        expect(sortNations(rows, { key: 'county', dir: 'desc' }).map((n) => n.nation!.name)).toEqual(['a', 'b', 'c']);
        expect(sortNations(rows, { key: 'county', dir: 'asc' }).map((n) => n.nation!.name)).toEqual(['a', 'b', 'c']);
        expect(sortNations(rows, null)).toBe(rows);
    });

    it('UNAVAILABLE 는 오류 · 다시 시도, 403 은 권한 없음, 0 세력은 빈 상태', async () => {
        mocks.nations.mockResolvedValue({ status: 'UNAVAILABLE', nations: [] });
        const { unmount } = render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('세력 개요를 지금 읽을 수 없습니다')).toBeInTheDocument();
        unmount();

        mocks.nations.mockRejectedValue(new Error('403: Forbidden'));
        const second = render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('관리자 권한이 필요합니다.')).toBeInTheDocument();
        second.unmount();

        mocks.nations.mockResolvedValue({ status: 'READY', nations: [] });
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('세력이 없습니다')).toBeInTheDocument();
    });
});

describe('인물 조치(GET /api/admin/people + 조치는 서버 대기)', () => {
    it('모든 쪽을 이어 받아 사람 고르기를 채우고, 고른 인물 표는 소속만 실제 값', async () => {
        mocks.tab = 'people';
        render(<GameAdminPage />);
        await settle();
        expect(mocks.people).toHaveBeenCalledTimes(2);
        expect(mocks.people.mock.calls.map((c) => c[0])).toEqual([null, 'c2']);
        const list = screen.getByRole('listbox', { name: '대상 인물' });
        expect(within(list).getByText('여포')).toBeInTheDocument();
        expect(screen.getByText('고른 인물이 없습니다')).toBeInTheDocument();

        fireEvent.click(within(list).getByText('허저'));
        fireEvent.click(within(list).getByText('여포'));
        const table = screen.getByRole('table', { name: '고른 인물' });
        expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['인물', '사람/NPC', '차단', '소속', '다음 개인 턴', '1순', '2순']);
        expect(within(within(table).getByRole('row', { name: /허저/ })).getAllByRole('cell').map((c) => c.textContent)).toEqual(['—', '—', '조조 소속', '—', '—', '—']);
        expect(within(within(table).getByRole('row', { name: /여포/ })).getAllByRole('cell')[2]).toHaveTextContent('재야');
    });

    it('조치 단추는 모두 잠겨 있고(aria-disabled) 누르면 사유가 열린다 — 서버에 아무것도 보내지 않는다', async () => {
        mocks.tab = 'people';
        render(<GameAdminPage />);
        await settle();
        for (const label of ['접속 허용', '접속 제한', '모두 접속 허용', '모두 접속 제한', '차단 풀기', '말하기 막기', '턴 막기', '3단계', '강제 사망', '보내기']) {
            expect(screen.getByRole('button', { name: label })).toHaveAttribute('aria-disabled', 'true');
        }
        const death = screen.getByRole('button', { name: '강제 사망' });
        const reasonOf = (button: HTMLElement) => document.getElementById(button.getAttribute('aria-describedby')!.split(' ').pop()!)!;
        expect(reasonOf(death)).not.toBeVisible();
        fireEvent.click(death);
        expect(reasonOf(death)).toBeVisible();
        expect(reasonOf(death)).toHaveTextContent('먼저 대상 인물을 고르세요');
        // 고른 뒤에는 사유가 「서버가 아직 받지 않습니다」로 바뀐다.
        fireEvent.click(within(screen.getByRole('listbox', { name: '대상 인물' })).getByText('허저'));
        expect(reasonOf(screen.getByRole('button', { name: '턴 막기' }))).toHaveTextContent('운영 조치를 서버가 아직 받지 않습니다');
        expect(screen.getByRole('note')).toHaveTextContent('조치는 서버 준비 중입니다');
        expect(mocks.serverStatus).not.toHaveBeenCalled();
    });

    it('모바일 — 고르면 「고른 n명 조치」 하단 시트(조치 · 고른 인물 표)', async () => {
        mocks.tab = 'people';
        mocks.viewport = 'mobile';
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByRole('button', { name: '조치' })).toHaveAttribute('aria-disabled', 'true');
        fireEvent.click(within(screen.getByRole('listbox', { name: '대상 인물' })).getByText('이전'));
        fireEvent.click(screen.getByRole('button', { name: '고른 1명 조치' }));
        const sheet = screen.getByRole('dialog', { name: '이전 조치' });
        expect(within(sheet).getByRole('button', { name: '말하기 막기' })).toHaveAttribute('aria-disabled', 'true');
        expect(within(sheet).getByRole('table', { name: '고른 인물' })).toBeInTheDocument();
    });

    it('목록 받기 실패는 다시 시도, 403 은 권한 없음', async () => {
        mocks.tab = 'people';
        mocks.people.mockRejectedValue(new Error('500: Internal Server Error'));
        const { unmount } = render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('대상 인물을 불러오지 못했습니다')).toBeInTheDocument();
        unmount();
        mocks.people.mockRejectedValue(new Error('403: Forbidden'));
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('관리자 권한이 필요합니다.')).toBeInTheDocument();
    });
});

describe('readAllAdminPeople', () => {
    it('409(명부 바뀜)면 처음부터 한 번 다시 받는다 — 두 번째 409 는 던진다', async () => {
        const read = vi.fn()
            .mockResolvedValueOnce(PAGE1)
            .mockRejectedValueOnce(new Error('409: Conflict'))
            .mockResolvedValueOnce(PAGE1)
            .mockResolvedValueOnce(PAGE2);
        const out = await readAllAdminPeople(read);
        expect(out.status).toBe('READY');
        expect(out.people.map((p) => p.name)).toEqual(['허저', '이전', '여포']);
        expect(read.mock.calls.map((c) => c[0])).toEqual([null, 'c2', null, 'c2']);

        const always409 = vi.fn().mockRejectedValue(new Error('409: Conflict'));
        await expect(readAllAdminPeople(always409)).rejects.toThrow('409');
        expect(always409).toHaveBeenCalledTimes(2);
    });

    it('UNAVAILABLE 는 멈추고, 쪽 상한을 넘으면 TRUNCATED', async () => {
        const unavailable = vi.fn().mockResolvedValue({ status: 'UNAVAILABLE', people: [], nextCursor: null });
        expect((await readAllAdminPeople(unavailable)).status).toBe('UNAVAILABLE');
        const endless = vi.fn().mockResolvedValue({ status: 'READY', people: [person(1, '갑', null)], nextCursor: 'more' });
        const out = await readAllAdminPeople(endless);
        expect(out.status).toBe('TRUNCATED');
        expect(endless).toHaveBeenCalledTimes(50);
    });
});

describe('인물 기록 · 외교 관계', () => {
    it('인물을 고르면 능력 카드 + 사건은 서버 대기', async () => {
        mocks.tab = 'records';
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('대상 인물을 고르세요')).toBeInTheDocument();
        fireEvent.click(within(screen.getByRole('listbox', { name: '대상 인물' })).getByText('허저'));
        const card = screen.getByRole('region', { name: '허저 인물' });
        expect(within(card).getByText('조조 소속')).toBeInTheDocument();
        expect(within(card).getByText('무력').nextElementSibling).toHaveTextContent('95');
        expect(screen.getByText('인물 기록을 준비하고 있습니다')).toBeInTheDocument();
    });

    it('외교 관계는 서버 대기 — 옛 diplomacy-all 을 부르지 않는다', async () => {
        mocks.tab = 'diplomacy';
        render(<GameAdminPage />);
        await settle();
        expect(screen.getByText('외교 관계를 준비하고 있습니다')).toBeInTheDocument();
        expect(mocks.nations).not.toHaveBeenCalled();
        expect(mocks.people).not.toHaveBeenCalled();
    });
});

describe('서버 상태(game-settings 읽기 + server-status 바꾸기)', () => {
    it('지금 상태 · 게임 날짜 · 마지막 턴, 같은 상태로는 바꿀 수 없다', async () => {
        mocks.tab = 'status';
        render(<GameAdminPage />);
        await settle();
        const panel = screen.getByRole('region', { name: '서버 상태' });
        expect(within(panel).getByText('지금 상태').nextElementSibling).toHaveTextContent('열림');
        expect(within(panel).getByText('게임 날짜').nextElementSibling).toHaveTextContent('200년 3월 중순 · 군웅할거');
        expect(within(panel).getByText('마지막 턴').nextElementSibling).toHaveTextContent('2026-10-04 05:00:00');
        expect(screen.getByRole('button', { name: '상태 바꾸기' })).toHaveAttribute('aria-disabled', 'true');
        expect(screen.getByRole('link', { name: '운영 콘솔' })).toHaveAttribute('href', '/admin');
    });

    it('고르고 확인하면 접수 → 「반영 여부는 위 지금 상태로」 + 다시 읽기', async () => {
        mocks.tab = 'status';
        render(<GameAdminPage />);
        await settle();
        fireEvent.click(screen.getByRole('radio', { name: '닫힘(점검)' }));
        fireEvent.click(screen.getByRole('button', { name: '상태 바꾸기' }));
        const dialog = screen.getByRole('dialog', { name: '서버 상태 바꾸기' });
        expect(dialog).toHaveTextContent('「닫힘(점검)」(으)로 바꿉니다');
        await act(async () => { fireEvent.click(within(dialog).getByRole('button', { name: '바꾸기' })); });
        await settle();
        expect(mocks.serverStatus).toHaveBeenCalledWith('CLOSED');
        expect(screen.getByRole('status')).toHaveTextContent('바꾸기를 접수했습니다(닫힘(점검)). 반영 여부는 위 지금 상태로 확인합니다.');
        expect(mocks.gameSettings).toHaveBeenCalledTimes(2);
    });

    it('거절이면 서버 사유를 그대로', async () => {
        mocks.tab = 'status';
        mocks.serverStatus.mockRejectedValue(new Error('invalid status'));
        render(<GameAdminPage />);
        await settle();
        fireEvent.click(screen.getByRole('radio', { name: '준비 중' }));
        fireEvent.click(screen.getByRole('button', { name: '상태 바꾸기' }));
        await act(async () => { fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: '바꾸기' })); });
        await settle();
        expect(screen.getByRole('status')).toHaveTextContent('invalid status');
    });
});
