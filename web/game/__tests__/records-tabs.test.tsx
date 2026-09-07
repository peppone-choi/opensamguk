import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import RecordsTabs, { recordsTabs } from '@/components/records/RecordsTabs';

const nav = vi.hoisted(() => ({ pathname: '/game/history' }));
vi.mock('next/navigation', () => ({
    usePathname: () => nav.pathname,
}));

describe('RecordsTabs', () => {
    it('lists the 기록 department leaves plus 전황, labels verbatim', () => {
        expect(recordsTabs().map((t) => t.label)).toEqual([
            '연감', '전황', '세력일람', '장수일람', '명장일람', '명예의전당', '왕조일람', '접속량정보', '빙의일람',
        ]);
        expect(recordsTabs().map((t) => t.href)).toEqual([
            '/game/history', '/game/world-log', '/game/rankings/kingdoms', '/game/rankings/generals', '/game/rankings/best-generals',
            '/game/rankings/hall-of-fame', '/game/rankings/emperor', '/game/rankings/traffic', '/game/rankings/npcs',
        ]);
    });

    it('marks the current page and links every other tab', () => {
        nav.pathname = '/game/world-log';
        render(<RecordsTabs />);
        const tabs = screen.getByRole('navigation', { name: '기록' });
        expect(tabs.querySelectorAll('a')).toHaveLength(9);
        expect(screen.getByRole('link', { name: '전황' })).toHaveAttribute('aria-current', 'page');
        expect(screen.getByRole('link', { name: '연감' })).not.toHaveAttribute('aria-current');
        expect(screen.getByRole('link', { name: '연감' })).toHaveAttribute('href', '/game/history');
    });
});
