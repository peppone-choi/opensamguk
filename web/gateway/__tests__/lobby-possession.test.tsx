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

        // 이 파일은 테스트가 하나뿐이라 항상 콜드 스타트다: 첫 render + fetch 두 번(서버 목록 → basic-info)
        // + 재렌더 두 번이 한 findBy 의 기본 1000ms 안에서 경쟁한다. 단독 실행에서 0.8~1.3s, 전체 스위트의
        // 워커 부하에서는 1.5s 를 넘겨 실패했다(같은 파일 두 번째 테스트부터는 ~100ms). 체인을 두 단계로
        // 나눠 각 단계가 자기 대기 창을 갖게 하고, 마지막 단계는 부하를 감안해 넉넉히 기다린다.
        expect(await screen.findByText('페프', {}, { timeout: 3000 })).toBeInTheDocument();

        expect(await screen.findByRole('link', { name: '장수빙의' }, { timeout: 3000 })).toHaveAttribute(
            'href',
            '/game/pep?entry=possession',
        );
    });
});
