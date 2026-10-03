// 요청 본문 크기 선검사 — content-length 가 상한을 넘으면 본문을 읽기 전에 413 으로 돌려보낸다.
// (content-length 가 없는 청크 전송은 여기서 거르지 않는다.)
import { MAX_SOURCE_BYTES } from './portraitCrop';

/** 프록시가 받는 JSON 본문 상한. 화면이 보내는 본문(게시글 · 명령 · 설정)은 수십 KB 안이다. */
export const MAX_JSON_BODY_BYTES = 1024 * 1024;
/** 초상 올리기 상한 — 원본 8MB(MAX_SOURCE_BYTES) + 자르기 정보 · multipart 머리 여유. */
export const MAX_UPLOAD_BODY_BYTES = MAX_SOURCE_BYTES + 64 * 1024;

export function bodyTooLarge(headers: Headers, limit: number): boolean {
    const raw = headers.get('content-length');
    if (raw === null) return false;
    const length = Number(raw);
    return Number.isFinite(length) && length > limit;
}

export function tooLargeResponse(): Response {
    return Response.json({ error: '요청 본문이 너무 큽니다.' }, { status: 413 });
}
