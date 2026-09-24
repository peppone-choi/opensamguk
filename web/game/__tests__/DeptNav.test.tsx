import { fireEvent, render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import DeptNav, { resolveDeptHref } from '@/components/DeptNav';
import type { ControlGating } from '@/lib/dept-menu-config';

const mocks = vi.hoisted(() => ({ pathname: vi.fn(), serverId: vi.fn() }));
vi.mock('next/navigation', () => ({ usePathname: mocks.pathname }));
vi.mock('@/lib/serverGameUrl', async () => {
    const actual = await vi.importActual<typeof import('@/lib/serverGameUrl')>('@/lib/serverGameUrl');
    return { ...actual, useServerId: mocks.serverId };
});
const authMock = vi.hoisted(() => ({ role: 'USER' as string | null }));
vi.mock('@/lib/auth-context', () => ({
    useAuthOptional: () => (authMock.role ? { user: { id: 1, username: 'u', nickname: null, email: null, role: authMock.role }, loading: false, refresh: vi.fn() } : null),
}));

const NONE: ControlGating = { myLevel: 0 };

describe('DeptNav (부서 나브)', () => {
    it('renders the six product groups with HWIHA links', () => {
        mocks.pathname.mockReturnValue('/game/s1/hwiha/war-room');
        mocks.serverId.mockReturnValue('s1');
        render(<DeptNav gating={NONE} />);

        expect(screen.getByRole('link', { name: '작전실' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '로비로' })).toHaveAttribute('href', expect.stringMatching(/\/lobby$/));
        expect(screen.getByRole('button', { name: '갱신' })).toBeInTheDocument();
        for (const label of ['국가 운영', '군사', '정보', '광장', '기록']) {
            expect(screen.getByRole('button', { name: new RegExp(label) })).toBeInTheDocument();
        }
        fireEvent.click(screen.getByRole('button', { name: /국가 운영/ }));
        const menu = screen.getByRole('menu', { name: '국가 운영' });
        expect(within(menu).getByRole('menuitem', { name: '배치 · 방침 · 공사' })).toHaveAttribute('href', '/game/s1/hwiha/posts');
        expect(within(menu).getByRole('menuitem', { name: '조정 결정' })).toHaveAttribute('href', '/game/s1/hwiha/orders');
    });

    it('resolves hrefs onto the server-scoped path and omits the old server menu', () => {
        mocks.pathname.mockReturnValue('/game/s1/city');
        mocks.serverId.mockReturnValue('s1');
        render(<DeptNav gating={{ myLevel: 5 }} />);

        fireEvent.click(screen.getByRole('button', { name: /^정보/ }));
        const info = screen.getByRole('menu', { name: '정보' });
        expect(within(info).getByRole('menuitem', { name: '현재 도시' })).toHaveAttribute('href', '/game/s1/city');
        expect(within(info).getByRole('menuitem', { name: '천하 지도' })).toHaveAttribute('href', '/game/s1/map');
        fireEvent.click(screen.getByRole('button', { name: /^기록/ }));
        const records = screen.getByRole('menu', { name: '기록' });
        expect(within(records).queryByText('빙의일람')).not.toBeInTheDocument();
        expect(within(records).getByRole('menuitem', { name: '월단평' })).toHaveAttribute('href', '/game/s1/hwiha/yuedan');
    });

    it('opens a group with ArrowDown, moves focus with arrows and closes with Escape back to the button', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        render(<DeptNav gating={{ myLevel: 5 }} />);
        const button = screen.getByRole('button', { name: /국가 운영/ });
        button.focus();
        fireEvent.keyDown(button, { key: 'ArrowDown' });
        const menu = screen.getByRole('menu', { name: '국가 운영' });
        const items = within(menu).getAllByRole('menuitem');
        expect(items[0]).toHaveFocus();
        fireEvent.keyDown(items[0], { key: 'ArrowDown' });
        expect(items[1]).toHaveFocus();
        fireEvent.keyDown(items[1], { key: 'End' });
        expect(items[items.length - 1]).toHaveFocus();
        fireEvent.keyDown(items[items.length - 1], { key: 'Escape' });
        expect(screen.queryByRole('menu', { name: '국가 운영' })).not.toBeInTheDocument();
        expect(button).toHaveFocus();
    });

    it('keeps product routes visible while server information loads or fails', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        const { unmount } = render(<DeptNav gating={null} gatingState="loading" />);
        fireEvent.click(screen.getByRole('button', { name: /국가 운영/ }));
        expect(within(screen.getByRole('menu', { name: '국가 운영' })).getByRole('menuitem', { name: '보급망 · 창고' })).not.toHaveAttribute('aria-disabled');
        unmount();
        render(<DeptNav gating={null} gatingState="error" />);
        fireEvent.click(screen.getByRole('button', { name: /국가 운영/ }));
        expect(within(screen.getByRole('menu', { name: '국가 운영' })).getByRole('menuitem', { name: '보급망 · 창고' })).toHaveAttribute('aria-disabled', 'true');
        expect(screen.getAllByText('서버 정보 없음').length).toBeGreaterThan(0);
    });

    it('shows the 관리 entry only for ADMIN accounts', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        authMock.role = 'USER';
        const { unmount } = render(<DeptNav gating={NONE} />);
        expect(screen.queryByRole('link', { name: '관리' })).not.toBeInTheDocument();
        unmount();
        authMock.role = 'ADMIN';
        render(<DeptNav gating={NONE} />);
        expect(screen.getByRole('link', { name: '관리' })).toHaveAttribute('href', '/game/s1/admin');
        authMock.role = 'USER';
    });

    it('resolveDeptHref keeps external links and hashes, maps legacy php and server paths', () => {
        expect(resolveDeptHref('https://open.kakao.com/o/', 's1')).toBe('https://open.kakao.com/o/');
        expect(resolveDeptHref('/game#commands', 's1')).toBe('/game/s1#commands');
        expect(resolveDeptHref('/game/board?secret=1', 's1')).toBe('/game/s1/board?secret=1');
        expect(resolveDeptHref('/game/board?secret=1', undefined)).toBe('/game/board?secret=1');
    });
});
