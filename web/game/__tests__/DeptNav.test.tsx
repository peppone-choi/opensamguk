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

const NONE: ControlGating = { showSecret: false, permission: 0, myLevel: 0, nationLevel: 0, isTournamentApplicationOpen: false, isBettingActive: false };

describe('DeptNav (부서 나브)', () => {
    it('renders the six S1 groups and keeps blocked entries visible as dashed items with a reason', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        render(<DeptNav gating={NONE} global={{ npcMode: 0 }} />);

        expect(screen.getByRole('link', { name: '작전실' })).toHaveAttribute('aria-current', 'page');
        for (const label of ['국가 운영', '군사', '정보', '광장', '기록']) {
            expect(screen.getByRole('button', { name: new RegExp(label) })).toBeInTheDocument();
        }
        fireEvent.click(screen.getByRole('button', { name: /국가 운영/ }));
        const menu = screen.getByRole('menu', { name: '국가 운영' });
        const secret = within(menu).getByText('기 밀 실');
        expect(secret).toHaveAttribute('aria-disabled', 'true');
        expect(secret).toHaveClass('dept-nav__entry--disabled');
        const tipId = secret.parentElement?.getAttribute('aria-describedby');
        expect(document.getElementById(tipId ?? '')).toHaveTextContent('수뇌부 권한 필요');
    });

    it('resolves hrefs onto the server-scoped path and hides only server-conditioned menu items', () => {
        mocks.pathname.mockReturnValue('/game/s1/city');
        mocks.serverId.mockReturnValue('s1');
        render(<DeptNav gating={{ ...NONE, myLevel: 5, nationLevel: 2, showSecret: true, permission: 4 }} global={{ npcMode: 0 }} />);

        fireEvent.click(screen.getByRole('button', { name: /^정보/ }));
        const info = screen.getByRole('menu', { name: '정보' });
        expect(within(info).getByRole('menuitem', { name: '현재 도시' })).toHaveAttribute('href', '/game/s1/city');
        expect(within(info).getByRole('menuitem', { name: '천하 지도' })).toHaveAttribute('href', '/game/s1/map');
        fireEvent.click(screen.getByRole('button', { name: /^기록/ }));
        const records = screen.getByRole('menu', { name: '기록' });
        expect(within(records).queryByText('빙의일람')).not.toBeInTheDocument();
        expect(within(records).getByText('접속량정보')).toBeInTheDocument();
    });

    it('resolveDeptHref keeps external links and hashes, maps legacy php and server paths', () => {
        expect(resolveDeptHref('https://open.kakao.com/o/', 's1')).toBe('https://open.kakao.com/o/');
        expect(resolveDeptHref('/game#commands', 's1')).toBe('/game/s1#commands');
        expect(resolveDeptHref('/game/board?secret=1', 's1')).toBe('/game/s1/board?secret=1');
        expect(resolveDeptHref('/game/board?secret=1', undefined)).toBe('/game/board?secret=1');
    });
});
