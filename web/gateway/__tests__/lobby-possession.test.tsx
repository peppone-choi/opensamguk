import { render, screen } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/ServerBoard', () => ({ default: () => <div>server board</div> }));

import LobbyPage from '@/app/lobby/page';

const GAME = {
    isUnited: 0,
    npcMode: 1,
    year: 200,
    month: 1,
    scenario: '테스트 시나리오',
    maxUserCnt: 100,
    turnTerm: 10,
    userCnt: 1,
    npcCnt: 1,
    nationCnt: 1,
    fictionMode: '사실',
    joinMode: null,
    blockGeneralCreate: 1,
    defaultStatTotal: 275,
    otherTextInfo: '',
    status: 'OPEN',
};

function response(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

describe('lobby possession entry', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal('React', React);
        vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
            const url = String(input);
            if (url === '/api/servers') {
                return response({ servers: [{ id: 'pep', name: '페프' }] });
            }
            if (url === '/api/server-basic-info/pep') {
                return response({ game: GAME, me: null });
            }
            return new Response(null, { status: 404 });
        }));
    });

    it('sends an alphanumeric public server ID to the explicit possession entry', async () => {
        render(<LobbyPage />);

        expect(await screen.findByRole('link', { name: '장수빙의' })).toHaveAttribute(
            'href',
            '/game/pep?entry=possession',
        );
    });
});
