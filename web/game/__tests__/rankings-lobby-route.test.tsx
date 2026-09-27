import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import RankingsLobbyPage from '@/app/game/rankings/page';

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

describe('RankingsLobbyPage route hrefs', () => {
    it('uses initial relative links that preserve a path server id', () => {
        render(<RankingsLobbyPage />);
        const kingdoms = screen.getByRole('link', { name: /세력 순위/ });
        expect(kingdoms).toHaveAttribute('href', 'rankings/kingdoms');
        expect(new URL(kingdoms.getAttribute('href')!, 'https://sam.peppone.dev/game/s1/rankings').pathname)
            .toBe('/game/s1/rankings/kingdoms');
        // 삼모 전용 랭킹 4종은 대체 없이 지웠다(ADR-LITE-049 2026-09-26).
        for (const retired of [/황제 정보/, /NPC 일람/, /명예의 전당/, /접속 통계/]) {
            expect(screen.queryByRole('link', { name: retired })).toBeNull();
        }
    });
});
