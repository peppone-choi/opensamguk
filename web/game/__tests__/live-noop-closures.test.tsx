import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import MyInfoLogPanel from '@/components/game/MyInfoLogPanel';
import SelectPoolPage from '@/app/game/select-pool/page';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    tournament: vi.fn(),
    generalLog: vi.fn(),
    selectPoolPick: vi.fn(),
    selectPoolUpdate: vi.fn(),
    selectPool: vi.fn(),
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
        tournament: apiMocks.tournament,
        generalLog: apiMocks.generalLog,
        selectPool: apiMocks.selectPool,
        commands: {
            selectPoolPick: apiMocks.selectPoolPick,
            selectPoolUpdate: apiMocks.selectPoolUpdate,
        },
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
        apiMocks.tournament.mockReset();
        apiMocks.generalLog.mockReset();
        apiMocks.selectPoolPick.mockReset();
        apiMocks.selectPoolUpdate.mockReset();
        apiMocks.selectPool.mockReset();
        apiMocks.pollCommandResult.mockReset();
        apiMocks.frontInfo.mockResolvedValue(frontInfo);
        apiMocks.tournament.mockResolvedValue({ entries: [], matches: [] });
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

    it('select-pool surfaces the PHP-fatal pick without reloading', async () => {
        apiMocks.selectPool.mockResolvedValue({
            result: true,
            generalId: null,
            validUntil: '2026-07-10T03:02:00Z',
            pick: [{
                uniqueName: '청룡',
                generalName: '마초',
                picture: '1042',
                imageServer: 0,
                leadership: 91,
                strength: 97,
                intel: 74,
                politics: 44,
                charm: 88,
                dex: [1000, 2000, 3000, 4000, 5000],
                personality: 'che_의리',
                specialDomestic: null,
                specialWar: null,
                statEditable: false,
            }],
        });
        apiMocks.selectPoolPick.mockRejectedValue(new Error('500: Internal Server Error'));

        render(<SelectPoolPage />);

        await waitFor(() => expect(screen.getByRole('heading', { name: '마초' })).toBeInTheDocument());
        expect(screen.getByText('91 / 97 / 74 / 44 / 88')).toBeInTheDocument();
        expect(screen.queryByLabelText('고유 이름')).not.toBeInTheDocument();
        const poolCallsBeforePick = apiMocks.selectPool.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '마초 선택' }));

        await waitFor(() =>
            expect(apiMocks.selectPoolPick).toHaveBeenCalledWith(
                {
                    uniqueName: '청룡',
                    leadership: undefined,
                    strength: undefined,
                    intel: undefined,
                    personalityName: undefined,
                    useOwnPicture: false,
                },
                0,
            ),
        );
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('500: Internal Server Error'));
        expect(apiMocks.pollCommandResult).not.toHaveBeenCalled();
        expect(screen.queryByRole('status')).not.toBeInTheDocument();
        expect(apiMocks.selectPool.mock.calls.length).toBe(poolCallsBeforePick);
    });

    it('select-pool update reloads only after an applied terminal result', async () => {
        apiMocks.selectPool.mockResolvedValue({
            result: true,
            generalId: 77,
            validUntil: '2026-07-10T03:02:00Z',
            pick: [{
                uniqueName: '청룡',
                generalName: '마초',
                picture: null,
                imageServer: 0,
                leadership: 91,
                strength: 97,
                intel: 74,
                politics: 44,
                charm: 88,
                dex: [1000, 2000, 3000, 4000, 5000],
                personality: 'che_의리',
                specialDomestic: null,
                specialWar: null,
                statEditable: false,
            }],
        });
        apiMocks.selectPoolUpdate.mockResolvedValue({ status: 'AVAILABLE', requestId: 'update-1' });
        apiMocks.pollCommandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'update-1',
            ok: true,
            type: 'selectPoolUpdate',
            result: {},
        });

        render(<SelectPoolPage />);

        await waitFor(() => expect(screen.getByRole('button', { name: '마초로 변경' })).toBeInTheDocument());
        const poolCallsBeforeUpdate = apiMocks.selectPool.mock.calls.length;
        fireEvent.click(screen.getByRole('button', { name: '마초로 변경' }));

        await waitFor(() => expect(apiMocks.selectPoolUpdate).toHaveBeenCalledWith(
            {
                uniqueName: '청룡',
                leadership: undefined,
                strength: undefined,
                intel: undefined,
                personalityName: undefined,
                useOwnPicture: false,
            },
            77,
        ));
        await waitFor(() => expect(apiMocks.pollCommandResult).toHaveBeenCalledWith('update-1', expect.anything()));
        await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent('변경이 처리되었습니다.'));
        await waitFor(() => expect(apiMocks.selectPool.mock.calls.length).toBeGreaterThan(poolCallsBeforeUpdate));
    });
});
