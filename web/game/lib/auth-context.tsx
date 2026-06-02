'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { User } from './types';

interface AuthCtx {
    user: User | null;
    loading: boolean;
    refresh: () => Promise<User | null>;
}

const Ctx = createContext<AuthCtx | null>(null);

/**
 * web/game 인증 컨텍스트 — 읽기 전용. /api/auth/me(서버 route handler)를 호출해 게이트웨이가 발급한
 * sam_access 쿠키로 사용자를 확정한다. 로그인/로그아웃/refresh는 게이트웨이가 소유하므로 여기엔 없다.
 */
export function AuthProvider({
    initialUser = null,
    children,
}: {
    initialUser?: User | null;
    children: React.ReactNode;
}) {
    const [user, setUser] = useState<User | null>(initialUser);
    const [loading, setLoading] = useState(initialUser === null);

    const refresh = useCallback(async (): Promise<User | null> => {
        setLoading(true);
        try {
            const res = await fetch('/api/auth/me', { cache: 'no-store' });
            const data = await res.json();
            const next: User | null = data?.user ?? null;
            setUser(next);
            return next;
        } catch {
            setUser(null);
            return null;
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        if (initialUser === null) void refresh();
    }, [initialUser, refresh]);

    return <Ctx.Provider value={{ user, loading, refresh }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
    const ctx = useContext(Ctx);
    if (!ctx) throw new Error('useAuth must be used within AuthProvider');
    return ctx;
}
