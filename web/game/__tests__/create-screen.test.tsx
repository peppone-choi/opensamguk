// 새 장수 만들기(P-E02) — 생성 옵션 고정 자료로: 역할 · 본관 · 이름 · 능력 · 주의 · 개성 → 접수 → CREATED 면 출사로.
import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ACCEPTED, OPTIONS, RESULT_CREATED } from '@/lib/creation-fixtures';

const mocks = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn(), viewport: null as string | null }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push }),
    usePathname: () => '/game/pep/create',
    useSearchParams: () => new URLSearchParams(),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: () => ({ serverId: 'pep', refresh: mocks.refresh }) }));
vi.mock('@/components/campaign/CampaignLink', () => ({
    default: ({ slug, children, ...rest }: { slug: string; children: React.ReactNode }) => <a href={`/game/pep/${slug}`} {...rest}>{children}</a>,
}));
vi.mock('@opensamguk/ui', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@opensamguk/ui')>()),
    useViewportClass: () => mocks.viewport,
}));

import CreateScreen from '@/components/entry/CreateScreen';

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
let routes: Record<string, (init?: RequestInit) => Response>;
let posts: unknown[];

beforeEach(() => {
    posts = [];
    mocks.push.mockReset();
    mocks.refresh.mockReset();
    mocks.viewport = null;
    routes = {
        'GET /api/game/api/generals/creation/options': () => json(200, OPTIONS),
        'POST /api/game/api/generals/creation': (init) => { posts.push(JSON.parse(String(init?.body))); return json(202, ACCEPTED); },
        'GET /api/game/api/generals/creation/req-1': () => json(200, RESULT_CREATED),
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
        render(<CreateScreen />);
        await settle();
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
        render(<CreateScreen />);
        await settle();
        fill();
        fireEvent.click(screen.getByRole('button', { name: '무력 내리기' }));
        expect(screen.getByText('1점이 남았습니다 — 합이 300이어야 합니다')).toBeInTheDocument();
        expect(reasonOf(submitButton())).toHaveTextContent('1점이 남았습니다. 다섯 능력의 합이 300이어야 합니다.');
        fireEvent.change(screen.getByRole('spinbutton', { name: '통솔 값' }), { target: { value: '85' } });
        expect(screen.getByText('24점이 넘칩니다 — 합이 300이어야 합니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '고르게' }));
        expect(screen.getByText('합 300 — 맞습니다')).toBeInTheDocument();
        expect(submitButton()).not.toHaveAttribute('aria-disabled');
    });

    it('미리보기 — 이름 · 향당 · 주의 · 개성 칩, 신분 「재야 → 출사」', async () => {
        render(<CreateScreen />);
        await settle();
        fill();
        const preview = screen.getByRole('region', { name: '미리보기' });
        expect(within(preview).getByText('하후연')).toBeInTheDocument();
        for (const t of ['향당 · 허현', '주의 · 왕도', '개성 · 규율', '재야 → 출사']) expect(within(preview).getByText(t)).toBeInTheDocument();
    });

    it('접수(202, CUSTOM — 역할 칸 없음) → CREATED 면 세션을 다시 읽고 출사(join)로', async () => {
        render(<CreateScreen />);
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(posts).toHaveLength(1);
        const body = posts[0] as { expectedWorldId: number; choice: Record<string, unknown> };
        expect(body.expectedWorldId).toBe(1);
        expect(body.choice).toEqual({ kind: 'CUSTOM', name: '하후연', nativeCountyId: 11, stats: { leadership: 60, strength: 60, intel: 60, politics: 60, charm: 60 }, ideologyId: 'kingly', traitId: 'discipline' });
        expect(screen.getByText('장수를 만드는 중입니다')).toBeInTheDocument();
        await settle(1600);
        expect(mocks.refresh).toHaveBeenCalled();
        expect(mocks.push).toHaveBeenCalledWith('/game/pep/join');
    }, 10_000);

    it('접수 거절(409 NAME_ALREADY_USED)은 서버 문장 · 「입력으로 돌아가기」면 입력이 남아 있다', async () => {
        routes['POST /api/game/api/generals/creation'] = () => json(409, { error: { code: 'NAME_ALREADY_USED', message: '이미 쓰는 이름입니다. 다른 이름을 선택해 주세요.' } });
        render(<CreateScreen />);
        await settle();
        fill();
        await act(async () => { fireEvent.click(submitButton()); });
        expect(screen.getByRole('alert')).toHaveTextContent('이미 쓰는 이름입니다. 다른 이름을 선택해 주세요.');
        expect(screen.getByRole('link', { name: '역사 인물 고르기' })).toHaveAttribute('href', '/game/pep/create/historical');
        fireEvent.click(screen.getByRole('button', { name: '입력으로 돌아가기' }));
        expect(screen.getByRole('textbox', { name: '이름' })).toHaveValue('하후연');
    });

    it('정책이 닫혔으면(customAllowed=false) 생성 대기 + 사유', async () => {
        routes['GET /api/game/api/generals/creation/options'] = () => json(200, { ...OPTIONS, policy: { customAllowed: false, historicalAllowed: false, reason: 'CREATION_POLICY_UNAVAILABLE' } });
        render(<CreateScreen />);
        await settle();
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다 — 서버 준비 중')).toBeInTheDocument();
        expect(screen.getByText('장수 만들기가 아직 열리지 않았습니다. 잠시 후 다시 확인해 주세요.')).toBeInTheDocument();
    });

    it('모바일 — 걸음 다섯, 다음 · 이전, 마지막 걸음에 만들기 단추', async () => {
        mocks.viewport = 'mobile';
        render(<CreateScreen />);
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
