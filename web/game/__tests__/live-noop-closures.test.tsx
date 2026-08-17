import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CONTROL_BUTTONS } from '@/lib/control-bar-config';
import { GLOBAL_MENU_V2 } from '@/lib/global-menu-fixture';
import { normalizeLegacyGamePath } from '@/lib/serverGameUrl';
import ComingSoonPage from '@/app/game/coming-soon/page';
import MyInfoLogPanel from '@/components/game/MyInfoLogPanel';
import TournamentAdminPage from '@/app/game/tournament-admin/page';
import SelectPoolPage from '@/app/game/select-pool/page';

const apiMocks = vi.hoisted(() => ({
    frontInfo: vi.fn(),
    tournament: vi.fn(),
    generalLog: vi.fn(),
    tournamentStart: vi.fn(),
    tournamentReset: vi.fn(),
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
        tournamentStart: apiMocks.tournamentStart,
        tournamentReset: apiMocks.tournamentReset,
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
        apiMocks.tournamentStart.mockReset();
        apiMocks.tournamentReset.mockReset();
        apiMocks.selectPoolPick.mockReset();
        apiMocks.selectPoolUpdate.mockReset();
        apiMocks.selectPool.mockReset();
        apiMocks.pollCommandResult.mockReset();
        apiMocks.frontInfo.mockResolvedValue(frontInfo);
        apiMocks.tournament.mockResolvedValue({ entries: [], matches: [] });
    });

    it('routes 감찰부 to the battle-center surface, not the coming-soon stub', () => {
        const button = CONTROL_BUTTONS.find((item) => item.label === '감 찰 부');

        expect(button?.href).toBe('/game/battle-center');
        expect(button?.href).not.toContain('coming-soon');
        expect(button?.newTab).toBe(true);
    });

    it('keeps every control-bar destination on a real game route', () => {
        const realRoutes = new Set([
            '/game/auction',
            '/game/battle-center',
            '/game/betting',
            '/game/board',
            '/game/chief-center',
            '/game/city',
            '/game/diplomacy',
            '/game/generals',
            '/game/global-diplomacy',
            '/game/inherit',
            '/game/my',
            '/game/my-boss',
            '/game/my-cities',
            '/game/my-generals',
            '/game/my-nation',
            '/game/nation-finance',
            '/game/npc-control',
            '/game/tournament',
            '/game/troop',
        ]);

        for (const button of CONTROL_BUTTONS) {
            const hrefs = [button.href, ...(button.split?.map((sub) => sub.href) ?? [])];
            for (const href of hrefs) {
                const path = href.split('?')[0];
                expect(path, `${button.label} -> ${href}`).not.toBe('/game/coming-soon');
                expect(realRoutes.has(path), `${button.label} -> ${href}`).toBe(true);
            }
        }
    });

    it('keeps fixture global-menu destinations on real game routes or external URLs', () => {
        const realRoutes = new Set([
            '/game/board',
            '/game/history',
            '/game/nation-betting',
            '/game/rankings/best-generals',
            '/game/rankings/emperor',
            '/game/rankings/generals',
            '/game/rankings/hall-of-fame',
            '/game/rankings/kingdoms',
            '/game/rankings/npcs',
            '/game/rankings/traffic',
            '/game/simulator',
            '/game/vote',
        ]);
        const urls = GLOBAL_MENU_V2.flatMap((node) => {
            if (node.type === 'item') return [node.url];
            if (node.type === 'multi') return node.subMenu.flatMap((sub) => (sub.type === 'item' ? [sub.url] : []));
            if (node.type === 'split') {
                return [node.main.url, ...node.subMenu.flatMap((sub) => (sub.type === 'item' ? [sub.url] : []))];
            }
            return [];
        });

        for (const url of urls) {
            const normalized = normalizeLegacyGamePath(url);
            if (/^(?:https?:)?\/\//i.test(normalized)) continue;
            const path = normalized.split('?')[0];
            expect(path, url).not.toBe('/game/coming-soon');
            expect(realRoutes.has(path), url).toBe(true);
        }
    });

    it('redirects direct coming-soon visits to the usable game front page', () => {
        ComingSoonPage();

        expect(apiMocks.redirect).toHaveBeenCalledWith('/game');
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

    it('tournament admin calls real mutations, removes manual advance, and renders backend errors', async () => {
        apiMocks.tournamentStart.mockResolvedValue({ result: true });
        apiMocks.tournamentReset.mockRejectedValue(new Error('토너먼트 상태를 초기화할 수 없습니다.'));

        render(<TournamentAdminPage />);

        await waitFor(() => expect(apiMocks.tournament).toHaveBeenCalled());
        fireEvent.click(screen.getByRole('button', { name: '관리' }));

        expect(screen.queryByRole('button', { name: '다음 라운드 진행' })).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', { name: '토너먼트 시작' }));
        await waitFor(() => expect(apiMocks.tournamentStart).toHaveBeenCalledWith(77));

        fireEvent.click(screen.getByRole('button', { name: '초기화' }));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('토너먼트 상태를 초기화할 수 없습니다.'));
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
