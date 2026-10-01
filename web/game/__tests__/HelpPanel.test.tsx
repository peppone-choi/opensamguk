import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { configure, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { HelpPanel } from '../components/help/HelpPanel';
import { InputHelpStrip, TopicHelpStrip } from '../components/help/HelpStrip';
import { useReasonHelp } from '../hooks/useHelp';
import { __resetHelpCache } from '../lib/help';
import type { HelpView } from '../lib/help-route';

// useReasonHelp 는 서랍을 여는 onHelp(useOpenHelp → next/navigation)를 같이 돌려준다 — 앱 라우터 밖이라 흉내 낸다.
// 첫걸음 바로가기는 셸 세션의 서버로 주소를 만든다(CampaignLink).
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep' }) }));
vi.mock('next/navigation', () => ({
    usePathname: () => '/game/pep',
    useSearchParams: () => new URLSearchParams(''),
    useRouter: () => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }),
}));

// 첫 시험은 모듈 적재(콜드 스타트)를 떠안고, 전체 스위트 부하에서는 더 느려진다 — 한 파일만 돌리면 1초 안팎.
configure({ asyncUtilTimeout: 5000 });
vi.setConfig({ testTimeout: 20_000 });

const FIX = resolve(__dirname, '../../../docs/development/fixtures/help-tutorial');
const fixture = (name: string) => JSON.parse(readFileSync(resolve(FIX, `${name}.json`), 'utf-8'));
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

let routes: Record<string, () => Response>;
let fetchMock: ReturnType<typeof vi.fn>;

beforeEach(() => {
    __resetHelpCache();
    routes = {
        '/api/game/api/help/context?inputId=action.enlist': () => json(200, fixture('help-context-action-enlist')),
        '/api/game/api/help/topics/commands.action.enlist': () => json(200, fixture('help-topic-action-enlist')),
        '/api/game/api/help/failures/ALREADY_SERVING?inputId=action.enlist': () => json(200, fixture('help-failure-already-serving')),
        '/api/game/api/help/search?q=%EC%B6%9C%EC%82%AC&limit=20': () => json(200, fixture('help-search-enlist')),
    };
    fetchMock = vi.fn(async (url: string) => {
        const hit = routes[url];
        if (hit) return hit();
        if (url.startsWith('/api/game/api/help/context')) return json(404, { error: { code: 'INPUT_NOT_FOUND', message: '' } });
        return json(404, fixture('help-error-not-found'));
    });
    vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
});

function panel(view: HelpView, extra: Partial<Parameters<typeof HelpPanel>[0]> = {}) {
    const onNavigate = vi.fn();
    render(<HelpPanel view={view} onNavigate={onNavigate} screen="enlist" variant="drawer" onClose={vi.fn()} {...extra} />);
    return onNavigate;
}

test('input topic shows the draft prose, plain-word rules and failure count — never ids', async () => {
    const goToInput = vi.fn();
    panel({ kind: 'input', inputId: 'action.enlist' }, { goToInput });
    expect(await screen.findByRole('heading', { name: '출사' })).toBeInTheDocument();
    expect(screen.getAllByText('초안').length).toBeGreaterThan(0);
    expect(screen.getByText('섬길 주공을 정해 그 세력에 들어가는 직접 행동입니다.')).toBeInTheDocument();
    const rules = screen.getByRole('region', { name: '이 명령의 규칙' });
    expect(within(rules).getByText('장수 본인 · 내 장수')).toBeInTheDocument();
    expect(within(rules).getByText('명령 목록 12순 · 한 순에 하나 · 정치 단계')).toBeInTheDocument();
    expect(within(rules).getByText('— 상황에 따라')).toBeInTheDocument();
    // 원장 사유 15개 중 배관 코드 4개(WRONG_RULE_PROFILE · UNKNOWN_INPUT · INVALID_REQUEST · ACTOR_NOT_FOUND)는 목록에서 빠진다 — 제목도 보이는 수로 센다.
    const toggle = screen.getByRole('button', { name: /안 되는 경우 11가지/ });
    expect(document.body.textContent).not.toMatch(/action\.enlist|commands\.|ALREADY_SERVING|HANDLER_DEFINED/);
    fireEvent.click(toggle);
    fireEvent.click(screen.getByRole('button', { name: '6가지 더 보기' }));
    expect(toggle.parentElement!.querySelectorAll('ul li')).toHaveLength(11);
    fireEvent.click(screen.getByRole('button', { name: /이 명령 하러 가기/ }));
    expect(goToInput).toHaveBeenCalledWith('action.enlist');
});

