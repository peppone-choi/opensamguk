import { NextRequest, NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { setAuthCookies } from '@/lib/cookies';
import type { AuthResponse } from '@/lib/types';

export async function POST(req: NextRequest) {
    const body = await req.json().catch(() => null);
    if (!body?.username || !body?.password) {
        return NextResponse.json({ error: '아이디와 비밀번호를 입력해주세요.' }, { status: 400 });
    }

    const upstream = await fetch(`${GATEWAY_API_URL}/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: body.username, password: body.password }),
    });

    const text = await upstream.text();
    if (!upstream.ok) {
        let message = '아이디 또는 비밀번호가 올바르지 않습니다.';
        try {
            const j = JSON.parse(text);
            if (typeof j?.message === 'string' && j.message) message = j.message;
        } catch {
            /* gateway-api가 비-JSON 응답 → 기본 메시지 유지 */
        }
        return NextResponse.json({ error: message }, { status: upstream.status === 0 ? 502 : upstream.status });
    }

    const data = JSON.parse(text) as AuthResponse;
    const res = NextResponse.json({ user: data.user });
    setAuthCookies(res, data);
    return res;
}
