import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import LobbyPage from '@/app/lobby/page';
import { LOBBY_LABELS } from '@/lib/constants';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/ServerBoard', () => ({ default: () => <div>server board</div> }));
vi.mock('@/lib/auth-context', () => { const ctx = { user: { id: 1, username: 'peppone', nickname: '페포', role: 'ADMIN' }, logout: vi.fn(), refresh: vi.fn(), loading: false }; return { useAuth: () => ctx, useAuthOptional: () => ctx }; });

const game = { isUnited: 0, npcMode: 1, year: 200, month: 3, turnPhaseText: '중순', scenario: '시나리오', maxUserCnt: 120, turnTerm: 60, userCnt: 38, npcCnt: 3, nationCnt: 5, fictionMode: '사실', joinMode: null, blockGeneralCreate: 0, defaultStatTotal: 275, otherTextInfo: '표준', status: 'RUNNING' };

describe('02 로비 필터·공지·계정 관리 (ADR-LITE-049)', () => {
    beforeEach(() => {
        vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
            const url = String(input);
            const json = (body: unknown) => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } });
            if (url.endsWith('/api/servers')) return json({ servers: [{ id: 'a', name: '갑 서버', generation: 1 }, { id: 'b', name: '을 서버', generation: 2 }, { id: 'c', name: '병 서버' }] });
            if (url.includes('/api/server-basic-info/a')) return json({ game, me: { name: '페프', picture: '10001', imageServer: 0 } });
            if (url.includes('/api/server-basic-info/b')) return json({ game, me: null });
            if (url.includes('/api/server-basic-info/c')) return json({ game: { ...game, isUnited: 2 }, me: null });
            if (url.includes('/api/notices')) return json({ notices: [] });
            throw new Error(`unexpected request: ${url}`);
        });
    });

    it('filters servers by entry state and keeps the verbatim column labels', async () => {
        render(<LobbyPage />);
        expect(await screen.findByText('페프')).toBeInTheDocument();
        for (const label of [LOBBY_LABELS.colServer, LOBBY_LABELS.colInfo, LOBBY_LABELS.colCharacter, LOBBY_LABELS.colSelect]) {
            expect(screen.getByRole('columnheader', { name: label })).toBeInTheDocument();
        }
        await waitFor(() => expect(document.querySelectorAll('tr[data-entry-state]')).toHaveLength(3));
        fireEvent.click(screen.getByRole('tab', { name: '참가 중' }));
        await waitFor(() => expect(document.querySelectorAll('tr[data-entry-state]')).toHaveLength(1));
        expect(screen.getByText('갑 서버')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('tab', { name: '종료' }));
        await waitFor(() => expect(screen.getByText('병 서버')).toBeInTheDocument());
        expect(screen.queryByText('갑 서버')).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('tab', { name: '전체' }));
        await waitFor(() => expect(document.querySelectorAll('tr[data-entry-state]')).toHaveLength(3));
    });

    it('shows the empty notice state, the account actions and the ADMIN entry', async () => {
        render(<LobbyPage />);
        expect(await screen.findByText('공지가 없습니다.')).toBeInTheDocument();
        expect(screen.getByRole('link', { name: LOBBY_LABELS.accountManage })).toHaveAttribute('href', '/account');
        expect(screen.getByRole('link', { name: '커뮤니티 게시판' })).toHaveAttribute('href', '/board');
        expect(screen.getByRole('link', { name: /관리 \(ADMIN만\)/ })).toHaveAttribute('href', '/admin');
        expect(screen.getByRole('button', { name: '로 그 아 웃' })).toBeInTheDocument();
    });
});
