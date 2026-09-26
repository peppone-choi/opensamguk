import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MyInfoLogPanel from '@/components/game/MyInfoLogPanel';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    generalLog: vi.fn(),
    pollCommandResult: vi.fn(),
    redirect: vi.fn(),
}));

vi.mock('next/navigation', () => ({
    redirect: apiMocks.redirect,
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: React.ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/components/GameTable', () => ({
    default: ({ headers, rows }: { headers: string[]; rows: React.ReactNode[][] }) => (
        <table>
            <thead>
                <tr>{headers.map((h) => <th key={h}>{h}</th>)}</tr>
            </thead>
            <tbody>
                {rows.map((row, rowIdx) => (
                    <tr key={rowIdx}>{row.map((cell, cellIdx) => <td key={cellIdx}>{cell}</td>)}</tr>
                ))}
            </tbody>
        </table>
    ),
}));

vi.mock('@/components/StatusBadge', () => ({
    default: ({ children }: { children: React.ReactNode }) => <span>{children}</span>,
}));

vi.mock('@/lib/serverGameUrl', async () => {
    const actual = await vi.importActual<typeof import('@/lib/serverGameUrl')>('@/lib/serverGameUrl');
    return {
        ...actual,
        useServerId: () => undefined,
    };
});

vi.mock('@/lib/api', () => ({
    api: {
        frontInfo: apiMocks.frontInfo,
        generalLog: apiMocks.generalLog,
    },
    pollCommandResult: apiMocks.pollCommandResult,
    // submitCommandAndAwaitResult가 실제로 부르는 건 이쪽이다(요청ID + abort 시그널).
    pollCommandResultResponse: apiMocks.pollCommandResult,
    isIntakeDenied: (out: { status: string }) => out.status === 'BLOCKED' || out.status === 'UNKNOWN',
    isIntakeQueued: (out: { status: string }) => out.status === 'AVAILABLE',
}));

const frontInfo = {
    general: {
        generalId: 77,
        nationId: 1,
        permission: 2,
    },
};

describe('production-reachable frontend no-op closures', () => {
    beforeEach(() => {
        apiMocks.frontInfo.mockReset();
        apiMocks.generalLog.mockReset();
        apiMocks.pollCommandResult.mockReset();
        apiMocks.frontInfo.mockResolvedValue(frontInfo);
    });

    it('loads MyInfoLogPanel pages from the GeneralLog API and uses reqTo for more rows', async () => {
        apiMocks.generalLog.mockImplementation(
            (_generalId: number, reqType: string, reqTo?: number) =>
                Promise.resolve({
                    result: true,
                    reqType,
                    generalID: 77,
                    log: reqTo == null ? { 30: `${reqType}-new`, 20: `${reqType}-old` } : { 10: `${reqType}-more` },
                }),
        );

        render(<MyInfoLogPanel generalId={77} />);

        await waitFor(() => expect(screen.getByText('generalAction-new')).toBeInTheDocument());
        expect(apiMocks.generalLog).toHaveBeenCalledWith(77, 'generalAction', undefined);
        expect(apiMocks.generalLog).toHaveBeenCalledWith(77, 'battleDetail', undefined);
        expect(apiMocks.generalLog).toHaveBeenCalledWith(77, 'battleResult', undefined);
        expect(apiMocks.generalLog).toHaveBeenCalledWith(77, 'generalHistory', undefined);

        fireEvent.click(screen.getAllByRole('button', { name: '이전 로그 불러오기' })[0]);

        await waitFor(() => expect(screen.getByText('generalAction-more')).toBeInTheDocument());
        expect(apiMocks.generalLog).toHaveBeenCalledWith(77, 'generalAction', 20);
    });
});
