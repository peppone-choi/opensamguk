import { act, fireEvent, render, screen, within } from '@testing-library/react';
import * as matchers from '@testing-library/jest-dom/matchers';
import { installViewport } from '@opensamguk/ui';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

expect.extend(matchers);

// v3.1 셸 하나 — 레일(데스크톱) · 하단 탭(모바일)은 둘 다 그리고 CSS 가 하나만 보인다. 여기선 구조와 규칙만 본다.
const nav = vi.hoisted(() => ({ pathname: '/game/pep/retinue/yuedan', search: '' }));
const entrySession = vi.hoisted(() => ({ hasGeneral: true, unknown: false }));
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
        frontInfo: () => entrySession.unknown ? new Promise(() => {}) : Promise.resolve({
            global: { year: 200, month: 3, turnPhase: 2, turnPhaseText: '중순', ruleProfile: 'HWIHA' },
            general: { hasGeneral: entrySession.hasGeneral, generalId: 7, name: '하후돈', nationId: 1, officerLevel: 1 },
            nation: { id: 1, name: '조조' },
        }),
    },
}));
vi.mock('@/lib/campaign-reads', () => ({ useRenown: () => 12 }));
const seasonNews = vi.hoisted(() => ({ on: false }));
vi.mock('@/lib/season', async (importActual) => ({
    ...(await importActual<typeof import('@/lib/season')>()),
    hasSeasonNews: () => seasonNews.on,
}));
vi.mock('@/hooks/useSSE', () => ({ useSSE: () => undefined }));
vi.mock('@/hooks/usePresencePulse', () => ({ usePresencePulse: () => undefined }));
vi.mock('@/lib/serverGameUrl', async (importActual) => {
    const actual = await importActual<typeof import('@/lib/serverGameUrl')>();
    return { ...actual, useServerId: () => 'pep' };
});

import GameFrame, { seasonOf } from '../components/shell/GameFrame';
import { NAV31 } from '../lib/nav31';

