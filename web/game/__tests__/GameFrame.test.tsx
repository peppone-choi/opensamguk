import { act, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

// v3.1 셸 하나 — 레일(데스크톱) · 하단 탭(모바일)은 둘 다 그리고 CSS 가 하나만 보인다. 여기선 구조와 규칙만 본다.
const nav = vi.hoisted(() => ({ pathname: '/game/pep/retinue/yuedan', search: '' }));
const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), back: vi.fn() }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(nav.search),
    useRouter: () => router,
}));
vi.mock('next/link', () => ({
    default: ({ href, prefetch, scroll, children, ...rest }: { href: string; prefetch?: boolean; scroll?: boolean; children: React.ReactNode }) => (
        <a href={href} data-prefetch={String(prefetch)} data-scroll={scroll === undefined ? undefined : String(scroll)} {...rest}>{children}</a>
    ),
}));
vi.mock('@/lib/api', () => ({
    api: {
        frontInfo: () => Promise.resolve({
            global: { year: 200, month: 3, turnPhase: 1, turnPhaseText: '중순', ruleProfile: 'HWIHA' },
            general: { hasGeneral: true, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1 },
            nation: { id: 1, name: '조조' },
        }),
    },
}));
vi.mock('@/lib/campaign-reads', () => ({ useRenown: () => 12 }));
vi.mock('@/hooks/useSSE', () => ({ useSSE: () => undefined }));
vi.mock('@/hooks/usePresencePulse', () => ({ usePresencePulse: () => undefined }));
vi.mock('@/lib/serverGameUrl', async (importActual) => {
    const actual = await importActual<typeof import('@/lib/serverGameUrl')>();
    return { ...actual, useServerId: () => 'pep' };
});

import GameFrame, { seasonOf } from '../components/shell/GameFrame';

beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('offline'))));
});
afterEach(() => {
    vi.unstubAllGlobals();
    vi.clearAllMocks();
    nav.pathname = '/game/pep/retinue/yuedan';
    nav.search = '';
});

async function renderFrame() {
    const view = render(<GameFrame><p>본문</p></GameFrame>);
    await act(async () => { await Promise.resolve(); });
    return view;
}

