'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';
import type { User } from './types';

interface AuthCtx {
    user: User | null;
    loading: boolean;
    refresh: () => Promise<User | null>;
}

const Ctx = createContext<AuthCtx | null>(null);

/** 다시 묻는 간격 — 2 · 4 · 8 · 16초, 그 뒤로는 30초마다. */
export function authRetryDelayMs(attempt: number): number {
    return Math.min(30_000, 1_000 * 2 ** Math.max(1, attempt));
}

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

    // 서버가 잠시 답하지 않음(5xx · 연결 실패) — 로그아웃이 아니다. 로그인으로 보내지 않고 기다렸다 다시 묻는다.
    // (옛 동작: 502 도 「사용자 없음」으로 읽어 /login ↔ /lobby 를 오갔다.) 다시 묻는 동안 loading 을 유지해 게이트가 그대로 기다린다.
    const [attempt, setAttempt] = useState(0);
    const [retrying, setRetrying] = useState(false);

    const refresh = useCallback(async (): Promise<User | null> => {
        setLoading(true);
        try {
            const res = await fetch('/api/auth/me', { cache: 'no-store' });
            if (res.ok || res.status === 401 || res.status === 403) {
                const data = await res.json().catch(() => null);
                const next: User | null = res.ok ? (data?.user ?? null) : null;
                setUser(next);
                setRetrying(false);
                setAttempt(0);
                setLoading(false);
                return next;
            }
        } catch {
            // 연결 실패 — 아래에서 다시 묻는다.
        }
        setRetrying(true);
        setAttempt((n) => n + 1);
        return null;
    }, []);

    useEffect(() => {
        if (initialUser === null) void refresh();
    }, [initialUser, refresh]);

    useEffect(() => {
        if (!retrying) return undefined;
        const timer = window.setTimeout(() => void refresh(), authRetryDelayMs(attempt));
        return () => window.clearTimeout(timer);
    }, [retrying, attempt, refresh]);

    return <Ctx.Provider value={{ user, loading, refresh }}>{children}</Ctx.Provider>;
}

export function useAuth(): AuthCtx {
    const ctx = useContext(Ctx);
    if (!ctx) throw new Error('useAuth must be used within AuthProvider');
    return ctx;
}

/** Provider 밖에서도 죽지 않는 변형 — 로그인 사용자 역할에 따라 항목을 더 보여 주기만 하는 곳(부서 나브 「관리」)에서 쓴다. */
export function useAuthOptional(): AuthCtx | null {
    return useContext(Ctx);
}
