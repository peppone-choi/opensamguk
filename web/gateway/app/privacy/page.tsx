import type { Metadata } from 'next';
import PolicyPage from '@/components/gateway/PolicyPage';

export const metadata: Metadata = { title: '개인정보처리방침 — 오픈삼국' };

export default function PrivacyPage() {
    return <PolicyPage title="개인정보처리방침" sections={['모으는 정보', '쓰는 곳', '보관 기간', '지우기 요청', '연락처']} />;
}
