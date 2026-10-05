import type { Metadata } from 'next';
import JoinScreen from '@/components/join/JoinScreen';
import { publicServerView } from '@/lib/publicServerView';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '회원 가입 — 오픈삼국',
};

// P-G03 가입(설계서 §2.3). 배경 지도는 로그인과 같은 첫 서버의 지금 판도다. 서버가 없으면 바탕만 깐다.
// 이미 로그인한 사람은 미들웨어가 로비로 보낸다.
export default async function JoinPage() {
    // 배경 지도도 공개 서버 중 첫째만 — 원천을 모르거나 공개 서버가 없으면 바탕만
    const { servers } = await publicServerView();
    return <JoinScreen mapServerId={servers[0]?.id ?? null} />;
}
