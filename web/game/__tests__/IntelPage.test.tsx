// 시야 · 첩보 페이지(/game/corps/intel) — 셸 세션의 장수로 시야 · 첩보 옵션을 읽어 군 목록을 그리고,
// 「첩보」 → 작전실 명령 흐름(?do=action.scout&target=commandery:<id>), 「정찰 보내기」 · 「망루 짓기」 → 영지(P-T01).
// 시야 출처는 시야 읽기의 READY 응답에서만 그린다 — 장수 · 순 · 서버 · 다시 시도가 바뀌면 첫 커밋부터 앞 응답이 없고,
// 늦게 온 앞 범위 응답은 쓰이지 않는다. 이 성질은 페이지 · 공용 훅(useCampaignRead)을 고치지 않고 지금 그대로 확인한다.
import { Profiler } from 'react';
import { act, configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import IntelPage from '@/app/game/(campaign)/corps/intel/page';
import { api } from '@/lib/api';
import type { Visibility } from '@/lib/campaign-reads';
import { useGameSession, type GameSession } from '@/lib/campaign-session';

configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });
const push = vi.fn();
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/corps/intel',
    useSearchParams: () => new URLSearchParams(),
    useRouter: () => ({ push, replace: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/lib/api')>()),
    api: { campaignVisibility: vi.fn(), campaignScoutOptions: vi.fn() },
}));

beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 7,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 7, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [
        { no: 1, id: 'c1', name: '영천군', tier: 'FULL' }, { no: 2, id: 'c2', name: '진류군', tier: 'INTEL', ageTurns: 3 },
    ] } as never);
    vi.mocked(api.campaignScoutOptions).mockResolvedValue({ status: 'READY', available: true, options: [{ no: 2, id: 'c2', name: '진류군', tier: 'INTEL', available: true }] } as never);
});

