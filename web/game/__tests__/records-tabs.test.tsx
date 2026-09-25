import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RecordsTabs, { recordsTabs } from '@/components/records/RecordsTabs';

const nav = vi.hoisted(() => ({ pathname: '/game/history' }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
}));

describe('RecordsTabs', () => {
    it('lists the HWIHA product records', () => {
        expect(recordsTabs().map((t) => t.label)).toEqual([
            '월단평', '연감', '월드 기록', '랭킹',
        ]);
        expect(recordsTabs().map((t) => t.href)).toEqual([
            '/game/yuedan', '/game/history', '/game/world-log', '/game/rankings',
        ]);
    });

    it('marks the current page and links every other tab', () => {
        nav.pathname = '/game/world-log';
        render(<RecordsTabs />);
        const tabs = screen.getByRole('navigation', { name: '기록' });
        expect(tabs.querySelectorAll('a')).toHaveLength(4);
        expect(screen.getByRole('link', { name: '월드 기록' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '연감' })).not.toHaveAttribute('aria-current');
        expect(screen.getByRole('link', { name: '연감' })).toHaveAttribute('href', '/game/history');
    });

    it('keeps rankings active and links ranking siblings from a detail page', () => {
        nav.pathname = '/game/rankings/emperor/7';
        render(<RecordsTabs />);
        expect(screen.getByRole('link', { name: '랭킹' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '황제 정보' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '세력 순위' })).toHaveAttribute('href', '/game/rankings/kingdoms');
    });
});
