import type { Metadata } from 'next';
import AuthGate from '@/components/AuthGate';
import LobbyScreen from '@/components/lobby/LobbyScreen';
import { getServers, isValidEmptyServerRegistry } from '@/lib/serverRegistry';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '게임 로비 — 오픈삼국',
};

// P-G04 로비(설계서 §2.4). 서버 목록을 서버 렌더에 싣는다 — 로그인과 같은 규칙. 로그인은 미들웨어(쿠키) + AuthGate(/api/auth/me)가 막는다.
export default function LobbyPage() {
    const servers = getServers().map(({ id, name, generation, gameUrl }) => ({ id, name, generation, gameUrl }));
    const registry = servers.length > 0 || isValidEmptyServerRegistry() ? 'ok' : 'error';
    return (
        <AuthGate>
            <LobbyScreen servers={servers} registry={registry} />
        </AuthGate>
    );
}