beforeEach(() => {
    vi.stubGlobal('fetch', vi.fn(() => Promise.reject(new Error('offline'))));
});
afterEach(() => {
    vi.unstubAllGlobals();
    seasonNews.on = false;
    entrySession.hasGeneral = true;
    entrySession.unknown = false;
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
        // 계절 칩 그림은 공용 스프라이트의 정본 season(장식) — 인라인 SVG 사본이 아니다.
        const glyph = screen.getByText('봄 · 200년 3월 중순').closest('button')!.querySelector('svg[data-icon="season"]');
        expect(glyph).not.toBeNull();
        expect(glyph!.querySelector('use')).toHaveAttribute('href', '/icons/icons.svg#ico-season');
        expect(glyph).toHaveAttribute('aria-hidden', 'true');
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
        // 「준비 중」은 built 도 지금 화면(current)도 없는 칸이다 — 화면이 켜져도 이 시험을 고치지 않게 NAV31 에서 고른다(K3 10-02).
        const pending = NAV31.flatMap((g) => g.screens).find((s) => !s.built && !s.current);
        if (pending) expect(within(sheet).getByText(pending.label).closest('[aria-disabled]')).toHaveTextContent('준비 중');
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

    it.each(['join', 'register', 'create', 'create/historical'])('입장 경로 %s는 명령 레일 · 하단 탭 없이 로비와 도움말을 유지한다', async (path) => {
        nav.pathname = `/game/pep/${path}`;
        await renderFrame();
        expect(screen.queryByRole('navigation', { name: '게임 메뉴' })).toBeNull();
        expect(screen.queryByRole('button', { name: '전체' })).toBeNull();
        expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('입장');
        expect(screen.getByRole('link', { name: '로비로' })).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '이 화면 도움말' })).toBeInTheDocument();
    });

    it.each(['/game/pep', '/game/pep/'])('무장수 루트 %s는 권위 있는 hasGeneral=false로 입장 셸을 고른다', async (path) => {
        nav.pathname = path;
        entrySession.hasGeneral = false;
        await renderFrame();
        expect(screen.queryByRole('navigation', { name: '게임 메뉴' })).toBeNull();
        expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent('입장');
        expect(screen.getByRole('link', { name: '로비로' })).toBeInTheDocument();
    });

    it('루트 세션이 UNKNOWN이면 무장수로 추정하지 않는다', async () => {
        nav.pathname = '/game/pep';
        entrySession.unknown = true;
        await renderFrame();
        expect(screen.getAllByRole('navigation', { name: '게임 메뉴' })).toHaveLength(2);
        expect(screen.getByRole('heading', { level: 1 })).not.toHaveTextContent('입장');
    });

    it('장수 있는 루트와 무장수의 다른 화면은 기존 게임 셸 판정을 유지한다', async () => {
        nav.pathname = '/game/pep';
        const view = await renderFrame();
        expect(screen.getAllByRole('navigation', { name: '게임 메뉴' })).toHaveLength(2);
        view.unmount();
        nav.pathname = '/game/pep/retinue';
        entrySession.hasGeneral = false;
        await renderFrame();
        expect(screen.getAllByRole('navigation', { name: '게임 메뉴' })).toHaveLength(2);
    });

    it('?help= 가 있으면 도움말 서랍(모달 아님)이 열리고 닫기는 그 쿼리를 뺀다', async () => {
        nav.search = 'help=home&person=3';
        await renderFrame();
        const drawer = screen.getByRole('complementary', { name: '도움말' });
        expect(screen.queryByRole('dialog')).toBeNull();
        // 「이 화면」은 셸이 찾은 지금 화면(부 · 월단평) — K7 본문이 든다.
        // 본문은 서랍을 열 때 lazy 로 받는다. 시험 환경(jsdom)에서는 첫 lazy import 가 모듈 변환까지 떠안아 부하 200+ 에서
        // 기본 1초를 넘긴다(K4 보고 — 혼자 돌리면 통과). 제품 로딩이 아니라 시험 대기 문제라, 도달 신호(서랍 제목)를 넉넉히 기다린다.
        await within(drawer).findByRole('heading', { name: '도움말', level: 2 }, { timeout: 15_000 });
        expect(within(drawer).getByText('부에서 하는 일')).toBeInTheDocument();
        fireEvent.click(within(drawer).getByRole('button', { name: '도움말 닫기(Esc)' }));
        expect(router.push).toHaveBeenLastCalledWith('/game/pep/retinue/yuedan?person=3', { scroll: false });
    }, 30_000);

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

