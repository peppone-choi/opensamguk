'use client';

import { useEffect } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import StateLine from '@/components/status/StateLine';
import { AuthProvider, useAuth } from '@/lib/auth-context';

function Gate({ admin, children }: { admin?: boolean; children: React.ReactNode }) {
    const { user, loading } = useAuth();
    const router = useRouter();

    useEffect(() => {
        if (loading) return;
        if (!user) router.replace('/login');
    }, [loading, user, router]);

    // 설계서 §2.0 G1 · G3 — 문구 없는 스피너 대신 공용 로딩, 비관리자는 말없이 로비로 보내지 않고 권한 없음 상태 + 로비 링크.
    if (loading || !user) {
        return (
            <div className="center-screen">
                <StateLine kind="loading" title="확인하는 중" />
            </div>
        );
    }
    if (admin && user.role !== 'ADMIN') {
        return (
            <div className="center-screen gw31-denied">
                <StateLine kind="empty" title="운영자만 볼 수 있습니다" body="운영 콘솔은 운영자 계정으로 들어와야 합니다." />
                <Link className="os-button os-button--ghost" href="/lobby">로비로</Link>
            </div>
        );
    }
    return <>{children}</>;
}

/**
 * 보호 페이지 래퍼. AuthProvider를 자체 포함하므로 페이지가 독립적으로 사용 가능.
 * /api/auth/me를 호출해(만료 시 refresh 포함) 사용자 확정 → 미인증이면 /login,
 * admin=true인데 비-ADMIN이면 /lobby로 보낸다.
 */
export default function AuthGate({ admin, children }: { admin?: boolean; children: React.ReactNode }) {
    return (
        <AuthProvider>
            <Gate admin={admin}>{children}</Gate>
        </AuthProvider>
    );
}
