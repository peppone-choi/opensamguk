'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { AuthProvider, useAuth } from '@/lib/auth-context';

function Gate({ admin, children }: { admin?: boolean; children: React.ReactNode }) {
    const { user, loading } = useAuth();
    const router = useRouter();

    useEffect(() => {
        if (loading) return;
        if (!user) {
            router.replace('/login');
            return;
        }
        if (admin && user.role !== 'ADMIN') {
            router.replace('/lobby');
        }
    }, [loading, user, admin, router]);

    if (loading || !user || (admin && user.role !== 'ADMIN')) {
        return (
            <div className="center-screen">
                <div className="spinner" />
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
