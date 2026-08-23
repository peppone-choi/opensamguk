import { NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE, REFRESH_COOKIE, clearAuthCookies, setAuthCookies } from '@/lib/cookies';
import { refreshAccessToken } from '@/lib/authRefresh';
import type { User } from '@/lib/types';

/**
 * 현재 사용자 조회 + 토큰 자동 갱신.
 *
 * web/game은 로그인/로그아웃 화면을 소유하지 않지만, `/api/auth/me`는 gateway와 같은 세션 회복
 * 규칙을 유지한다. access가 만료/무효(401/403)면 같은 `/api/auth` path의 `sam_refresh` 쿠키로
 * gateway-api `/auth/refresh`를 호출하고 갱신된 httpOnly 쿠키를 다시 심는다. 일시적 업스트림 오류는
 * 쿠키를 건드리지 않고 502로 전달해 유효한 7일 세션을 끊지 않는다.
 */
function isAuthFailure(status: number): boolean {
    return status === 401 || status === 403;
}

export async function GET() {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;
    const refresh = store.get(REFRESH_COOKIE)?.value;

    if (access) {
        try {
            const r = await fetch(`${GATEWAY_API_URL}/auth/me`, {
                headers: { Authorization: `Bearer ${access}` },
                cache: 'no-store',
            });
            if (r.ok) {
                const user = (await r.json()) as User;
                return NextResponse.json({ user });
            }
            if (!isAuthFailure(r.status)) {
                return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
            }
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    if (refresh) {
        try {
            const result = await refreshAccessToken(refresh);
            if (result.ok) {
                const res = NextResponse.json({ user: result.data.user });
                setAuthCookies(res, result.data);
                return res;
            }
            if (!isAuthFailure(result.status)) {
                return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
            }
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    const res = NextResponse.json({ user: null }, { status: 401 });
    clearAuthCookies(res);
    return res;
}
