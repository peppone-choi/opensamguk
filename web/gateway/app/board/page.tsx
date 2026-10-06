import type { Metadata } from 'next';
import CommunityScreen from '@/components/community/CommunityScreen';

export const metadata: Metadata = {
    title: '커뮤니티 — 오픈삼국',
};

// P-G06 커뮤니티 목록(설계서 §3.1). 서버 밖 · 계정 단위 공간이라 손님도 읽는다(세션은 board/layout 의 AuthProvider).
export default function BoardPage() {
    return <CommunityScreen />;
}