test('a highlighted plumbing reason stays in the list and in the count', async () => {
    panel({ kind: 'input', inputId: 'action.enlist', reason: 'ACTOR_NOT_FOUND' });
    expect(await screen.findByRole('heading', { name: '출사' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /안 되는 경우 12가지/ })).toHaveAttribute('aria-expanded', 'true');
});

test('reason highlight opens the failure list with that reason first', async () => {
    const onNavigate = panel({ kind: 'input', inputId: 'action.enlist', reason: 'ALREADY_SERVING' });
    const first = await screen.findByText('이미 다른 주공을 섬기고 있습니다.');
    const row = first.closest('button')!;
    expect(row).toHaveAttribute('aria-current', 'true');
    fireEvent.click(row);
    expect(onNavigate).toHaveBeenCalledWith({ kind: 'failure', reason: 'ALREADY_SERVING', inputId: 'action.enlist' });
});

test('one character never reaches the server; composition waits; a real query searches once', async () => {
    const onNavigate = panel({ kind: 'home' });
    const box = screen.getByRole('searchbox');
    fireEvent.change(box, { target: { value: '출' } });
    expect(await screen.findByText('두 글자 이상 적어 주세요.')).toBeInTheDocument();
    fireEvent.compositionStart(box);
    fireEvent.change(box, { target: { value: '출사' } });
    await new Promise((r) => setTimeout(r, 400));
    expect(fetchMock.mock.calls.some(([u]) => String(u).includes('/search'))).toBe(false);
    fireEvent.compositionEnd(box, { currentTarget: { value: '출사' } });
    expect(await screen.findByText('결과 1개')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([u]) => String(u).includes('/search'))).toHaveLength(1);
    expect(onNavigate).toHaveBeenCalledWith({ kind: 'search', q: '출사' }, 'push');
});

test('home lists the screen inputs by name without waiting for the server, concepts show the pending state', async () => {
    panel({ kind: 'home' }, { screen: 'war-room' });
    expect(screen.getByRole('button', { name: /현장 행동\s*26/ })).toHaveAttribute('aria-pressed', 'true');
    expect(screen.getByText('농지개간')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '19개 더 보기' })).toBeInTheDocument();
    expect(screen.getByText('개념 도움말은 준비 중입니다')).toBeInTheDocument();
});

test.each([
    [503, { error: { code: 'WORLD_UNAVAILABLE', message: '' } }, '서버가 준비 중이라 도움말도 잠시 쉽니다'],
    [404, { error: { code: 'HELP_TOPIC_NOT_FOUND', message: '' } }, '이 도움말을 찾을 수 없습니다'],
    [500, {}, '도움말을 불러오지 못했습니다'],
])('topic %i shows its own state', async (status, body, text) => {
    routes['/api/game/api/help/topics/concepts.createGeneral'] = () => json(status, body);
    panel({ kind: 'topic', topicId: 'concepts.createGeneral' });
    expect(await screen.findByText(text)).toBeInTheDocument();
});

test('help strip hides when the topic is missing and shows success/failure on demand', async () => {
    const { container } = render(<TopicHelpStrip topicId="concepts.createGeneral" />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalled());
    await new Promise((r) => setTimeout(r, 0));
    expect(container).toBeEmptyDOMElement();
    const open = vi.fn();
    render(<InputHelpStrip inputId="action.enlist" onOpenHelp={open} />);
    fireEvent.click(await screen.findByRole('button', { name: '잘 되면 · 안 되면' }));
    expect(screen.getByText(/출사 결과가 확정되면/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '도움말 열기' }));
    expect(open).toHaveBeenCalled();
});

