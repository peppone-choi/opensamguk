import { NextResponse } from 'next/server';

/**
 * gateway-api 의 ApiError{message} → 화면이 읽는 {error}(설계서 §2.5 A12). 서버 문장은 받은 그대로 보이고
 * 상태 코드는 지킨다. JSON 이 아니거나 문장이 없으면 기본 문장.
 */
export function upstreamErrorResponse(status: number, text: string, fallback: string): NextResponse {
    let message = fallback;
    try {
        const j = JSON.parse(text);
        if (typeof j?.message === 'string' && j.message) message = j.message;
    } catch {
        /* 비-JSON 업스트림 → 기본 문장 */
    }
    return NextResponse.json({ error: message }, { status });
}
