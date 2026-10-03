import DOMPurify from 'dompurify';

// 커뮤니티 글 본문 화면 정리 — 서버(board-api GatewayBoardContentSanitizer)가 이미 태그만 남기지만, 화면도 같은 허용 목록으로
// 한 번 더 거른다. 태그는 서버 허용 목록과 같고 속성은 하나도 두지 않는다. 게임 SafeHtml 과 같은 방식(DOMPurify).
export const BOARD_ALLOWED_TAGS = ['p', 'br', 'strong', 'b', 'em', 'i', 's', 'ul', 'ol', 'li', 'blockquote', 'code', 'pre', 'h1', 'h2', 'h3'];

/** 브라우저에서만 정리한다. 서버 렌더(창 없음)에서는 빈 문자열 — 본문은 화면이 글을 받은 뒤에만 그린다. */
export function sanitizeBoardHtml(html: string): string {
    if (typeof window === 'undefined' || typeof DOMPurify.sanitize !== 'function') return '';
    return DOMPurify.sanitize(html, { ALLOWED_TAGS: BOARD_ALLOWED_TAGS, ALLOWED_ATTR: [], RETURN_TRUSTED_TYPE: false }) as string;
}
