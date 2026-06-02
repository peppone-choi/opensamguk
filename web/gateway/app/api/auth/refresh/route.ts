import { NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { REFRESH_COOKIE, clearAuthCookies, setAuthCookies } from '@/lib/cookies';
import type { AuthResponse } from '@/lib/types';

/** 클라이언트의 401 재시도용 명시적 토큰 갱신. 일시적 5xx는 쿠키 유지·502, 인증 실패만 쿠키 정리. */
export async function POST() {
    const store = await cookies();
    const refresh = store.get(REFRESH_COOKIE)?.value;
    if (!refresh) {
        const res = NextResponse.json({ user: null }, { status: 401 });
        clearAuthCookies(res);
        return res;
    }

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
        if (rr.status === 401 || rr.status === 403) {
            const res = NextResponse.json({ user: null }, { status: 401 });
            clearAuthCookies(res);
            return res;
        }
        // 일시적 업스트림 오류 — 쿠키 유지.
        return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}
