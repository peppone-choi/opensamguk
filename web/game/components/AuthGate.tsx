'use client';

import { useEffect } from 'react';
import { usePathname } from 'next/navigation';
import { AuthProvider, useAuth } from '@/lib/auth-context';

// 브라우저에서 쓰는 게이트웨이 ORIGIN — 로그인 페이지가 게이트웨이(:3000)에만 있으므로
// 크로스-앱 절대 URL로 리다이렉트한다. 서버 전용 GATEWAY_API_URL과 구분(이건 public).
const GATEWAY_PUBLIC_URL = process.env.NEXT_PUBLIC_GATEWAY_URL ?? 'http://localhost:3000';

function Gate({ children }: { children: React.ReactNode }) {
    const { user, loading } = useAuth();
    const pathname = usePathname();

    useEffect(() => {
        if (loading) return;
        if (!user) {
            // web/game엔 로그인 페이지가 없다 → 게이트웨이 로그인으로 보내고 next로 되돌아온다.
            const here = typeof window !== 'undefined' ? window.location.href : pathname;
            const next = encodeURIComponent(here);
            window.location.href = `${GATEWAY_PUBLIC_URL}/login?next=${next}`;
        }
    }, [loading, user, pathname]);

    if (loading || !user) {
        return (
            <div className="center-screen">
                <div className="spinner" />
            </div>
        );
    }
    return <>{children}</>;
}

/**
 * 게임 보호 래퍼. AuthProvider를 자체 포함한다(/api/auth/me로 로그인 사용자 확정).
 * 미인증이면 게이트웨이 로그인(`${NEXT_PUBLIC_GATEWAY_URL}/login?next=<현재 URL>`)으로 보낸다.
 * hasGeneral(빙의 여부) 게이팅은 W3의 책임 — 여기선 "로그인된 게이트웨이 사용자"만 요구한다.
 */
export default function AuthGate({ children }: { children: React.ReactNode }) {
    return (
        <AuthProvider>
            <Gate>{children}</Gate>
        </AuthProvider>
    );
}
