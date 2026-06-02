import { NextResponse } from 'next/server';
import { cookies } from 'next/headers';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';
import type { User } from '@/lib/types';

/**
 * 현재 사용자 조회 — 읽기 전용.
 *
 * web/game은 토큰을 발급/갱신하지 않는다(게이트웨이 소유). sam_access 쿠키를 서버사이드로 읽어
 * gateway-api /auth/me에 Bearer로 전달하고, 그 결과를 {user}로 반환한다. 쿠키가 없거나 인증 실패면
 * 401 {user:null} → AuthGate가 게이트웨이 로그인으로 보낸다. 일시적 업스트림 오류(5xx/네트워크)는
 * 502로 전달(쿠키 만료 처리를 트리거하지 않음 — 여기선 쿠키를 건드리지 않는다).
 */
export async function GET() {
    const store = await cookies();
    const access = store.get(ACCESS_COOKIE)?.value;

    if (!access) {
        return NextResponse.json({ user: null }, { status: 401 });
    }

    try {
        const r = await fetch(`${GATEWAY_API_URL}/auth/me`, {
            headers: { Authorization: `Bearer ${access}` },
            cache: 'no-store',
        });
        if (r.ok) {
            const user = (await r.json()) as User;
            return NextResponse.json({ user });
        }
        if (r.status === 401 || r.status === 403) {
            // 만료/무효 — 게이트웨이가 refresh를 소유. 여기선 단순 미인증으로 취급.
            return NextResponse.json({ user: null }, { status: 401 });
        }
        // 일시적 업스트림 오류 — 세션을 죽이지 않는다.
        return NextResponse.json({ error: '일시적 오류가 발생했습니다.' }, { status: 502 });
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}
