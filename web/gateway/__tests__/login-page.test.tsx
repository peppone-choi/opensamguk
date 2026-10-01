import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { useEffect } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { MapData, MapPreviewProps } from '@/components/MapPreview';
import { AUTH_LABELS } from '@/lib/constants';

const mocks = vi.hoisted(() => ({
    push: vi.fn(), refresh: vi.fn(), login: vi.fn(), next: vi.fn(),
    servers: [] as { id: string; name: string; generation?: number }[],
    emptyValid: true,
    mapProps: [] as { serverId?: string; variant?: string }[],
    previews: {} as Record<string, MapData | 'fail'>,
}));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }),
    useSearchParams: () => ({ get: mocks.next }),
}));
vi.mock('@/lib/client', () => ({ login: mocks.login }));
vi.mock('@/lib/serverRegistry', () => ({
    getServers: () => mocks.servers,
    isValidEmptyServerRegistry: () => mocks.emptyValid,
}));
// 지도 캔버스는 이 테스트 밖이다(MapPreview.world.test). 받은 미리보기를 onPreview 로 넘기는 계약만 흉내 낸다.
vi.mock('@/components/MapPreview', () => ({
    default: function FakeMap({ serverId, variant, onPreview, onPreviewError }: MapPreviewProps) {
        mocks.mapProps.push({ serverId, variant });
        useEffect(() => {
            const data = mocks.previews[serverId ?? ''];
            if (data === 'fail') onPreviewError?.();
            else if (data) onPreview?.(data);
        }, [serverId, onPreview, onPreviewError]);
        return <div data-testid="map" data-server={serverId} data-variant={variant} />;
    },
}));

import LoginPage from '@/app/login/page';

const PEP: MapData = {
    serverName: 'pep', year: 200, month: 3, turnPhaseText: '중순', mapCode: 'han-world-v3', width: 1, height: 1,
    cities: [
        { id: 12, name: '허', displayName: '허현', level: 5, nationId: 1, x: 0, y: 0 },
        { id: 13, name: '업', level: 5, nationId: 2, x: 0, y: 0 },
        { id: 14, name: '영음', level: 5, nationId: 2, x: 0, y: 0 },
        { id: 15, name: '양적', level: 5, nationId: 0, x: 0, y: 0 },
    ],
    nations: [{ id: 1, name: '조조', color: '#4a6fa5' }, { id: 2, name: '원소', color: '#a14b8c' }],
};

type Handler = (url: string) => Response | Promise<Response>;
let handlers: Record<string, Handler> = {};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

beforeEach(() => {
    mocks.login.mockReset();
    mocks.refresh.mockReset();
    mocks.next.mockReturnValue(null);
    mocks.servers = [{ id: 'pep', name: 'pep', generation: 1 }, { id: 'uni', name: '통일 서버', generation: 3 }];
    mocks.emptyValid = true;
    mocks.mapProps = [];
    mocks.previews = { pep: PEP };
    handlers = {
        '/api/notices': () => json({ notices: [{ id: 1, title: '공개 알파 안내', body: '월드는 초기화될 수 있습니다.\n계정은 보존됩니다.', pinned: true, publishedAt: '2026-09-05T00:00:00Z', deleted: false }] }),
        '/api/server-events/pep': () => json({ events: [{ id: 7, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: { year: 200, month: 3, phase: 2, ordinal: 0 }, refs: { CITY: 12, FROM_NATION: 2, TO_NATION: 1 }, facts: {} }], nextCursor: null }),
        '/api/server-events/uni': () => json({ events: [], nextCursor: null }),
        '/api/server-imperial/': () => json({ status: 'NOT_SEEDED', badges: [] }),
    };
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
        const url = String(input);
        const key = Object.keys(handlers).find((prefix) => url.startsWith(prefix));
        if (!key) throw new Error(`unexpected request: ${url}`);
        return handlers[key](url);
    });
});

