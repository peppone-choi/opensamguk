import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';
import { describe, expect, it, vi } from 'vitest';
import WorldLogPage from '@/app/game/world-log/page';

const mocks = vi.hoisted(() => ({ worldLog: vi.fn() }));
vi.mock('@/lib/api', () => ({ api: { worldLog: mocks.worldLog } }));
vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <main>{children}</main>,
}));
vi.mock('@/hooks/useTurnRefresh', () => ({ useTurnRefresh: vi.fn() }));
vi.mock('next/navigation', () => ({ usePathname: () => '/game/world-log' }));

function rowTexts(scope: HTMLElement): (string | null)[] {
    return Array.from(scope.querySelectorAll('.chron__item')).map((row) => row.textContent);
}

describe('WorldLogPage 편년체', () => {
    it('groups consecutive entries of the same 연·월·순 under one serif heading', async () => {
        mocks.worldLog.mockResolvedValue({
            entries: [
                { id: 3, year: 187, month: 2, phase: 1, phaseText: '상순', text: '<C>●</>187년 2월:셋째' },
                { id: 2, year: 187, month: 1, phase: 2, phaseText: '중순', text: '<C>●</>187년 1월:둘째' },
                { id: 1, year: 187, month: 1, phase: 2, phaseText: '중순', text: '<C>●</>187년 1월:첫째' },
            ],
        });
        render(<WorldLogPage />);
        const feb = await screen.findByRole('region', { name: '187年 2月 상순' });
        // formatLog 가 <C>●</> 토큰을 색 span 으로 바꾸므로 행 textContent 로 본다.
        expect(rowTexts(feb)).toEqual(['●187년 2월:셋째']);
        const jan = screen.getByRole('region', { name: '187年 1月 중순' });
        expect(rowTexts(jan)).toEqual(['●187년 1월:둘째', '●187년 1월:첫째']);
        expect(screen.getAllByRole('region')).toHaveLength(2);
        expect(screen.getByText('187年 1月 ~ 187年 2月')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: '전황' })).toHaveAttribute('aria-current', 'page');
    });

    it('renders the empty state without a heading row', async () => {
        mocks.worldLog.mockResolvedValue({ entries: [] });
        render(<WorldLogPage />);
        expect(await screen.findByText('기록이 없습니다.')).toBeInTheDocument();
        expect(screen.queryAllByRole('region')).toHaveLength(0);
    });
});
