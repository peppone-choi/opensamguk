import type { Metadata } from 'next';
import LoginScreen from '@/components/login/LoginScreen';
import { publicServerView } from '@/lib/publicServerView';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '로그인 — 오픈삼국',
};

// P-G02 로그인(설계서 §2.2). 서버 목록은 서버 렌더에 실어 보낸다 — 클라이언트가 `/api/servers`를 따로 부르던
// 첫 지도 경로의 한 단계(약 1.6초, K1 측정)를 뺀다. 목록은 공개 서버 원천(C8)만 — 원천을 모르면 빈 목록과 가른다(설계서 SB6).
export default async function LoginPage() {
    const view = await publicServerView();
    return <LoginScreen servers={view.servers.map(({ id, name, generation }) => ({ id, name, generation }))} registry={view.registry} />;
}
