// 군단 → 영지 방침 이동(출발 화면) — 고른 내 군단 · 미선택 · 남의 군단 · 내 군단 0 · 읽기 실패, 군단장 바꾸기는 기존 배치 이동,
// 주소는 지금 탭의 명시 서버를 먼저(다른 서버 쿠키여도) · 불투명 corpsId 는 인코딩만. 시험 대역(api 흉내)만 쓴다.
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CorpsPage from '@/app/game/(campaign)/corps/page';
import { CorpsPanel } from '@/components/corps/CorpsPanel';
import { api } from '@/lib/api';
import { useGameSession, type GameSession } from '@/lib/campaign-session';
import { toCorpsRows } from '@/lib/corps/corps-model';
import type { CorpsList } from '@/lib/campaign-reads';

const push = vi.fn();
const nav = vi.hoisted(() => ({ pathname: '/game/pep/corps' }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
    useSearchParams: () => new URLSearchParams(''),
    useRouter: () => ({ push, replace: vi.fn(), back: vi.fn() }),
}));
vi.mock('@/lib/campaign-session', () => ({ useGameSession: vi.fn() }));
vi.mock('@/lib/api', async (importOriginal) => ({
    ...(await importOriginal<typeof import('@/lib/api')>()),
    api: {
        campaignCorps: vi.fn(), campaignVisibility: vi.fn(), deployOptions: vi.fn(), campaignPolicies: vi.fn(),
        legacyCourtOptions: vi.fn(), courtLegacy: vi.fn(),
    },
}));

const OPAQUE = 'ord/7 a&b=c';
const MINE = { corpsId: OPAQUE, ownerGeneralId: 7, commanderGeneralId: 7, commanderName: '하후돈', nationId: 1, nationColor: '#4f7fbf', provinceId: 'P-1', commanderyNo: 12, visibility: 'FULL' as never, own: true, troops: 1200 };
const THEIRS = { corpsId: 'X-9', ownerGeneralId: 9, commanderGeneralId: 9, commanderName: '적장', nationId: 5, nationColor: '#3333aa', provinceId: 'P-7', commanderyNo: 40, visibility: 'INTEL' as never, own: false, ageTurns: 1 };

beforeEach(() => {
    vi.clearAllMocks();
    nav.pathname = '/game/pep/corps';
    vi.mocked(useGameSession).mockReturnValue({
        loading: false, error: null, serverId: undefined, gameDate: '', refresh: vi.fn(), generalId: 7,
        frontInfo: { global: { year: 200, month: 3, turnPhase: 1 }, general: { hasGeneral: true, generalId: 7, nationId: 1 } },
    } as unknown as GameSession);
    vi.mocked(api.campaignCorps).mockResolvedValue({ status: 'READY', corps: [MINE, THEIRS] } as never);
    vi.mocked(api.campaignVisibility).mockResolvedValue({ status: 'READY', commanderies: [] } as never);
    vi.mocked(api.deployOptions).mockResolvedValue({ available: true, maxReservedTurns: 12, bugoks: [], destinations: [], order: null } as never);
    vi.mocked(api.campaignPolicies).mockResolvedValue({ status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, counties: [], corps: [] } as never);
    vi.mocked(api.legacyCourtOptions).mockResolvedValue({ inputId: 'court.releaseCorps', available: false, reason: '안 됨', choices: [] } as never);
});
afterEach(() => { document.cookie = 'sam_server=; path=/; max-age=0'; });

