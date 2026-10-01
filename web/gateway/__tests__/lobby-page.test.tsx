import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MapPreviewProps } from '@/components/MapPreview';

const mocks = vi.hoisted(() => ({
    user: { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 } as Record<string, unknown>,
    logout: vi.fn(),
    refresh: vi.fn(),
    servers: [] as { id: string; name: string; generation?: number }[],
    info: {} as Record<string, unknown | 'fail'>,
}));
vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/lib/auth-context', () => ({ useAuthOptional: () => ({ user: mocks.user, logout: mocks.logout }) }));
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh: mocks.refresh, push: vi.fn() }) }));
vi.mock('@/lib/serverRegistry', () => ({ getServers: () => mocks.servers, isValidEmptyServerRegistry: () => true }));
vi.mock('@/components/MapPreview', () => ({ default: ({ serverId }: MapPreviewProps) => <div data-testid="map" data-server={serverId} /> }));

import LobbyPage from '@/app/lobby/page';
import { lobbyVerdict, matchesFilter } from '@/lib/lobbyEntry';

const GAME = {
    isUnited: 0, year: 200, month: 3, turnPhaseText: '중순', scenario: '군웅할거', maxUserCnt: 30, turnTerm: 10,
    userCnt: 24, npcCnt: 412, nationCnt: 5, blockGeneralCreate: 0, status: 'OPEN', catchUp: { active: false, multiplier: 2 },
};
const ME = { name: '하후돈', picture: null, imageServer: 0 };
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
    vi.stubGlobal('React', React);
    mocks.logout.mockReset();
    mocks.user = { id: 1, username: 'hahoudon', nickname: '원양', role: 'USER', email: null, picture: null, imageServer: 0 };
    mocks.servers = [
        { id: 'pep', name: 'pep', generation: 1 },
        { id: 'uni', name: '통일 서버', generation: 3 },
        { id: 's2', name: 's2', generation: 7 },
        { id: 'old', name: 'old' },
    ];
    mocks.info = {
        pep: { game: GAME, me: ME },
        uni: { game: { ...GAME, userCnt: 12, catchUp: { active: true, multiplier: 2 } }, me: null },
        s2: { game: { ...GAME, userCnt: 30 }, me: null },
        old: 'fail',
    };
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        const basic = url.match(/^\/api\/server-basic-info\/(.+)$/);
        if (basic) {
            const body = mocks.info[decodeURIComponent(basic[1])];
            return body === 'fail' || body === undefined ? json({ error: 'down' }, 502) : json(body);
        }
        if (url === '/api/notices') return json({ notices: [] });
        if (url.startsWith('/api/server-events/')) return json({ events: [] });
        if (url.startsWith('/api/server-imperial/')) return json({ status: 'NOT_SEEDED', badges: [] });
        throw new Error(`unexpected request: ${url}`);
    }));
});
afterEach(() => vi.unstubAllGlobals());

const card = (name: string) => screen.getByRole('article', { name });

describe('P-G04 로비 — 판정 표', () => {
    it.each([
        ['불러오는 중', true, null, 'loading'],
        ['응답 없음', false, null, 'noResponse'],
        ['점검 중(내 장수가 있어도)', false, { game: { ...GAME, status: 'CLOSED' }, me: ME }, 'maintenance'],
        ['준비 중', false, { game: { ...GAME, status: 'PRE_OPEN' }, me: null }, 'preOpen'],
        ['참가 중', false, { game: GAME, me: ME }, 'joined'],
        ['시즌 끝 · 통일', false, { game: { ...GAME, isUnited: 2 }, me: null }, 'seasonEnded'],
        ['생성 금지', false, { game: { ...GAME, blockGeneralCreate: 1 }, me: null }, 'full'],
        ['정원 참', false, { game: { ...GAME, userCnt: 30 }, me: null }, 'full'],
        ['모집 중', false, { game: GAME, me: null }, 'recruiting'],
    ] as const)('%s', (_label, loading, info, kind) => {
        expect(lobbyVerdict(loading, info as never).kind).toBe(kind);
    });

    it('거르기 — 참가 가능은 모집 중만, 닫힘은 응답 없음 · 점검 · 준비', () => {
        expect(matchesFilter({ kind: 'full', reason: 'x' }, 'available')).toBe(false);
        expect(matchesFilter({ kind: 'recruiting' }, 'available')).toBe(true);
        for (const kind of ['noResponse', 'maintenance', 'preOpen'] as const) expect(matchesFilter({ kind }, 'closed')).toBe(true);
        expect(matchesFilter({ kind: 'loading' }, 'ended')).toBe(true);
    });
});