describe('GameFrame — v3.1 셸 하나', () => {
    it('레일에 묶음 8개 + 도움말, 지금 묶음(부)에 aria-current, 주소엔 서버가 든다', async () => {
        await renderFrame();
        const [rail, tabbar] = screen.getAllByRole('navigation', { name: '게임 메뉴' });
        const items = within(rail).getAllByRole('link').map((a) => a.textContent);
        expect(items).toEqual(['작전실', '부', '계책', '영지', '군단', '조정', '기록', '광장', '도움말']);
        expect(within(rail).getByRole('link', { name: '부' })).toHaveAttribute('aria-current', 'page');
        expect(within(rail).getByRole('link', { name: '부' })).toHaveAttribute('href', '/game/pep/retinue');
        expect(within(rail).getByRole('link', { name: '작전실' })).toHaveAttribute('href', '/game/pep');
        // 모바일 하단 탭: 작전실 · 부 · 계책 · 기록 · 전체
        expect(within(tabbar).getAllByRole('link').map((a) => a.textContent)).toEqual(['작전실', '부', '계책', '기록']);
        expect(within(tabbar).getByRole('button', { name: '전체' })).toBeInTheDocument();
        expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('부');
    });

    it('머리줄: 계절 · 날짜, 다음 개인 턴은 서버 값이 없으니 「확인 중」, 소속 · 명망', async () => {
        await renderFrame();
        expect(screen.getByText('봄 · 200년 3월 중순')).toBeInTheDocument();
        expect(screen.getByText('다음 개인 턴 확인 중')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '하후돈 · 조조 소속' })).toHaveAttribute('href', '/game/pep/retinue');
        expect(screen.getByText('명망 12')).toBeInTheDocument();
    });

    it('턴 루프를 읽지 못하면 「운영 상태 확인 중」 띠(status) — 다시 확인은 다시 읽는다', async () => {
        await renderFrame();
        const band = await screen.findByText(/운영 상태 확인 중/);
        expect(band.closest('[data-band]')).toHaveAttribute('role', 'status');
        const calls = vi.mocked(fetch).mock.calls.length;
        expect(vi.mocked(fetch).mock.calls[0][0]).toBe('/api/server-basic-info/pep');
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: '다시 확인' })); });
        expect(vi.mocked(fetch).mock.calls.length).toBe(calls + 1);
    });

    it('턴이 멈추면 경보 띠(alert)', async () => {
        vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(new Response(JSON.stringify({
            game: { turnLoop: { state: 'STALLED', staleSeconds: 600 }, serverTime: '2026-10-01T12:00:00Z', month: 3, turnPhaseText: '중순' },
        })))));
        await renderFrame();
        expect(await screen.findByRole('alert')).toHaveTextContent('턴이 멈췄습니다 — 마지막 순 3월 중순, 멈춘 지 10분 (21:00 확인)');
    });

    it('「전체」는 모든 묶음을 여는 시트 — 없는 화면은 「준비 중」, Esc 로 닫힌다', async () => {
        await renderFrame();
        fireEvent.click(screen.getByRole('button', { name: '전체' }));
        const sheet = screen.getByRole('dialog', { name: '전체 메뉴' });
        expect(within(sheet).getByRole('link', { name: '월단평' })).toHaveAttribute('href', '/game/pep/retinue/yuedan');
        expect(within(sheet).getByText('역정보').closest('[aria-disabled]')).toHaveTextContent('준비 중');
        expect(within(sheet).getByRole('link', { name: '도움말' })).toHaveAttribute('href', '?help=home');
        fireEvent.keyDown(sheet, { key: 'Escape' });
        expect(screen.queryByRole('dialog', { name: '전체 메뉴' })).toBeNull();
    });

    it('입장(join)은 레일 · 하단 탭 없이 머리줄만, 로비로', async () => {
        nav.pathname = '/game/pep/join';
        await renderFrame();
        expect(screen.queryByRole('navigation', { name: '게임 메뉴' })).toBeNull();
        expect(screen.getByRole('link', { name: '로비로' })).toBeInTheDocument();
    });

    it('?help= 가 있으면 도움말 서랍(모달 아님)이 열리고 닫기는 그 쿼리를 뺀다', async () => {
        nav.search = 'help=home&person=3';
        await renderFrame();
        const drawer = screen.getByRole('complementary', { name: '도움말' });
        expect(screen.queryByRole('dialog')).toBeNull();
        // 「이 화면」은 셸이 찾은 지금 화면(부 · 월단평) — K7 본문이 든다.
        expect(await within(drawer).findByText('부에서 하는 일')).toBeInTheDocument(); // 본문은 열 때 받는다(lazy)
        fireEvent.click(within(drawer).getByRole('button', { name: '도움말 닫기(Esc)' }));
        expect(router.push).toHaveBeenLastCalledWith('/game/pep/retinue/yuedan?person=3', { scroll: false });
    });

    it('머리줄 · 레일 「도움말」은 쿼리만 바꾸는 링크 — 다른 쿼리는 둔다', async () => {
        nav.search = 'person=3';
        await renderFrame();
        expect(screen.getByRole('link', { name: '이 화면 도움말' })).toHaveAttribute('href', '?person=3&help=home');
        expect(screen.getByRole('link', { name: '이 화면 도움말' })).toHaveAttribute('data-scroll', 'false');
        const [rail] = screen.getAllByRole('navigation', { name: '게임 메뉴' });
        expect(within(rail).getByRole('link', { name: '도움말' })).toHaveAttribute('href', '?person=3&help=home');
    });

    it('계절은 달에서 — 봄 3–5 · 여름 6–8 · 가을 9–11 · 겨울 12–2', () => {
        expect([1, 3, 6, 9, 12].map(seasonOf)).toEqual(['겨울', '봄', '여름', '가을', '겨울']);
    });
});