describe('군단 화면 → 영지 방침 주소', () => {
    it('고른 내 군단 — 지금 탭 서버(/game/pep)가 다른 서버 쿠키보다 먼저, 불투명 id 는 인코딩만, 실제 & 구분자', async () => {
        document.cookie = 'sam_server=other; path=/';
        render(<CorpsPage />);
        fireEvent.click(within(await screen.findByRole('region', { name: '내 군단' })).getByRole('button'));
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        const href = push.mock.calls.at(-1)![0] as string;
        expect(href).toBe(`/game/pep/territory?view=policy&scope=CORPS&orderId=${encodeURIComponent(OPAQUE)}`);
        expect(href).not.toContain('&amp;');
        expect(new URL(href, 'http://x').searchParams.get('orderId')).toBe(OPAQUE);
    });

    it('고른 군단이 없으면 군단 방침 목록 · 명시 서버가 없는 경로는 기존 주소', async () => {
        nav.pathname = '/game/corps';
        render(<CorpsPage />);
        await screen.findByRole('region', { name: '내 군단' });
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(push).toHaveBeenLastCalledWith('/game/territory?view=policy&scope=CORPS');
    });

    it('군단 읽기 실패면 목록으로 간다(권한 · 대상은 목적지가 판단)', async () => {
        vi.mocked(api.campaignCorps).mockRejectedValue(new Error('500'));
        render(<CorpsPage />);
        await screen.findByRole('button', { name: /다시 시도/ });
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(push).toHaveBeenLastCalledWith('/game/pep/territory?view=policy&scope=CORPS');
    });

    it('남의 군단을 고르면 이동하지 않고 내 군단을 고르라는 안내', async () => {
        render(<CorpsPage />);
        fireEvent.click(within(await screen.findByRole('region', { name: '보이는 다른 군단' })).getByRole('button'));
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(push).not.toHaveBeenCalled();
        expect(screen.getByText('다른 장수의 군단은 방침을 바꿀 수 없습니다 — 내 군단을 고르세요.')).toHaveAttribute('role', 'status');
        // 내 군단으로 다시 고르면 안내는 지우고 그 군단으로
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        expect(screen.queryByText('다른 장수의 군단은 방침을 바꿀 수 없습니다 — 내 군단을 고르세요.')).toBeNull();
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        await waitFor(() => expect(push).toHaveBeenCalledTimes(1));
        expect(push.mock.calls[0][0]).toContain(`orderId=${encodeURIComponent(OPAQUE)}`);
    });

    it('내 군단이 0이면 이동하지 않고 출병 안내', async () => {
        vi.mocked(api.campaignCorps).mockResolvedValue({ status: 'READY', corps: [THEIRS] } as never);
        render(<CorpsPage />);
        await screen.findByText('출전한 군단이 없습니다');
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(push).not.toHaveBeenCalled();
        expect(screen.getByText(/내 군단이 없어 군단 방침을 정할 수 없습니다/)).toHaveAttribute('role', 'status');
        expect(screen.getByRole('button', { name: '출병' })).toBeInTheDocument();
    });
});

describe('군단 칸 콜백', () => {
    const rows = toCorpsRows({ status: 'READY', corps: [MINE] } as CorpsList, null, null);
    const base = { load: { state: 'ready' as const, rows }, order: null, releaseOptions: null, onOpenFlow: vi.fn(), onRelease: vi.fn(async () => ({ ok: true })) };

    it('방침 바꾸기는 고른 군단 id 를 그대로 넘기고, 군단장 바꾸기는 배치 콜백만 부른다', () => {
        const onOpenPolicy = vi.fn();
        const onOpenPlacement = vi.fn();
        render(<CorpsPanel {...base} onOpenPolicy={onOpenPolicy} onOpenPlacement={onOpenPlacement} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(onOpenPolicy).toHaveBeenLastCalledWith(OPAQUE);
        fireEvent.click(screen.getByRole('button', { name: '군단장 바꾸기' }));
        expect(onOpenPlacement).toHaveBeenCalledTimes(1);
        expect(onOpenPolicy).toHaveBeenCalledTimes(1);
    });

    it('고른 군단이 목록에서 사라지면 그 id 로 가지 않고 목록으로', () => {
        const onOpenPolicy = vi.fn();
        const view = render(<CorpsPanel {...base} onOpenPolicy={onOpenPolicy} />);
        fireEvent.click(within(screen.getByRole('region', { name: '내 군단' })).getByRole('button'));
        const other = toCorpsRows({ status: 'READY', corps: [{ ...MINE, corpsId: 'NEW-1' }] } as CorpsList, null, null);
        view.rerender(<CorpsPanel {...base} load={{ state: 'ready', rows: other }} onOpenPolicy={onOpenPolicy} />);
        fireEvent.click(screen.getByRole('button', { name: '방침 바꾸기' }));
        expect(onOpenPolicy).toHaveBeenLastCalledWith(null);
    });
});