function ReasonProbe({ code, inputId }: { code: string; inputId: string }) {
    const r = useReasonHelp(code, inputId);
    return <output data-testid="probe">{JSON.stringify(r)}</output>;
}

test('reason help fills the K3 reason sheet props: recovery text and a help link that opens the input with this reason', async () => {
    render(<ReasonProbe code="ALREADY_SERVING" inputId="action.enlist" />);
    await waitFor(() => expect(screen.getByTestId('probe').textContent).toContain('recovery'));
    expect(JSON.parse(screen.getByTestId('probe').textContent!)).toEqual({
        recovery: '현재 소속 관계를 확인한 뒤 출사 가능한 상태에서 다시 시도하세요.',
        recoveryDraft: true,
        helpTopic: { id: 'input:action.enlist!ALREADY_SERVING', title: '출사' },
    });
});

test('an unknown reason leaves recovery empty (sheet still shows the server sentence) and warns once', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    render(<ReasonProbe code="MYSTERY" inputId="action.enlist" />);
    await waitFor(() => expect(warn).toHaveBeenCalled());
    expect(JSON.parse(screen.getByTestId('probe').textContent!)).toEqual({ recoveryDraft: false, helpTopic: { id: 'input:action.enlist!MYSTERY', title: '출사' } });
});

test('first steps (D21): eight steps to read — what · where · how, 「준비 중」 where it is not built, and a 44px shortcut to each real screen', () => {
    const onNavigate = panel({ kind: 'start' });
    const list = screen.getByRole('list', { name: '첫걸음 8단계' });
    const steps = within(list).getAllByRole('heading', { level: 3 }).map((h) => h.textContent);
    expect(steps).toEqual(['1단계 · 가입', '2단계 · 장수 생성', '3단계 · 출사', '4단계 · 발령', '5단계 · 공사', '6단계 · 등용', '7단계 · 행군', '8단계 · 전투']);
    // 진행 기록 · 완료 표시 · 잠김이 없다 — 설명만
    expect(document.body.textContent).not.toMatch(/완료|잠김|\d\s*\/\s*8|연습 서버/);
    expect([...list.querySelectorAll('[data-first-step-id]')].map((el) => el.getAttribute('data-first-step-id'))).toEqual([
        'tutorial.signup', 'tutorial.createGeneral', 'tutorial.enlist', 'tutorial.dispatch',
        'tutorial.work', 'tutorial.employ', 'tutorial.march', 'tutorial.battle',
    ]);
    const battle = list.querySelector('[data-first-step="battle"]') as HTMLElement;
    expect(within(battle).getByText('일부 준비 중')).toBeInTheDocument();
    expect(within(battle).getByText('서버가 아직 전투를 열지 않아 참가 대기 · 진행 중인 전투는 볼 수 없습니다.')).toBeInTheDocument();
    const hrefs = Object.fromEntries([...list.querySelectorAll<HTMLAnchorElement>('[data-first-step-go]')].map((a) => [a.dataset.firstStepGo, a.getAttribute('href')]));
    expect(hrefs).toEqual({
        register: '/join',
        create: '/game/pep/create',
        enlist: '/game/pep/join',
        dispatch: '/game/pep/court?tab=orders',
        work: '/game/pep/territory',
        employ: '/game/pep?do=action.search',
        march: '/game/pep?do=action.deploy',
        battle: '/game/pep/corps/battle',
    });
    expect(onNavigate).not.toHaveBeenCalled();
    // 진척 API 를 부르지 않는다(D21)
    expect(fetchMock.mock.calls.map((c) => String(c[0])).filter((u) => u.includes('tutorial'))).toEqual([]);
});
