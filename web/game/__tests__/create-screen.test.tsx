// 새 장수 만들기(P-E02) — 생성 옵션 고정 자료로: 역할 · 본관 · 이름 · 능력 · 주의 · 개성 → 접수 → CREATED 면 출사로.
import { StrictMode } from 'react';
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectServerWait, expectServerWaitGone } from '@opensamguk/ui';
import { ACCEPTED, OPTIONS, RESULT_CREATED } from './fixtures/creation';

// 세션은 진짜(GameSessionProvider) — 출사로 넘어가는 순간 세션에 장수가 보이는지(#1329 리뷰: 옛 세션이면 출사가 입구로 되돌린다)를 잰다.
const mocks = vi.hoisted(() => ({
    viewport: null as string | null,
    sessionGeneral: null as number | null,
    pushed: [] as { href: string; sessionGeneral: number | null }[],
}));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: (href: string) => mocks.pushed.push({ href, sessionGeneral: mocks.sessionGeneral }) }),
    usePathname: () => '/game/pep/create',
    useSearchParams: () => new URLSearchParams(),
}));
// 세션이 장수를 다시 묻는 간격만 줄인다(접수 결과를 묻는 간격은 그대로)
vi.mock('@/hooks/useCreationRequest', async (importOriginal) => ({ ...(await importOriginal<typeof import('@/hooks/useCreationRequest')>()), RESULT_POLL_MS: 20 }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));

import CreateScreen from '@/components/entry/CreateScreen';
import { GameSessionProvider, useGameSession } from '@/lib/campaign-session';

function SessionProbe() {
    mocks.sessionGeneral = useGameSession().generalId;
    return null;
}
const inSession = (node: React.ReactNode = <CreateScreen />) => <GameSessionProvider><SessionProbe />{node}</GameSessionProvider>;
/** 세션이 읽는 front-info — 만들어지기 전엔 장수 없음, 만든 뒤엔 재야 장수 7. */
const frontInfo = (hasGeneral: boolean) => ({
    result: true,
    global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA', turnterm: 60, scenario: 's', scenarioText: 's', generalCount: 0, nationCount: 0, cityCount: 0, npcCount: 0 },
    general: { hasGeneral, generalId: hasGeneral ? 7 : 0, name: hasGeneral ? '하후연' : '', nationId: 0, officerLevel: 0, permission: 0, showSecret: false },
    nation: null, city: null, recentRecord: {},
});

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, (init?: RequestInit) => Response>;
let posts: unknown[];
/** 서버가 장수를 만들었는가 — front-info 가 이것을 따른다(접수 결과가 CREATED 일 때 켠다). */
let made: boolean;

beforeEach(() => {
    posts = [];
    made = false;
    mocks.viewport = null;
    mocks.sessionGeneral = null;
    mocks.pushed = [];
    document.cookie = 'sam_server=pep; path=/';
    routes = {
        'GET /api/game/api/front-info': () => json(200, frontInfo(made)),
        'GET /api/game/api/generals/creation/options': () => json(200, OPTIONS),
        'POST /api/game/api/generals/creation': (init) => { posts.push(JSON.parse(String(init?.body))); return json(202, ACCEPTED); },
        'GET /api/game/api/generals/creation/req-1': () => { made = true; return json(200, RESULT_CREATED); },
    };
    vi.stubGlobal('fetch', vi.fn(async (input: string, init?: RequestInit) => {
        const url = new URL(input, 'http://x');
        const handler = routes[`${init?.method ?? 'GET'} ${url.pathname}`];
        return handler ? handler(init) : json(404, {});
    }));
});
afterEach(() => vi.unstubAllGlobals());

async function settle(ms = 0) {
    await act(async () => { await new Promise((r) => setTimeout(r, ms)); });
}

const submitButton = () => screen.getByRole('button', { name: '만들고 섬길 주공 고르기' });
const reasonOf = (el: HTMLElement) => document.getElementById(el.getAttribute('aria-describedby')!.split(' ').pop()!)!;

function fill() {
    fireEvent.click(within(screen.getByRole('listbox', { name: '본관 현 후보' })).getByRole('option', { name: /허현/ }));
    fireEvent.change(screen.getByRole('textbox', { name: '이름' }), { target: { value: '하후연' } });
    fireEvent.click(within(screen.getByRole('group', { name: '주의' })).getByRole('button', { name: '왕도' }));
    fireEvent.click(within(screen.getByRole('group', { name: '개성' })).getByRole('button', { name: '규율' }));
}