test('군 목록 · 지도 자리 — 「첩보」는 그 군을 미리 고른 작전실 흐름, 정찰 · 망루는 영지', async () => {
    render(<IntelPage />);
    expect(screen.getByRole('heading', { name: '시야 · 첩보' })).toBeInTheDocument();
    expect(screen.getByText('시야 지도 준비 중')).toBeInTheDocument();
    const intel = await screen.findByRole('region', { name: '첩보' });
    expect(intel).toHaveTextContent('3순 전 첩보');
    fireEvent.click(within(intel).getByRole('button', { name: '첩보' }));
    expect(push).toHaveBeenLastCalledWith('/game?do=action.scout&target=commandery%3Ac2');
    fireEvent.click(screen.getByRole('button', { name: '정찰 보내기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
    fireEvent.click(screen.getByRole('button', { name: '망루 짓기' }));
    expect(push).toHaveBeenLastCalledWith('/game/territory');
});

test('첩보 옵션을 못 읽으면 단추 없이 군만 · 시야 읽기 실패는 다시 시도, 원문(영어)은 보이지 않는다', async () => {
    vi.mocked(api.campaignScoutOptions).mockRejectedValue(new Error('500: Internal Server Error'));
    vi.mocked(api.campaignVisibility).mockRejectedValueOnce(new Error('500: Internal Server Error'));
    render(<IntelPage />);
    fireEvent.click(await screen.findByRole('button', { name: /다시 시도/ }));
    const intel = await screen.findByRole('region', { name: '첩보' });
    expect(within(intel).queryByRole('button', { name: '첩보' })).toBeNull();
    await waitFor(() => expect(api.campaignVisibility).toHaveBeenCalledTimes(2));
    expect(screen.queryByText(/Internal Server Error/)).toBeNull();
});

// ── 시야 출처 · 읽기 범위 ─────────────────────────────────────────────────────
afterEach(() => { document.cookie = 'sam_server=; max-age=0; path=/'; });

interface Deferred<T> { readonly promise: Promise<T>; readonly resolve: (v: T) => void; readonly reject: (e: unknown) => void }
function deferred<T>(): Deferred<T> {
    let resolve!: (v: T) => void;
    let reject!: (e: unknown) => void;
    const promise = new Promise<T>((res, rej) => { resolve = res; reject = rej; });
    return { promise, resolve, reject };
}
const sessionOf = (generalId: number, month = 3) => ({
    loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId,
    frontInfo: { global: { year: 200, month, turnPhase: 1 }, general: { hasGeneral: true, generalId, nationId: 1 } },
}) as unknown as GameSession;
const visionA: Visibility = {
    status: 'READY', commanderies: [{ no: 1, id: 'c1', name: '영천군', tier: 'FULL' }],
    sources: [{ kind: 'SELF', commanderyNo: 1, radius: 0, provinceId: '82828', refId: 7 }], invalidSourceRecords: 2,
};
const visionB: Visibility = {
    status: 'READY', commanderies: [{ no: 0, id: 'c0', name: '하남윤', tier: 'FULL' }],
    sources: [{ kind: 'TERRITORY', commanderyNo: 0, radius: 0 }], invalidSourceRecords: 0,
};
/** 앞 응답(A)의 흔적 — 郡國 이름 · 출처 종류 · 서버 기록 수. */
const A_MARKS = /영천군|내 위치|읽지 못한 출처 기록 2개/;

/** 시야 읽기를 붙잡아 두고 테스트가 끝내는 순서를 정한다. */
function holdVisibility() {
    const calls: { readonly generalId: number; readonly d: Deferred<Visibility> }[] = [];
    vi.mocked(api.campaignVisibility).mockImplementation((generalId: number) => {
        const d = deferred<Visibility>();
        calls.push({ generalId, d });
        return d.promise;
    });
    return calls;
}
const panelText = () => document.querySelector('[data-testid="intel-panel"]')?.textContent ?? '';
const busy = () => document.querySelector('[data-testid="intel-panel"] [aria-busy="true"]');
/** 커밋마다(Profiler onRender 는 DOM 을 바꾼 뒤 불린다) 시야 칸 글자를 적는다 — 첫 커밋부터 앞 응답이 없는지 본다. */
function renderTracked() {
    const commits: string[] = [];
    const tree = () => <Profiler id="intel" onRender={() => { commits.push(panelText()); }}><IntelPage /></Profiler>;
    const utils = render(tree());
    return { commits, rerender: () => utils.rerender(tree()) };
}
const stale = (commits: readonly string[]) => commits.filter((t) => A_MARKS.test(t));

test('READY 응답의 출처 · 서버 기록 수를 그린다 — 날 id 없음', async () => {
    vi.mocked(api.campaignVisibility).mockResolvedValue(visionA);
    render(<IntelPage />);
    const box = await screen.findByRole('region', { name: '내 시야 출처' });
    expect(within(box).getByRole('listitem')).toHaveTextContent('내 위치영천군 · 반경 0칸');
    expect(box).toHaveTextContent('읽지 못한 출처 기록 2개');
    expect(box.textContent).not.toMatch(/82828|SELF/);
});

test('A 출처가 보이던 중 B 로 바뀌면 첫 커밋부터 A 출처 · 기록 수가 없고 읽는 중이다', async () => {
    const calls = holdVisibility();
    const { commits, rerender } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => { calls[0].d.resolve(visionA); });
    expect(await screen.findByText('읽지 못한 출처 기록 2개')).toBeInTheDocument();
    vi.mocked(useGameSession).mockReturnValue(sessionOf(8));
    const from = commits.length;
    rerender();
    const after = commits.slice(from);
    expect(after.length).toBeGreaterThan(0);
    expect(after[0]).not.toMatch(A_MARKS);
    expect(stale(after)).toEqual([]);
    expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
    expect(busy()).not.toBeNull();
    await waitFor(() => expect(calls.map((c) => c.generalId)).toEqual([7, 8]));
});

test('B 읽는 중에 늦게 온 A 응답은 쓰이지 않고, B 응답의 출처만 보인다', async () => {
    const calls = holdVisibility();
    const { commits, rerender } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    vi.mocked(useGameSession).mockReturnValue(sessionOf(8));
    const from = commits.length;
    rerender();
    await waitFor(() => expect(calls.map((c) => c.generalId)).toEqual([7, 8]));
    await act(async () => { calls[0].d.resolve(visionA); });
    expect(panelText()).not.toMatch(A_MARKS);
    expect(busy()).not.toBeNull();
    await act(async () => { calls[1].d.resolve(visionB); });
    const box = await screen.findByRole('region', { name: '내 시야 출처' });
    expect(within(box).getByRole('listitem')).toHaveTextContent('우리 세력 영토하남윤 · 반경 0칸');
    expect(box).not.toHaveTextContent('읽지 못한 출처 기록');
    expect(stale(commits.slice(from))).toEqual([]);
});

test.each([
    ['서버 오류', new Error('500: Internal Server Error')],
    ['403', new Error('403: Forbidden')],
])('B 읽기가 %s 로 실패한 뒤 늦게 온 A 응답은 실패 모양을 덮지 않는다', async (_label, failure) => {
    const calls = holdVisibility();
    const { commits, rerender } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    vi.mocked(useGameSession).mockReturnValue(sessionOf(8));
    const from = commits.length;
    rerender();
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => { calls[1].d.reject(failure); });
    expect(await screen.findByText('시야를 불러오지 못했습니다')).toBeInTheDocument();
    await act(async () => { calls[0].d.resolve(visionA); });
    expect(screen.getByText('시야를 불러오지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
    expect(stale(commits.slice(from))).toEqual([]);
    expect(panelText()).not.toMatch(/Internal Server Error|Forbidden/);
});

test('다시 시도하면 앞 결과를 지우고 읽는 중 — 읽을 수 없음 응답의 출처는 그리지 않는다', async () => {
    const calls = holdVisibility();
    const { commits } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => { calls[0].d.resolve({ status: 'UNAVAILABLE', sources: visionA.sources, invalidSourceRecords: 2 }); });
    expect(await screen.findByText('시야를 계산하지 못했습니다')).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '내 시야 출처' })).toBeNull();
    expect(panelText()).not.toMatch(A_MARKS);
    const from = commits.length;
    fireEvent.click(screen.getByRole('button', { name: /다시 시도/ }));
    expect(commits.slice(from)[0]).not.toMatch(/시야를 계산하지 못했습니다/);
    expect(busy()).not.toBeNull();
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => { calls[1].d.resolve(visionA); });
    expect(await screen.findByText('읽지 못한 출처 기록 2개')).toBeInTheDocument();
});

