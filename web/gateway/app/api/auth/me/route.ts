import { NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE, REFRESH_COOKIE, clearAuthCookies, setAuthCookies } from '@/lib/cookies';
import type { AuthResponse, User } from '@/lib/types';

/**
 * 현재 사용자 조회 + 토큰 자동 갱신.
 *
 * access가 유효하면 그대로 반환. access가 인증 실패(401/403, 만료/무효)면 refresh로 재발급.
 * 둘 다 인증 실패면 쿠키를 비우고 401. **일시적 업스트림 오류(5xx/네트워크)는 쿠키를
 * 건드리지 않고 502로 전달** — 백엔드 순간 장애로 유효한 7일 세션을 날리지 않기 위함.
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
                // 일시적 업스트림 오류 — 쿠키 유지, 전달.
                return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
            }
            // 401/403 → 만료/무효 → 아래 refresh 시도.
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    if (refresh) {
        try {
            const rr = await fetch(`${GATEWAY_API_URL}/auth/refresh`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ refreshToken: refresh }),
            });
            if (rr.ok) {
                const data = (await rr.json()) as AuthResponse;
                const res = NextResponse.json({ user: data.user });
                setAuthCookies(res, data);
                return res;
            }
            if (!isAuthFailure(rr.status)) {
                return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
            }
            // refresh도 인증 실패 → 진짜 만료 → 아래에서 쿠키 정리.
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    const res = NextResponse.json({ user: null }, { status: 401 });
    clearAuthCookies(res);
    return res;
}
