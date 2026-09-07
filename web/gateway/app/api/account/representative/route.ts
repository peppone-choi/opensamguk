import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { ACCESS_COOKIE } from '@/lib/cookies';
import { GATEWAY_API_URL } from '@/lib/server-api';

// ADR-LITE-049 13 — 계정 대표 장수. 신원(Bearer)은 httpOnly 쿠키에서만 붙인다. 소유 검증은 gateway-api 가 한다.
async function forward(method: 'GET' | 'POST', body?: string): Promise<NextResponse> {
  const access = (await cookies()).get(ACCESS_COOKIE)?.value;
  if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });
  try {
    const upstream = await fetch(`${GATEWAY_API_URL}/auth/account/representative`, {
      method,
      headers: { Authorization: `Bearer ${access}`, ...(body != null ? { 'Content-Type': 'application/json' } : {}) },
      body,
      cache: 'no-store',
    });
    const text = await upstream.text();
    if (!upstream.ok) {
      const payload = JSON.parse(text || '{}') as { message?: unknown };
      const message = typeof payload.message === 'string' ? payload.message : '대표 장수를 처리하지 못했습니다.';
      return NextResponse.json({ error: message }, { status: upstream.status });
    }
    return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json', 'Cache-Control': 'private, no-store' } });
  } catch (error) {
    if (error instanceof SyntaxError) {
      return NextResponse.json({ error: '게이트웨이 응답을 해석할 수 없습니다.' }, { status: 502 });
    }
    return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
  }
}

export async function GET(): Promise<NextResponse> {
  return forward('GET');
}

export async function POST(request: Request): Promise<NextResponse> {
  const body = await request.json().catch(() => null) as { generalId?: unknown } | null;
  if (body == null || (body.generalId !== null && typeof body.generalId !== 'number')) {
    return NextResponse.json({ error: '대표 장수 id 가 올바르지 않습니다.' }, { status: 400 });
  }
  return forward('POST', JSON.stringify({ generalId: body.generalId }));
}
