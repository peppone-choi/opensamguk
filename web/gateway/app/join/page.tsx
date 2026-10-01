import type { Metadata } from 'next';
import JoinScreen from '@/components/join/JoinScreen';
import { getServers } from '@/lib/serverRegistry';

export const dynamic = 'force-dynamic';

export const metadata: Metadata = {
    title: '회원 가입 — 오픈삼국',
};

// P-G03 가입(설계서 §2.3). 배경 지도는 로그인과 같은 첫 서버의 지금 판도다. 서버가 없으면 바탕만 깐다.
// 이미 로그인한 사람은 미들웨어가 로비로 보낸다.
export default function JoinPage() {
    return <JoinScreen mapServerId={getServers()[0]?.id ?? null} />;
}