// 계절 칩(K8 · 셸, 보드 V31SystemSeason · MSeason) — 데스크톱은 머리줄 아래 떠 있는 패널, 모바일은 하단 시트.
describe('GameFrame — 계절 칩', () => {
    const chipName = /^봄 · 200년 3월/;

    it('칩은 button(글자가 이름) — 누르면 패널이 열리고 닫기로 닫히며 초점이 칩으로 돌아온다(데스크톱)', async () => {
        const vp = installViewport(1440);
        try {
            await renderFrame();
            const chip = screen.getByRole('button', { name: chipName });
            expect(chip).toHaveAttribute('aria-haspopup', 'dialog');
            expect(chip).toHaveAttribute('aria-expanded', 'false');
            expect(chip).not.toHaveAttribute('aria-label');
            fireEvent.click(chip);
            const dialog = screen.getByRole('dialog', { name: '계절 — 봄' });
            expect(chip).toHaveAttribute('aria-expanded', 'true');
            expect(chip).toHaveAttribute('aria-controls', dialog.id);
            expect(chip).toHaveTextContent('3월 중순');
            expect(within(dialog).getByRole('img', { name: '1년 36순 달력 — 지금 3월 중순' })).toBeInTheDocument(); // 칩 글자와 같은 순
            expect(within(dialog).getByRole('button', { name: '계절 닫기' })).toHaveFocus(); // 열면 초점은 닫기
            vi.useFakeTimers();
            fireEvent.click(within(dialog).getByRole('button', { name: '계절 닫기' }));
            act(() => { vi.runAllTimers(); });
            vi.useRealTimers();
            expect(screen.queryByRole('dialog', { name: '계절 — 봄' })).toBeNull();
            expect(chip).toHaveFocus();
            expect(chip).toHaveAttribute('aria-expanded', 'false');
        } finally {
            vp.restore();
        }
    });

    it('Esc · 바깥 누름으로 닫힌다 — 패널 안을 눌러서는 닫히지 않는다(투명 덮개 없음)', async () => {
        const vp = installViewport(1440);
        try {
            await renderFrame();
            const chip = screen.getByRole('button', { name: chipName });
            fireEvent.click(chip);
            const dialog = screen.getByRole('dialog', { name: '계절 — 봄' });
            fireEvent.pointerDown(within(dialog).getByRole('heading', { name: '계절 — 봄' }));
            expect(screen.getByRole('dialog', { name: '계절 — 봄' })).toBeInTheDocument();
            fireEvent.keyDown(dialog, { key: 'Escape' });
            expect(screen.queryByRole('dialog')).toBeNull();
            fireEvent.click(chip);
            expect(screen.getAllByRole('button', { name: '계절 닫기' })).toHaveLength(1); // 데스크톱엔 덮개 단추가 없다
            fireEvent.pointerDown(screen.getByText('본문'));
            expect(screen.queryByRole('dialog')).toBeNull();
        } finally {
            vp.restore();
        }
    });

    it('바깥 누름은 누른 곳의 초점을 지킨다(칩으로 되돌리지 않는다), 닫기 · Esc 는 칩으로 되돌린다', async () => {
        const vp = installViewport(1440);
        try {
            render(<GameFrame><label>쓰기<input /></label></GameFrame>);
            await act(async () => { await Promise.resolve(); });
            const chip = screen.getByRole('button', { name: chipName });
            const input = screen.getByRole('textbox', { name: '쓰기' });
            vi.useFakeTimers();
            fireEvent.click(chip);
            fireEvent.pointerDown(input); // 바깥 누름 → 닫힘, 그다음 mousedown 기본 동작이 입력칸에 초점
            input.focus();
            act(() => { vi.runAllTimers(); });
            expect(screen.queryByRole('dialog')).toBeNull();
            expect(input).toHaveFocus();
            fireEvent.click(chip);
            fireEvent.keyDown(screen.getByRole('dialog', { name: '계절 — 봄' }), { key: 'Escape' });
            act(() => { vi.runAllTimers(); });
            expect(chip).toHaveFocus();
            vi.useRealTimers();
        } finally {
            vi.useRealTimers();
            vp.restore();
        }
    });

    it('키보드로 패널 밖 입력칸에 간 뒤 Esc — 닫기만 하고 초점은 입력칸에, 한글 조합 중 Esc 는 닫지 않는다', async () => {
        const vp = installViewport(1440);
        try {
            render(<GameFrame><label>쓰기<input /></label></GameFrame>);
            await act(async () => { await Promise.resolve(); });
            const input = screen.getByRole('textbox', { name: '쓰기' });
            vi.useFakeTimers();
            fireEvent.click(screen.getByRole('button', { name: chipName }));
            input.focus(); // Tab 으로 패널 밖에 간 것(누름 없음)
            fireEvent.keyDown(input, { key: 'Escape', isComposing: true }); // 조합 취소 — 패널은 그대로
            expect(screen.getByRole('dialog', { name: '계절 — 봄' })).toBeInTheDocument();
            fireEvent.keyDown(input, { key: 'Escape' });
            act(() => { vi.runAllTimers(); });
            expect(screen.queryByRole('dialog')).toBeNull();
            expect(input).toHaveFocus();
        } finally {
            vi.useRealTimers();
            vp.restore();
        }
    });

    it('패널 안 초점 못 받는 곳을 눌러 초점이 빠져도 Esc 가 듣는다(데스크톱 · 모바일)', async () => {
        for (const width of [1440, 390]) {
            const vp = installViewport(width);
            try {
                const view = await renderFrame();
                fireEvent.click(screen.getByRole('button', { name: chipName }));
                const dialog = screen.getByRole('dialog', { name: '계절 — 봄' });
                fireEvent.pointerDown(within(dialog).getByRole('img'));
                (document.activeElement as HTMLElement | null)?.blur();
                fireEvent.keyDown(document.body, { key: 'Escape' });
                expect(screen.queryByRole('dialog', { name: '계절 — 봄' }), `${width}`).toBeNull();
                view.unmount();
            } finally {
                vp.restore();
            }
        }
    });

    it('모바일은 하단 시트 + 덮개, 「전체」 메뉴와 동시에 열리지 않는다', async () => {
        const vp = installViewport(390);
        try {
            await renderFrame();
            fireEvent.click(screen.getByRole('button', { name: '전체' }));
            expect(screen.getByRole('dialog', { name: '전체 메뉴' })).toBeInTheDocument();
            fireEvent.click(screen.getByRole('button', { name: chipName }));
            expect(screen.queryByRole('dialog', { name: '전체 메뉴' })).toBeNull();
            const sheet = screen.getByRole('dialog', { name: '계절 — 봄' });
            expect(screen.getAllByRole('button', { name: '계절 닫기' })).toHaveLength(2); // 덮개 + 닫기
            expect(sheet.closest('header')).toBeNull(); // 머리줄 밖(시트 층)
            fireEvent.click(screen.getAllByRole('button', { name: '계절 닫기' })[0]);
            expect(screen.queryByRole('dialog')).toBeNull();
        } finally {
            vp.restore();
        }
    });

    it('도움말 서랍과 한 층 — 서랍이 열려 있으면 계절을 열 때 ?help= 만 빼고, 서랍이 열리면 계절이 닫힌다', async () => {
        const vp = installViewport(1440);
        try {
            nav.search = 'help=home&tab=bonds';
            const view = await renderFrame();
            expect(screen.getByRole('complementary', { name: '도움말' })).toBeInTheDocument();
            fireEvent.click(screen.getByRole('button', { name: chipName }));
            expect(screen.getByRole('dialog', { name: '계절 — 봄' })).toBeInTheDocument();
            expect(router.replace).toHaveBeenCalledWith('/game/pep/retinue/yuedan?tab=bonds', { scroll: false });
            nav.search = 'tab=bonds'; // 주소가 바뀌면 서랍이 사라지고 계절은 그대로
            view.rerender(<GameFrame><p>본문</p></GameFrame>);
            expect(screen.queryByRole('complementary', { name: '도움말' })).toBeNull();
            expect(screen.getByRole('dialog', { name: '계절 — 봄' })).toBeInTheDocument();
            nav.search = 'tab=bonds&help=home'; // 도움말을 열면(클라이언트 이동) 계절이 닫힌다
            view.rerender(<GameFrame><p>본문</p></GameFrame>);
            expect(screen.queryByRole('dialog', { name: '계절 — 봄' })).toBeNull();
            expect(screen.getByRole('complementary', { name: '도움말' })).toBeInTheDocument();
        } finally {
            vp.restore();
        }
    });

    it('모바일 「전체」도 같은 층 — 서랍이 열려 있으면 ?help= 를 빼고 연다, 서랍이 없으면 주소를 건드리지 않는다', async () => {
        const vp = installViewport(390);
        try {
            const view = await renderFrame();
            fireEvent.click(screen.getByRole('button', { name: '전체' }));
            expect(screen.getByRole('dialog', { name: '전체 메뉴' })).toBeInTheDocument();
            expect(router.replace).not.toHaveBeenCalled();
            view.unmount();
            nav.search = 'help=home';
            await renderFrame();
            fireEvent.click(screen.getByRole('button', { name: '전체' }));
            expect(screen.getByRole('dialog', { name: '전체 메뉴' })).toBeInTheDocument();
            expect(router.replace).toHaveBeenCalledWith('/game/pep/retinue/yuedan', { scroll: false });
        } finally {
            vp.restore();
        }
    });

    it('소식 점은 서버 소식이 있을 때만 — 색만이 아니라 「새 소식」 글자', async () => {
        await renderFrame();
        expect(screen.queryByText('새 소식')).toBeNull();
    });

    it('소식이 있으면(hasSeasonNews true) 칩 안에 「새 소식」', async () => {
        seasonNews.on = true;
        await renderFrame();
        const chip = screen.getByRole('button', { name: /^봄 · 200년 3월 .*새 소식$/ });
        expect(within(chip).getByText('새 소식')).toBeInTheDocument();
    });
});
