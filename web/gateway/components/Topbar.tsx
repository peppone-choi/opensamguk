'use client';

import Link from 'next/link';
import { useAuth } from '@/lib/auth-context';
import { AUTH_LABELS, BRAND, LOBBY_LABELS } from '@/lib/constants';

/** 로비/어드민 상단바 — AuthProvider 하위에서 사용(useAuth 필요). */
export default function Topbar() {
    const { user, logout } = useAuth();
    return (
        <header className="lobby-topbar">
            <Link href="/lobby" className="lobby-brand">
                {BRAND}
            </Link>
            <div className="lobby-user">
                {user && <span>{user.nickname || user.username}</span>}
                {user?.role === 'ADMIN' && <Link href="/admin">{LOBBY_LABELS.admin}</Link>}
                <button className="btn-ghost" onClick={() => void logout()}>
                    {AUTH_LABELS.logout}
                </button>
            </div>
        </header>
    );
}
