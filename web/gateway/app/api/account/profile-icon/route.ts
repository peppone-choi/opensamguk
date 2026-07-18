import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';
import { GATEWAY_API_URL } from '@/lib/server-api';
import { ACCESS_COOKIE } from '@/lib/cookies';

const ICON_URL = `${GATEWAY_API_URL}/auth/account/profile-icon`;

// gateway-api의 ApiError{message} → 프론트가 읽는 {error}로 변환(비-JSON이면 기본 메시지 유지).
// 409 하루 1회, 400 디코더/모양, 413 크기 등 canonical 사유를 그대로 노출하되 상태코드는 보존한다.
function surface(status: number, text: string, fallback: string): NextResponse {
    let message = fallback;
    try {
        const j = JSON.parse(text);
        if (typeof j?.message === 'string' && j.message) message = j.message;
    } catch {
        /* 비-JSON 업스트림 → 기본 메시지 */
    }
    return NextResponse.json({ error: message }, { status });
}

async function accessToken(): Promise<string | null> {
    return (await cookies()).get(ACCESS_COOKIE)?.value ?? null;
}

export async function POST(req: Request) {
    const access = await accessToken();
    if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });

    if ((req.headers.get('content-type') ?? '').includes('multipart/form-data')) {
        // 업로드: 브라우저 body에서 file part만 추려 재구성한다. 임의 필드(userId/path/imgsvr/URL) 주입을
        // 원천 차단하고, 신원은 오직 httpOnly 쿠키의 Bearer에서만 파생된다.
        const form = await req.formData().catch(() => null);
        const file = form?.get('file');
        if (!(file instanceof File) || file.size === 0) {
            return NextResponse.json({ error: '업로드할 이미지를 선택해주세요.' }, { status: 400 });
        }
        const forward = new FormData();
        forward.append('file', file, file.name);
        try {
            const upstream = await fetch(ICON_URL, {
                method: 'POST',
                headers: { Authorization: `Bearer ${access}` },
                body: forward,
            });
            const text = await upstream.text();
            if (!upstream.ok) return surface(upstream.status, text, '전콘 업로드에 실패했습니다.');
            return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
        } catch {
            return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
        }
    }

    // 공유 전콘 선택(JSON selectShared) — 기존 경로 유지.
    const body = await req.json().catch(() => null);
    if (!body || !Object.prototype.hasOwnProperty.call(body, 'picture')) {
        return NextResponse.json({ error: '전콘 파일명을 입력해주세요.' }, { status: 400 });
    }
    try {
        const upstream = await fetch(ICON_URL, {
            method: 'POST',
            headers: { Authorization: `Bearer ${access}`, 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
        });
        const text = await upstream.text();
        if (!upstream.ok) return surface(upstream.status, text, '전콘 변경에 실패했습니다.');
        return new NextResponse(text, { status: 200, headers: { 'Content-Type': 'application/json' } });
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}

export async function DELETE() {
    const access = await accessToken();
    if (!access) return NextResponse.json({ error: '로그인이 필요합니다.' }, { status: 401 });
    try {
        const upstream = await fetch(ICON_URL, {
            method: 'DELETE',
            headers: { Authorization: `Bearer ${access}` },
        });
        if (upstream.ok) return NextResponse.json({ deleted: true });
        return surface(upstream.status, await upstream.text(), '전콘 삭제에 실패했습니다.');
    } catch {
        return NextResponse.json({ error: '게이트웨이에 연결할 수 없습니다.' }, { status: 502 });
    }
}
