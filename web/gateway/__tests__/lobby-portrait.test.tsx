import { fireEvent, render, screen } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/ServerBoard', () => ({ default: () => <div>server board</div> }));

import LobbyPage from '@/app/lobby/page';
import { IMAGE_CDN_BASE } from '@/lib/constants';

const GAME = {
    isUnited: 0,
    npcMode: 0,
    year: 200,
    month: 1,
    scenario: '테스트 시나리오',
    maxUserCnt: 100,
    turnTerm: 10,
    userCnt: 1,
    npcCnt: 0,
    nationCnt: 1,
    fictionMode: '사실',
    joinMode: null,
    blockGeneralCreate: 0,
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

function renderLobby(me: { name: string; picture: string | null; imageServer: number }) {
    vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === '/api/servers') {
            return response({ servers: [{ id: 'alpha', name: '알파' }] });
        }
        if (url === '/api/server-basic-info/alpha') {
            return response({ game: GAME, me });
        }
        return new Response(null, { status: 404 });
    }));
    render(<LobbyPage />);
    return screen.findByRole('img', { name: me.name });
}

describe('lobby portrait contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal('React', React);
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

    it('adds jpg to a bare shared portrait code', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: '1001', imageServer: 0 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/1001.jpg`);
    });

    it('preserves a supported canonical extension', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: 'portrait.webp', imageServer: 0 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/portrait.webp`);
    });

    it('renders the default when picture is missing', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: null, imageServer: 0 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });

    it('renders the default when picture is whitespace only', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: '   ', imageServer: 0 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });

    it('resolves an imageServer 1 canonical managed name to same-origin /d_pic/', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: 'a1b2c3d4.png', imageServer: 1 });
        expect(portrait).toHaveAttribute('src', '/d_pic/a1b2c3d4.png');
    });

    it('renders the default for a non-canonical imageServer 1 name', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: 'uploaded.png', imageServer: 1 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });

    it('falls back to the default when the lobby portrait fails to load', async () => {
        const portrait = await renderLobby({ name: '테스터', picture: 'missing.png', imageServer: 0 });
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/missing.png`);

        fireEvent.error(portrait);
        expect(portrait).toHaveAttribute('src', `${IMAGE_CDN_BASE}/icons/default.jpg`);
    });
});
