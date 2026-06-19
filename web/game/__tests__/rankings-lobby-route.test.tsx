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
        const emperor = screen.getByRole('link', { name: /황제 정보/ });
        expect(emperor).toHaveAttribute('href', 'rankings/emperor');
        expect(new URL(emperor.getAttribute('href')!, 'https://sam.peppone.dev/game/s1/rankings').pathname)
            .toBe('/game/s1/rankings/emperor');
    });
});
