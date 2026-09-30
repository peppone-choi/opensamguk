import type { Metadata } from 'next';
import PolicyPage from '@/components/gateway/PolicyPage';

export const metadata: Metadata = { title: '이용약관 — 오픈삼국' };

export default function TermsPage() {
    return <PolicyPage title="이용약관" sections={['계정', '금지 행동', '이용 제한', '서비스 변경', '연락처']} />;
}
