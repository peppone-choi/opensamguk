import { NextRequest, NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { setAuthCookies } from '@/lib/cookies';
import type { AuthResponse } from '@/lib/types';

export async function POST(req: NextRequest) {
    const body = await req.json().catch(() => null);
    if (!body?.username || !body?.password) {
        return NextResponse.json({ error: '아이디와 비밀번호를 입력해주세요.' }, { status: 400 });
    }
    if (!body?.nickname) {
        return NextResponse.json({ error: '별명을 입력해주세요' }, { status: 400 });
    }

    const payload: Record<string, unknown> = {
        username: body.username,
        password: body.password,
    };
    if (body.email) payload.email = body.email;
    payload.nickname = body.nickname;

    const upstream = await fetch(`${GATEWAY_API_URL}/auth/register`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
    });

    const text = await upstream.text();
    if (!upstream.ok) {
        let message = '회원가입에 실패했습니다.';
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
