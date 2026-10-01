import { NextRequest, NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { setAuthCookies } from '@/lib/cookies';
import type { AuthResponse } from '@/lib/types';

export async function POST(req: NextRequest) {
    const body = await req.json().catch(() => null);
    if (!body?.username || !body?.password) {
        return NextResponse.json({ error: '계정명과 비밀번호를 입력하세요.' }, { status: 400 });
    }
    if (!body?.nickname) {
        return NextResponse.json({ error: '별명을 입력하세요.' }, { status: 400 });
    }

    const payload: Record<string, unknown> = {
        username: body.username,
        password: body.password,
    };
    if (body.email) payload.email = body.email;
    payload.nickname = body.nickname;

    let upstream: Response;
    try {
        upstream = await fetch(`${GATEWAY_API_URL}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload),
        });
    } catch {
        // 연결 실패는 서버 거절과 다르다(설계서 P-G03 — 전에는 처리하지 않아 500 이 났다).
        return NextResponse.json({ error: '가입 서버에 연결할 수 없습니다.' }, { status: 502 });
    }

    const text = await upstream.text();
    if (!upstream.ok) {
        let message = '가입하지 못했습니다.';
        try {
            const j = JSON.parse(text);
            if (typeof j?.message === 'string' && j.message) message = j.message;
        } catch {
            /* 비-JSON → 기본 메시지 */
        }
        return NextResponse.json({ error: message }, { status: upstream.status === 0 ? 502 : upstream.status });
    }

    const data = JSON.parse(text) as AuthResponse;
    const res = NextResponse.json({ user: data.user });
    setAuthCookies(res, data);
    return res;
}
