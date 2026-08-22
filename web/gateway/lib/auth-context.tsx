'use client';

import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react';
import type { User } from './types';

interface AuthCtx {
    user: User | null;
    loading: boolean;
    refresh: (canonicalUser?: User) => Promise<User | null>;
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
    const refreshGeneration = useRef(0);

    const refresh = useCallback(async (canonicalUser?: User): Promise<User | null> => {
        const generation = ++refreshGeneration.current;
        if (canonicalUser) {
            setUser(canonicalUser);
            setLoading(false);
            return canonicalUser;
        }
        setLoading(true);
        try {
            const res = await fetch('/api/auth/me', { cache: 'no-store' });
            const data = await res.json();
            const next: User | null = data?.user ?? null;
            if (generation === refreshGeneration.current) setUser(next);
            return next;
        } catch {
            if (generation === refreshGeneration.current) setUser(null);
            return null;
        } finally {
            if (generation === refreshGeneration.current) setLoading(false);
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
