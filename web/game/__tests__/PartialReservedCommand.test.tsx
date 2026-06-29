import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PartialReservedCommand from '@/components/game/PartialReservedCommand';

const mocks = vi.hoisted(() => ({
    reservedCommands: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        reservedCommands: mocks.reservedCommands,
        commandQueue: {
            push: vi.fn(),
            repeat: vi.fn(),
        },
    },
}));

vi.mock('@/components/CommandModal', () => ({
    default: () => null,
}));

describe('PartialReservedCommand', () => {
    it('renders each reserved slot with progressive ten-day phases and turn times', async () => {
        mocks.reservedCommands.mockResolvedValueOnce({
            result: true,
            slots: [{ turnIdx: 1, brief: '농지개간' }],
            maxTurn: 4,
            turnTime: '2026-06-22 07:12:00',
            turnTerm: 60,
            year: 190,
            month: 8,
            turnPhase: 2,
            turnPhaseText: '중순',
            date: '2026-06-22 07:12:00',
        });

        render(<PartialReservedCommand generalId={10} nationId={1} maxTurn={4} onToast={vi.fn()} />);

        await waitFor(() => expect(mocks.reservedCommands).toHaveBeenCalledWith(10));

        expect(document.querySelectorAll('.rcp-row')).toHaveLength(36);
        expect(screen.queryByText('접기')).not.toBeInTheDocument();
        expect(screen.queryByText('펼치기')).not.toBeInTheDocument();
        expect(screen.getAllByText('190년 8월 중순').length).toBeGreaterThan(0);
        expect(screen.getAllByText('190년 8월 하순').length).toBeGreaterThan(0);
        expect(screen.getAllByText('190년 9월 상순').length).toBeGreaterThan(0);
        expect(screen.getAllByText('190년 9월 중순').length).toBeGreaterThan(0);
        expect(screen.getAllByText('07:12').length).toBeGreaterThan(0);
        expect(screen.getAllByText('08:12').length).toBeGreaterThan(0);
        expect(screen.getAllByText('09:12').length).toBeGreaterThan(0);
        expect(screen.getAllByText('10:12').length).toBeGreaterThan(0);
        expect(screen.getByText('농지개간')).toBeInTheDocument();
    });
});
