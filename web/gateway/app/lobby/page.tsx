import type { Metadata } from 'next';
import AuthGate from '@/components/AuthGate';
import LobbyScreen from '@/components/lobby/LobbyScreen';
import { readPublicServers } from '@/lib/serverPublication';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '게임 로비 — 오픈삼국',
};

// P-G04 로비(설계서 §2.4). 서버 목록을 서버 렌더에 싣는다 — 로그인과 같은 규칙. 로그인은 미들웨어(쿠키) + AuthGate(/api/auth/me)가 막는다.
// 검증 중(VERIFYING) 서버는 공개 목록에 없다 — 그 서버에 장수가 있는 사람에게도 숨긴다(D112 ①).
export default async function LobbyPage() {
    const list = await readPublicServers();
    const servers = list.kind === 'known' ? [...list.servers] : [];
    const registry = list.kind === 'known' ? 'ok' : 'error';
    return (
        <AuthGate>
            <LobbyScreen servers={servers} registry={registry} />
        </AuthGate>
    );
}
