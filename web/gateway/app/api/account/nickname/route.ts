import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { ACCESS_COOKIE, setAuthCookies } from '@/lib/cookies';
import { GATEWAY_API_URL } from '@/lib/server-api';
import type { AuthResponse } from '@/lib/types';

export async function POST(request: Request): Promise<NextResponse> {
  const access = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });

  const body = await request.json().catch(() => null) as { nickname?: unknown } | null;
  if (typeof body?.nickname !== 'string') {
    return NextResponse.json({ error: '닉네임을 입력해주세요.' }, { status: 400 });
  }

  try {
    const upstream = await fetch(`${GATEWAY_API_URL}/auth/account/nickname`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ nickname: body.nickname }),
    });
    const text = await upstream.text();
    if (!upstream.ok) {
      const payload = JSON.parse(text) as { message?: unknown };
      const message = typeof payload.message === 'string' ? payload.message : '닉네임 변경에 실패했습니다.';
      return NextResponse.json({ error: message }, { status: upstream.status });
    }

    const auth = JSON.parse(text) as AuthResponse;
    const response = NextResponse.json({ user: auth.user });
    setAuthCookies(response, auth);
    return response;
  } catch (error) {
    if (error instanceof SyntaxError) {
      return NextResponse.json({ error: '게이트웨이 응답을 해석할 수 없습니다.' }, { status: 502 });
    }
    return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
  }
}
