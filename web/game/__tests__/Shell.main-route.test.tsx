import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import Shell from '@/components/Shell';

const mocks = vi.hoisted(() => ({
    pathname: vi.fn(),
    back: vi.fn(),
    push: vi.fn(),
}));

vi.mock('next/navigation', () => ({
    usePathname: mocks.pathname,
    useRouter: () => ({ back: mocks.back, push: mocks.push }),
}));

vi.mock('@/hooks/useSSE', () => ({
    useSSE: vi.fn(),
}));

vi.mock('@/components/Header', () => ({ default: () => <header data-testid="header" /> }));
vi.mock('@/components/BottomNav', () => ({ default: () => <nav data-testid="bottom-nav" /> }));

describe('Shell main route chrome', () => {
    it('does not render the sub-page BackBar on the path-scoped main page', () => {
        mocks.pathname.mockReturnValue('/game/s1');

        render(
            <Shell>
                <main>메인</main>
            </Shell>,
        );

        expect(screen.queryByRole('button', { name: '돌아가기' })).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '갱신' })).not.toBeInTheDocument();
    });

    it('keeps the BackBar on sub pages', () => {
        mocks.pathname.mockReturnValue('/game/s1/city');

        render(
            <Shell>
                <main>도시</main>
            </Shell>,
        );

        expect(screen.getByRole('button', { name: '돌아가기' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '갱신' })).toBeInTheDocument();
    });
});
