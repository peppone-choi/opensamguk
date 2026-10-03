import type { Metadata } from 'next';
import AuthGate from '@/components/AuthGate';
import AccountScreen from '@/components/account/AccountScreen';

export const metadata: Metadata = {
    title: '계정 설정 — 오픈삼국',
};

// P-G05 계정(설계서 §2.5). 로그인은 미들웨어(쿠키) + AuthGate(/api/auth/me)가 막는다.
export default function AccountPage() {
    return (
        <AuthGate>
            <AccountScreen />
        </AuthGate>
    );
}