describe('P-G04 로비 — 화면', () => {
    it('카드마다 상태 칩 · 정보 줄 · 주 단추(참가 중 입장 · 모집 중 장수 만들기 · 마감은 사유)', async () => {
        render(<LobbyPage />);
        await waitFor(() => expect(within(card('pep')).getByText('참가 중')).toBeInTheDocument());
        const pep = card('pep');
        expect(within(pep).getByText('200년 3월 중순 ·', { exact: false })).toBeInTheDocument();
        expect(within(pep).getByText('세력 5 · 사람 24 / 30 · NPC 412')).toBeInTheDocument();
        expect(within(pep).getByText('한 순 10분')).toBeInTheDocument();
        expect(within(pep).getByRole('img', { name: '하후돈' })).toBeInTheDocument();
        expect(within(pep).getByRole('link', { name: '입장' })).toHaveAttribute('href', expect.stringContaining('pep'));

        await waitFor(() => expect(within(card('통일 서버')).getByText('모집 중')).toBeInTheDocument());
        expect(within(card('통일 서버')).getByText('따라잡는 중 · 2배속')).toBeInTheDocument();
        expect(within(card('통일 서버')).getByRole('link', { name: '장수 만들기' })).toHaveAttribute('href', expect.stringContaining('join'));

        await waitFor(() => expect(within(card('s2')).getByText('마감')).toBeInTheDocument());
        const full = within(card('s2')).getByRole('button', { name: '장수 만들기' });
        expect(full).toHaveAttribute('aria-disabled', 'true');
        expect(full).toHaveAttribute('data-reason', '사람 장수 30 / 30');
    });

    it('응답 없음 카드는 그 카드만 다시 시도한다', async () => {
        render(<LobbyPage />);
        await waitFor(() => expect(within(card('old')).getByText('응답 없음')).toBeInTheDocument());
        mocks.info.old = { game: GAME, me: null };
        fireEvent.click(within(card('old')).getByRole('button', { name: '다시 시도' }));
        await waitFor(() => expect(within(card('old')).getByText('모집 중')).toBeInTheDocument());
    });

    it('거르기 칩 — 참가 가능은 마감 카드를 뺀다', async () => {
        render(<LobbyPage />);
        await waitFor(() => expect(within(card('s2')).getByText('마감')).toBeInTheDocument());
        await waitFor(() => expect(within(card('old')).getByText('응답 없음')).toBeInTheDocument());
        fireEvent.click(screen.getByRole('button', { name: '참가 가능' }));
        expect(screen.getByRole('button', { name: '참가 가능' })).toHaveAttribute('aria-pressed', 'true');
        expect(screen.getByRole('article', { name: '통일 서버' })).toBeVisible();
        expect(screen.queryByRole('article', { name: 's2' })).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: '닫힘' }));
        expect(screen.getByRole('article', { name: 'old' })).toBeVisible();
        expect(screen.queryByRole('article', { name: 'pep' })).toBeNull();
    });

    it('현황 펼치기 — 그 서버 하나만 지도 · 세력 현황 · 천하 정세를 연다', async () => {
        render(<LobbyPage />);
        expect(screen.queryByTestId('map')).toBeNull();
        fireEvent.click(within(card('pep')).getByRole('button', { name: '현황 펼치기' }));
        expect(screen.getAllByTestId('map')).toHaveLength(1);
        expect(within(card('pep')).getByRole('region', { name: '세력 현황' })).toBeInTheDocument();
        expect(within(card('pep')).getByRole('region', { name: '천하 정세' })).toBeInTheDocument();
        fireEvent.click(within(card('통일 서버')).getByRole('button', { name: '현황 펼치기' }));
        expect(screen.getAllByTestId('map')).toHaveLength(1);
        expect(screen.getByTestId('map')).toHaveAttribute('data-server', 'uni');
    });

    it('머리줄 한 곳에 로비 · 커뮤니티 · 계정 · 로그아웃(관리는 운영자만) · 모바일 메뉴 시트', async () => {
        const { unmount } = render(<LobbyPage />);
        const nav = screen.getByRole('navigation', { name: '게이트웨이 메뉴' });
        expect(within(nav).getByRole('link', { name: '로비' })).toHaveAttribute('aria-current', 'page');
        expect(within(nav).getByRole('link', { name: '커뮤니티' })).toHaveAttribute('href', '/board');
        expect(within(nav).getByRole('link', { name: '계정' })).toHaveAttribute('href', '/account');
        expect(within(nav).queryByRole('link', { name: '관리' })).toBeNull();
        expect(screen.getByText('원양')).toBeInTheDocument();
        fireEvent.click(screen.getAllByRole('button', { name: '로그아웃' })[0]);
        expect(mocks.logout).toHaveBeenCalled();
        fireEvent.click(screen.getByRole('button', { name: '메뉴' }));
        const sheet = screen.getByRole('dialog', { name: '메뉴' });
        expect(within(sheet).getByRole('link', { name: '로비' })).toHaveFocus();
        fireEvent.keyDown(document, { key: 'Escape' });
        expect(screen.queryByRole('dialog', { name: '메뉴' })).toBeNull();
        unmount();
        mocks.user = { ...mocks.user, role: 'ADMIN' };
        render(<LobbyPage />);
        expect(within(screen.getByRole('navigation', { name: '게이트웨이 메뉴' })).getByRole('link', { name: '관리' })).toHaveAttribute('href', '/admin');
    });

    it('첫걸음 카드는 연습 서버 표지가 오기 전까지 준비 중, 각주는 승인 문구(D18), 삼모 표기는 없다', async () => {
        render(<LobbyPage />);
        expect(screen.getByRole('region', { name: '첫걸음 — 연습 서버' })).toHaveTextContent('연습 서버 준비 중');
        for (const note of screen.getAllByText(/계정/, { selector: 'li' })) expect(note).toHaveAttribute('data-copy-status', 'approved');
        expect(screen.queryByText(/문구 초안/)).not.toBeInTheDocument();
        await waitFor(() => expect(within(card('pep')).getByText('참가 중')).toBeInTheDocument());
        const text = document.body.textContent ?? '';
        for (const legacy of ['상성', '기타:', '§', '서기', '전콘', '폐 쇄', '미 등 록', '로 그 아 웃', '(ADMIN만)', '경쟁중']) expect(text).not.toContain(legacy);
    });
});