describe('P-G02 로그인 — 폼', () => {
    it('계정명 · 비밀번호 · 회원가입 링크 둘(머리줄 · 패널) · 정책 링크 · 소개 문구(승인됨 D18)', () => {
        render(<LoginPage />);
        expect(screen.getByRole('heading', { level: 1, name: '로그인' })).toBeInTheDocument();
        expect(screen.getByLabelText(AUTH_LABELS.username)).toBeInTheDocument();
        expect(screen.getByLabelText(AUTH_LABELS.password)).toHaveAttribute('type', 'password');
        expect(screen.getByRole('link', { name: '회원가입' })).toHaveAttribute('href', '/join');
        expect(screen.getByRole('link', { name: AUTH_LABELS.toJoin })).toHaveAttribute('href', '/join');
        const policy = screen.getByRole('navigation', { name: '정책' });
        expect(within(policy).getByRole('link', { name: '개인정보처리방침' })).toHaveAttribute('href', '/privacy');
        expect(within(policy).getByRole('link', { name: '이용약관' })).toHaveAttribute('href', '/terms');
        expect(screen.getByText(/순마다 명령을 세우고/)).toHaveAttribute('data-copy-status', 'approved');
        expect(screen.queryByText(/문구 초안/)).not.toBeInTheDocument();
        // 로고는 한 번(머리줄 로고를 끈다, 시스템 3.1.4)
        expect(screen.getAllByAltText('오픈삼국')).toHaveLength(1);
        // 큰 워드마크는 WebP(81 KB)를 먼저, PNG(256색 72 KB)는 대체본(D22 · opensamguk-images export)
        const logo = screen.getByAltText('오픈삼국');
        expect(logo).toHaveAttribute('src', '/logo-wordmark.png');
        expect(logo.closest('picture')?.querySelector('source[type="image/webp"]')).toHaveAttribute('srcset', '/logo-wordmark.webp');
    });

    it('빈 칸은 쉬운 말 오류로 막고, 표시 단추로 비밀번호를 보인다', () => {
        render(<LoginPage />);
        fireEvent.click(screen.getByRole('button', { name: '표시' }));
        expect(screen.getByLabelText(AUTH_LABELS.password)).toHaveAttribute('type', 'text');
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        expect(screen.getByRole('alert')).toHaveTextContent('계정명을 입력하세요');
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.username), { target: { value: 'tester' } });
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        expect(screen.getByRole('alert')).toHaveTextContent('비밀번호를 입력하세요');
        expect(mocks.login).not.toHaveBeenCalled();
    });

    it('서버가 거절한 문장은 받은 그대로 보이고 페이지에 머문다', async () => {
        mocks.login.mockRejectedValueOnce(new Error('현재는 로그인이 금지되어있습니다!'));
        render(<LoginPage />);
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.username), { target: { value: 'tester' } });
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.password), { target: { value: 'secret' } });
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('현재는 로그인이 금지되어있습니다!'));
        expect(mocks.push).not.toHaveBeenCalled();
    });

    it('로그인하면 next(같은 사이트 경로만) 또는 로비로 간다', async () => {
        mocks.login.mockResolvedValueOnce({});
        mocks.next.mockReturnValue('//evil.example');
        render(<LoginPage />);
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.username), { target: { value: 'tester' } });
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.password), { target: { value: 'secret' } });
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        await waitFor(() => expect(mocks.push).toHaveBeenCalledWith('/lobby'));
    });
});

