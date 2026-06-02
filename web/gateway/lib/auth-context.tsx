'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { User } from './types';

interface AuthCtx {
    user: User | null;
    loading: boolean;
    refresh: () => Promise<User | null>;
    logout: () => Promise<void>;
}

const Ctx = createContext<AuthCtx | null>(null);

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

    const logout = useCallback(async () => {
        await fetch('/api/auth/logout', { method: 'POST' });
        setUser(null);
        window.location.href = '/login';
    }, []);

    useEffect(() => {
        if (initialUser === null) void refresh();
    }, [initialUser, refresh]);

    return <Ctx.Provider value={{ user, loading, refresh, logout }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
    const ctx = useContext(Ctx);
    if (!ctx) throw new Error('useAuth must be used within AuthProvider');
    return ctx;
}