test('순이 바뀌면 첫 커밋부터 앞 순 출처가 없고, 늦게 온 앞 순 응답은 쓰이지 않는다', async () => {
    const calls = holdVisibility();
    const { commits, rerender } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => { calls[0].d.resolve(visionA); });
    expect(await screen.findByText('읽지 못한 출처 기록 2개')).toBeInTheDocument();
    vi.mocked(useGameSession).mockReturnValue(sessionOf(7, 4));
    let from = commits.length;
    rerender();
    expect(stale(commits.slice(from))).toEqual([]);
    expect(busy()).not.toBeNull();
    await waitFor(() => expect(calls).toHaveLength(2));
    // 4월 응답이 오기 전에 5월로 또 넘어가고, 4월 응답이 늦게 온다.
    vi.mocked(useGameSession).mockReturnValue(sessionOf(7, 5));
    rerender();
    await waitFor(() => expect(calls).toHaveLength(3));
    from = commits.length;
    await act(async () => { calls[1].d.resolve(visionA); });
    expect(stale(commits.slice(from))).toEqual([]);
    expect(busy()).not.toBeNull();
    await act(async () => { calls[2].d.resolve(visionB); });
    expect(await screen.findByText('우리 세력 영토')).toBeInTheDocument();
    expect(panelText()).not.toMatch(A_MARKS);
});

test('서버가 바뀌면 첫 커밋부터 앞 서버 출처가 없고 다시 읽는다', async () => {
    document.cookie = 'sam_server=pep; path=/';
    const calls = holdVisibility();
    const { commits, rerender } = renderTracked();
    await waitFor(() => expect(calls).toHaveLength(1));
    await act(async () => { calls[0].d.resolve(visionA); });
    expect(await screen.findByText('읽지 못한 출처 기록 2개')).toBeInTheDocument();
    document.cookie = 'sam_server=che; path=/';
    const from = commits.length;
    rerender();
    expect(commits.length).toBeGreaterThan(from);
    expect(stale(commits.slice(from))).toEqual([]);
    expect(busy()).not.toBeNull();
    await waitFor(() => expect(calls).toHaveLength(2));
    await act(async () => { calls[1].d.resolve(visionB); });
    expect(await screen.findByText('우리 세력 영토')).toBeInTheDocument();
});
