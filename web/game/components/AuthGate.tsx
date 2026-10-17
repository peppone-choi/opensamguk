'use client';

import { useEffect } from 'react';
import { usePathname } from 'next/navigation';
import { StatusView } from '@opensamguk/ui';
import { AuthProvider, useAuth } from '@/lib/auth-context';

// 브라우저에서 쓰는 게이트웨이 ORIGIN — 로그인 페이지가 게이트웨이(:3000)에만 있으므로
// 크로스-앱 절대 URL로 리다이렉트한다. 서버 전용 GATEWAY_API_URL과 구분(이건 public).
const CONFIGURED_GATEWAY_PUBLIC_URL =
    process.env.NEXT_PUBLIC_GATEWAY_URL ?? process.env.NEXT_PUBLIC_GATEWAY_ORIGIN;

function isOtherOrigin(gatewayOrigin: string): boolean {
    try {
        return new URL(gatewayOrigin, window.location.href).origin !== window.location.origin;
    } catch {
        return false;
    }
}

function Gate({ children }: { children: React.ReactNode }) {
    const { user, loading } = useAuth();
    const pathname = usePathname();

    useEffect(() => {
        if (loading) return;
        if (!user) {
            // web/game엔 로그인 페이지가 없다 → 게이트웨이 로그인으로 보내고 next로 되돌아온다.
            // 같은 출처(nginx)면 next 는 경로 + 쿼리만 넘긴다. 게이트웨이가 다른 출처(로컬 :3000 / :3001 분리)일 때만
            // 전체 주소를 넘긴다 — 게이트웨이는 그 주소를 자기 NEXT_PUBLIC_GAME_URL 출처일 때만 받는다(lib/safeNext.ts).
            const here = typeof window !== 'undefined' ? `${window.location.pathname}${window.location.search}` : pathname;
            const gatewayOrigin = CONFIGURED_GATEWAY_PUBLIC_URL || window.location.origin;
            const next = encodeURIComponent(isOtherOrigin(gatewayOrigin) ? `${window.location.origin}${here}` : here);
            window.location.href = `${gatewayOrigin}/login?next=${next}`;
        }
    }, [loading, user, pathname]);

    if (loading || !user) {
        return (
            <main>
                {/* 공용 불러오기(보드 P-X01 로딩 — 0.3초 넘을 때만 뼈대). 서버가 잠시 답하지 않아 다시 묻는 동안도 같은 모양. */}
                <StatusView kind="loading" scope="page" rows={4} />
            </main>
        );
    }
    return <>{children}</>;
}

/**
 * 게임 보호 래퍼. AuthProvider를 자체 포함한다(/api/auth/me로 로그인 사용자 확정).
 * 미인증이면 게이트웨이 로그인(`${gatewayOrigin}/login?next=<현재 경로 + 쿼리>`, 게이트웨이가 다른 출처면 전체 주소)으로 보낸다. 서버가 잠시 답하지 않으면(5xx · 연결 실패)
 * 로그인으로 보내지 않고 기다렸다 다시 묻는다(auth-context).
 * hasGeneral(빙의 여부) 게이팅은 W3의 책임 — 여기선 "로그인된 게이트웨이 사용자"만 요구한다.
 */
export default function AuthGate({ children }: { children: React.ReactNode }) {
    return (
        <AuthProvider>
            <Gate>{children}</Gate>
        </AuthProvider>
    );
}
