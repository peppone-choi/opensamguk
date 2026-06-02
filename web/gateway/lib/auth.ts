import 'server-only';
import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import { GATEWAY_API_URL } from './server-api';
import { ACCESS_COOKIE } from './cookies';
import type { User } from './types';

/**
 * 서버 컴포넌트용 세션 조회(best-effort).
 *
 * access 쿠키로 gateway-api `/auth/me`를 호출한다. RSC 렌더 중에는 쿠키를 새로 쓸 수
 * 없으므로 여기서는 refresh를 시도하지 않는다 — access 만료 시 null을 반환하고,
 * 클라이언트 AuthProvider가 `/api/auth/me`(refresh 가능 route handler)로 복구한다.
 */
export async function getSession(): Promise<User | null> {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;
    if (!access) return null;
    try {
        const res = await fetch(`${GATEWAY_API_URL}/auth/me`, {
            headers: { Authorization: `Bearer ${access}` },
            cache: 'no-store',
        });
        if (!res.ok) return null;
        return (await res.json()) as User;
    } catch {
        return null;
    }
}

export async function requireUser(): Promise<User> {
    const user = await getSession();
    if (!user) redirect('/login');
    return user;
}

export async function requireAdmin(): Promise<User> {
    const user = await requireUser();
    if (user.role !== 'ADMIN') redirect('/lobby');
    return user;
}