describe('새 장수 만들기', () => {
    it('그려짐 — 역할 둘(예비 주공은 서버 대기 사유), 본관 후보(불가 사유), 능력 합 300, 적성 · 처음 명망은 서버 대기', async () => {
        const { container } = render(inSession());
        await settle();
        // 생성 옵션(K5-02)은 왔다 — 표지 없이 본관 후보가 보인다
        expectServerWaitGone(container, ['K5-02'], { value: '허현' });
        const roles = within(screen.getByRole('listbox', { name: '시작할 역할' })).getAllByRole('option');
        expect(roles[0]).toHaveAttribute('aria-selected', 'true');
        expect(roles[1]).toHaveAttribute('aria-disabled', 'true');
        expect(reasonOf(roles[1])).toHaveTextContent('예비 주공으로 시작하기는 서버가 아직 받지 않습니다.');
        expect(screen.getByText(/먼저 재야로 만들고, 다음 화면에서 섬길 주공을 고릅니다/)).toBeInTheDocument();
        expect(within(screen.getByRole('listbox', { name: '본관 현 후보' })).getAllByRole('option')).toHaveLength(4);
        expect(screen.getByText('합 300 — 맞습니다')).toBeInTheDocument();
        expect(screen.getByText('적성(장 · 리 · 사 · 사자)과 처음 명망은 만든 뒤 서버가 정합니다.')).toBeInTheDocument();
        expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
        expect(reasonOf(submitButton())).toHaveTextContent('본관 현을 고르세요.');
    });

    it('능력 −/+ · 고르게 — 남은 점수 줄과 사유가 따라간다', async () => {
        render(inSession());
        await settle();
        fill();
        fireEvent.click(screen.getByRole('button', { name: '무력 내리기' }));
        expect(screen.getByText('1점이 남았습니다 — 합이 300이어야 합니다')).toBeInTheDocument();
        expect(reasonOf(submitButton())).toHaveTextContent('1점이 남았습니다. 다섯 능력의 합이 300이어야 합니다.');
        fireEvent.change(screen.getByRole('spinbutton', { name: '통솔 값' }), { target: { value: '85' } });
        expect(screen.getByText('24점이 넘칩니다 — 합이 300이어야 합니다')).toBeInTheDocument();
        fireEvent.blur(screen.getByRole('spinbutton', { name: '통솔 값' }));
        fireEvent.click(screen.getByRole('button', { name: '고르게' }));
        expect(screen.getByText('합 300 — 맞습니다')).toBeInTheDocument();
        expect(submitButton()).not.toHaveAttribute('aria-disabled');
    });

    it('능력 값 칸 — 비우고 7 → 75 를 차례로 치면 75(치는 중엔 자르지 않는다), 칸을 떠날 때 범위 밖은 자르고 빈 칸은 되돌린다', async () => {
        render(inSession());
        await settle();
        const lead = () => screen.getByRole('spinbutton', { name: '통솔 값' });
        fireEvent.focus(lead());
        fireEvent.change(lead(), { target: { value: '' } });
        expect(lead()).toHaveValue(null);
        fireEvent.change(lead(), { target: { value: '7' } });
        expect(lead()).toHaveValue(7);
        // 아직 범위 밖(20 미만) — 합은 그대로
        expect(screen.getByText('합 300 — 맞습니다')).toBeInTheDocument();
        fireEvent.change(lead(), { target: { value: '75' } });
        expect(lead()).toHaveValue(75);
        expect(screen.getByText('15점이 넘칩니다 — 합이 300이어야 합니다')).toBeInTheDocument();
        fireEvent.blur(lead());
        expect(lead()).toHaveValue(75);
        // 범위 밖은 칸을 떠날 때(또는 Enter) 자른다
        fireEvent.change(lead(), { target: { value: '9' } });
        fireEvent.keyDown(lead(), { key: 'Enter' });
        expect(lead()).toHaveValue(20);
        // 빈 칸으로 떠나면 원래 값
        fireEvent.change(lead(), { target: { value: '' } });
        fireEvent.blur(lead());
        expect(lead()).toHaveValue(20);
    });

    it('미리보기 — 이름 · 향당 · 주의 · 개성 칩, 신분 「재야 → 출사」', async () => {
        render(inSession());
        await settle();
        fill();
        const preview = screen.getByRole('region', { name: '미리보기' });
        expect(within(preview).getByText('하후연')).toBeInTheDocument();
        for (const t of ['향당 · 허현', '주의 · 왕도', '개성 · 규율', '재야 → 출사']) expect(within(preview).getByText(t)).toBeInTheDocument();
    });

    it('접수(202, CUSTOM — 역할 칸 없음) → CREATED 면 세션을 다시 읽고 출사(join)로', async () => {
        render(inSession());
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(posts).toHaveLength(1);
        const body = posts[0] as { expectedWorldId: number; choice: Record<string, unknown> };
        expect(body.expectedWorldId).toBe(1);
        expect(body.choice).toEqual({ kind: 'CUSTOM', name: '하후연', nativeCountyId: 11, stats: { leadership: 60, strength: 60, intel: 60, politics: 60, charm: 60 }, ideologyId: 'kingly', traitId: 'discipline' });
        expect(screen.getByText('장수를 만드는 중입니다')).toBeInTheDocument();
        await settle(1600);
        await settle(50);
        // 출사로 넘어가는 순간 세션에 새 장수가 이미 보인다 — 옛 세션(장수 없음)이면 출사가 입구로 되돌린다
        expect(mocks.pushed).toEqual([{ href: '/game/pep/join', sessionGeneral: 7 }]);
    }, 10_000);

    it('CREATED 인데 front-info 에 장수가 끝내 안 보이면 넘어가지 않고 「아직 반영되지 않음 · 입구로」', async () => {
        routes['GET /api/game/api/front-info'] = () => json(200, frontInfo(false));
        render(inSession());
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        await settle(1600);
        await settle(200);
        expect(screen.getByText('아직 반영되지 않았습니다')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '입구로' })).toHaveAttribute('href', '/game/pep/');
        expect(mocks.pushed).toEqual([]);
    }, 10_000);

    it('StrictMode(개발 모드)에서도 접수 뒤 CREATED 면 출사로 넘어간다', async () => {
        render(<StrictMode>{inSession()}</StrictMode>);
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(screen.getByText('장수를 만드는 중입니다')).toBeInTheDocument();
        await settle(1600);
        await settle(50);
        expect(mocks.pushed).toEqual([{ href: '/game/pep/join', sessionGeneral: 7 }]);
    }, 10_000);

    it('접수 거절(409 NAME_ALREADY_USED)은 서버 문장 · 「입력으로 돌아가기」면 입력이 남아 있다', async () => {
        routes['POST /api/game/api/generals/creation'] = () => json(409, { error: { code: 'NAME_ALREADY_USED', message: '이미 쓰는 이름입니다. 다른 이름을 선택해 주세요.' } });
        render(inSession());
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(screen.getByRole('alert')).toHaveTextContent('이미 쓰는 이름입니다. 다른 이름을 선택해 주세요.');
        expect(screen.getByRole('link', { name: '역사 인물 고르기' })).toHaveAttribute('href', '/game/pep/create/historical');
        fireEvent.click(screen.getByRole('button', { name: '입력으로 돌아가기' }));
        expect(screen.getByRole('textbox', { name: '이름' })).toHaveValue('하후연');
    });

    it('옵션은 열렸는데 접수 경로가 없으면(본문 없는 404) 「연결 오류」가 아니라 접수 서버 대기(K5-01) — 입력은 남는다', async () => {
        // main #1319 뒤 상태: 옵션(K5-02)은 있고 접수(K5-01 #1137)는 컨트롤러가 없어 Spring 기본 404 본문
        routes['POST /api/game/api/generals/creation'] = () => json(404, { timestamp: 'x', status: 404, error: 'Not Found', path: '/api/generals/creation' });
        const { container } = render(inSession());
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(screen.getByText('장수 만들기 접수를 서버가 아직 받지 않습니다')).toBeInTheDocument();
        expect(screen.queryByText(/연결 오류|404/)).toBeNull();
        expectServerWait(container, ['K5-01']);
        fireEvent.click(screen.getByRole('button', { name: '입력으로 돌아가기' }));
        expect(screen.getByRole('textbox', { name: '이름' })).toHaveValue('하후연');
    });

    it('404 라도 서버가 문장(계약 본문)을 주면 그 문장 그대로 — 서버 대기 표지 없음', async () => {
        routes['POST /api/game/api/generals/creation'] = () => json(404, { error: { code: 'WORLD_NOT_FOUND', message: '이 서버의 월드를 찾을 수 없습니다.' } });
        const { container } = render(inSession());
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(screen.getByRole('alert')).toHaveTextContent('이 서버의 월드를 찾을 수 없습니다.');
        expectServerWait(container, []);
    });

    it('정책이 닫혔으면(customAllowed=false) 생성 대기 + 사유', async () => {
        routes['GET /api/game/api/generals/creation/options'] = () => json(200, { ...OPTIONS, policy: { customAllowed: false, historicalAllowed: false, reason: 'CREATION_POLICY_UNAVAILABLE' } });
        const { container } = render(inSession());
        await settle();
        // 서버가 정책 닫힘을 답했다 — 서버 대기 표지가 아니다
        expectServerWait(container, []);
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeInTheDocument();
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.')).toBeInTheDocument();
    });

    it('모바일 — 걸음 다섯, 다음 · 이전, 마지막 걸음에 만들기 단추', async () => {
        mocks.viewport = 'mobile';
        render(inSession());
        await settle();
        const steps = within(screen.getByRole('navigation', { name: '걸음' })).getAllByRole('button');
        expect(steps.map((s) => s.textContent)).toEqual(['1역할', '2본관', '3능력', '4주의 · 개성', '5확인']);
        expect(steps[0]).toHaveAttribute('aria-current', 'step');
        fireEvent.click(screen.getByRole('button', { name: '다음 — 본관' }));
        expect(screen.getByRole('listbox', { name: '본관 현 후보' })).toBeInTheDocument();
        fireEvent.click(steps[4]);
        expect(screen.getByRole('region', { name: '미리보기' })).toBeInTheDocument();
        expect(submitButton()).toHaveAttribute('aria-disabled', 'true');
    });
});
