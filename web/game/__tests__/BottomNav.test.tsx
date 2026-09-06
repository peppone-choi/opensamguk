import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import BottomNav from '@/components/BottomNav';
import { MOBILE_TABS, type ControlGating } from '@/lib/dept-menu-config';

const mocks = vi.hoisted(() => ({ pathname: vi.fn(), serverId: vi.fn() }));
vi.mock('next/navigation', () => ({ usePathname: mocks.pathname }));
vi.mock('@/lib/serverGameUrl', async () => {
    const actual = await vi.importActual<typeof import('@/lib/serverGameUrl')>('@/lib/serverGameUrl');
    return { ...actual, useServerId: mocks.serverId };
});
const NONE: ControlGating = { showSecret: false, permission: 0, myLevel: 0, nationLevel: 0, isTournamentApplicationOpen: false, isBettingActive: false };

describe('BottomNav (모바일 5탭)', () => {
    it('renders the five S1 tabs on server-scoped hrefs and gates 국가 with a reason', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        render(<BottomNav gating={NONE} gatingState="ready" global={{}} />);
        expect(screen.getByRole('link', { name: '작전실' })).toHaveAttribute('href', '/game/s1');
        expect(screen.getByRole('link', { name: '지도' })).toHaveAttribute('href', '/game/s1/map');
        expect(screen.getByRole('link', { name: '명령' })).toHaveAttribute('href', '/game/s1#reservedCommandPanel');
        const nation = screen.getByRole('link', { name: '국가' });
        expect(nation).toHaveAttribute('aria-disabled', 'true');
        expect(screen.getByRole('tooltip', { hidden: true })).toHaveTextContent('장수 직위 이상 필요');
        expect(MOBILE_TABS).toHaveLength(5);
    });

    it('opens the department sheet from 더보기 and closes it with Escape', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        render(<BottomNav gating={NONE} gatingState="ready" global={{}} />);
        fireEvent.click(screen.getByRole('button', { name: '더보기' }));
        expect(screen.getByRole('dialog', { name: '부서 메뉴' })).toBeInTheDocument();
        fireEvent.keyDown(window, { key: 'Escape' });
        expect(screen.queryByRole('dialog', { name: '부서 메뉴' })).not.toBeInTheDocument();
        expect(screen.getByRole('button', { name: '더보기' })).toHaveFocus();
    });
    it('traps Tab inside the department sheet in both directions', () => {
        mocks.pathname.mockReturnValue('/game/s1');
        mocks.serverId.mockReturnValue('s1');
        render(<BottomNav gating={NONE} gatingState="ready" global={{}} />);
        fireEvent.click(screen.getByRole('button', { name: '더보기' }));
        const sheet = screen.getByRole('dialog', { name: '부서 메뉴' });
        const items = Array.from(sheet.querySelectorAll<HTMLElement>('button:not([disabled]), a[href]'));
        expect(items.length).toBeGreaterThan(1);
        expect(items[0]).toHaveFocus();
        fireEvent.keyDown(window, { key: 'Tab', shiftKey: true });
        expect(items[items.length - 1]).toHaveFocus();
        fireEvent.keyDown(window, { key: 'Tab' });
        expect(items[0]).toHaveFocus();
    });
});
