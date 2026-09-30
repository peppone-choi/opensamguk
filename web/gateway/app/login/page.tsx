import type { Metadata } from 'next';
import LoginScreen from '@/components/login/LoginScreen';
import { getServers, isValidEmptyServerRegistry } from '@/lib/serverRegistry';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '로그인 — 오픈삼국',
};

// P-G02 로그인(설계서 §2.2). 서버 목록은 서버 렌더에 실어 보낸다 — 클라이언트가 `/api/servers`를 따로 부르던
// 첫 지도 경로의 한 단계(약 1.6초, K1 측정)를 뺀다. 레지스트리가 깨졌으면 빈 목록과 가른다(설계서 SB6).
export default function LoginPage() {
    const servers = getServers().map(({ id, name, generation }) => ({ id, name, generation }));
    const registry = servers.length > 0 || isValidEmptyServerRegistry() ? 'ok' : 'error';
    return <LoginScreen servers={servers} registry={registry} />;
}