describe('P-G02 로그인 — 서버 현황 지도', () => {
    it('지도는 화면 배경(backdrop)이고, 서버 목록은 서버 렌더가 준다(`/api/servers`를 부르지 않는다)', async () => {
        render(<LoginPage />);
        expect(screen.getByTestId('map')).toHaveAttribute('data-variant', 'backdrop');
        expect(screen.getByTestId('map')).toHaveAttribute('data-server', 'pep');
        expect(await screen.findByText('pep 1기 · 200년 3월 중순')).toBeInTheDocument();
        const calls = vi.mocked(fetch).mock.calls.map(([input]) => String(input));
        expect(calls.some((url) => url.startsWith('/api/servers'))).toBe(false);
    });

    it('세력 현황은 미리보기의 현 수(내림차순) — 주인 없는 城 · 장수 수는 없다', async () => {
        render(<LoginPage />);
        const panel = screen.getByRole('region', { name: '세력 현황' });
        await waitFor(() => expect(within(panel).getAllByRole('listitem')).toHaveLength(2));
        const rows = within(panel).getAllByRole('listitem').map((row) => row.textContent);
        expect(rows).toEqual(['원소현 2', '조조현 1']);
        expect(within(panel).getByText('pep 1기 · 세력 2')).toBeInTheDocument();
        expect(within(panel).queryByText(/명$/)).toBeNull();
    });

    it('천하 정세는 kind + refs 를 알림체 문장으로 — 이름은 미리보기에서 푼다', async () => {
        render(<LoginPage />);
        const panel = screen.getByRole('region', { name: '천하 정세' });
        expect(await within(panel).findByText('허현의 소유 세력이 원소에서 조조로 바뀌었습니다.')).toBeInTheDocument();
        expect(within(panel).getByText('200년 3월 중순')).toBeInTheDocument();
    });

    it('서버를 바꾸면 지도 · 천하 정세가 그 서버로 바뀐다(빈 사건은 빈 문구)', async () => {
        render(<LoginPage />);
        fireEvent.click(screen.getByRole('button', { name: /통일 서버/ }));
        expect(screen.getByRole('button', { name: /통일 서버/ })).toHaveAttribute('aria-pressed', 'true');
        expect(screen.getByTestId('map')).toHaveAttribute('data-server', 'uni');
        const panel = screen.getByRole('region', { name: '천하 정세' });
        expect(await within(panel).findByText('아직 공개된 사건이 없습니다')).toBeInTheDocument();
    });

    it('미리보기 실패는 세력 현황 오류 + 다시 시도, 사건 피드 실패도 따로 다시 시도', async () => {
        mocks.previews = { pep: 'fail' };
        handlers['/api/server-events/pep'] = () => json({ error: 'down' }, 502);
        render(<LoginPage />);
        const nations = screen.getByRole('region', { name: '세력 현황' });
        expect(await within(nations).findByText('세력 현황을 불러오지 못했습니다')).toBeInTheDocument();
        const events = screen.getByRole('region', { name: '천하 정세' });
        expect(await within(events).findByText('천하 정세를 불러오지 못했습니다')).toBeInTheDocument();
        handlers['/api/server-events/pep'] = () => json({ events: [], nextCursor: null });
        fireEvent.click(within(events).getByRole('button', { name: '다시 시도' }));
        expect(await within(events).findByText('아직 공개된 사건이 없습니다')).toBeInTheDocument();
    });

    it('황제 소재: READY 면 한 줄, NOT_SEEDED 면 아무것도 두지 않는다', async () => {
        handlers['/api/server-imperial/'] = (url) => json(url.includes('/pep')
            ? { status: 'READY', badges: [{ lineCode: 'HAN', lineName: '한', emperorGeneralId: 1, emperorNodeKind: 'LAND_PROVINCE', emperorNodeId: 'p1', emperorCityId: 12, courtCityId: 12 }] }
            : { status: 'NOT_SEEDED', badges: [] });
        render(<LoginPage />);
        expect(await screen.findByText('황제 — 허현')).toBeInTheDocument();
        await act(async () => { fireEvent.click(screen.getByRole('button', { name: /통일 서버/ })); });
        await waitFor(() => expect(screen.queryByText(/황제 —/)).toBeNull());
        expect(screen.queryByText(/황실 소재/)).toBeNull();
    });

    it('서버가 하나도 없으면 빈 문구, 레지스트리가 깨졌으면 오류와 다시 시도', () => {
        mocks.servers = [];
        const { unmount } = render(<LoginPage />);
        expect(screen.getByText('지금 열린 서버가 없습니다')).toBeInTheDocument();
        expect(screen.queryByTestId('map')).toBeNull();
        unmount();
        mocks.emptyValid = false;
        render(<LoginPage />);
        expect(screen.getByText('서버 목록을 불러오지 못했습니다')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
        expect(mocks.refresh).toHaveBeenCalled();
    });

    it('공지를 못 받으면 「없음」이 아니라 「불러올 수 없음」 + 다시 시도', async () => {
        handlers['/api/notices'] = () => new Response('down', { status: 502 });
        render(<LoginPage />);
        expect(await screen.findByText('공지를 불러올 수 없습니다.')).toBeInTheDocument();
    });

    it('공지는 5건, 「공지 모두 보기」로 20건까지', async () => {
        const notices = Array.from({ length: 24 }, (_, i) => ({ id: i + 1, title: `공지 ${i + 1}`, body: '본문', pinned: false, publishedAt: '2026-09-05T00:00:00Z', deleted: false }));
        handlers['/api/notices'] = () => json({ notices });
        render(<LoginPage />);
        const panel = screen.getByRole('region', { name: '공지' });
        await waitFor(() => expect(within(panel).getAllByRole('listitem')).toHaveLength(5));
        fireEvent.click(within(panel).getByRole('button', { name: '공지 모두 보기 · 15건 더' }));
        expect(within(panel).getAllByRole('listitem')).toHaveLength(20);
    });
});
