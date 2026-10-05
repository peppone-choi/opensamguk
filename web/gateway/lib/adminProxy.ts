// 운영 콘솔 프록시(`/api/proxy/**`)의 경로 허용 목록과 응답 정리.
// 화면은 gateway-api 의 `admin/**` 만 부른다(운영 콘솔 · 회원 · 공지 · 턴 · 배포 · 서버 수명). 그 밖의 경로는 이 프록시로 보내지 않는다.

/** 허용하는 첫 조각. 이 밖은 404 다. */
const ALLOWED_ROOT = 'admin';

/** 응답 JSON 에서 지우는 필드 — 프록시는 인증 토큰을 화면 스크립트에 넘기지 않는다. */
const STRIPPED_KEYS = new Set(['accessToken', 'refreshToken']);

// 제어문자(U+0000–U+001F, U+007F)와 역슬래시 · 경로 구분자.
// eslint-disable-next-line no-control-regex
const UNSAFE_SEGMENT = /[\u0000-\u001f\u007f\\/]/;

/**
 * Next 가 푼(디코드한) 경로 조각을 검사해 위로 보낼 경로를 만든다. 허용하지 않으면 null.
 * - 첫 조각은 `admin`, 그 뒤로 조각이 하나 이상 있어야 한다.
 * - 빈 조각 · `.` · `..` · 구분자(인코딩된 `/` 가 풀린 것) · 역슬래시 · 제어문자가 든 조각은 거절한다.
 * - 조각은 다시 인코딩해 보낸다(공백 · `?` · `#` 이 위에서 다른 뜻이 되지 않게).
 */
export function adminProxyPath(segments: readonly string[]): string | null {
    if (segments.length < 2 || segments[0] !== ALLOWED_ROOT) return null;
    for (const segment of segments) {
        if (segment === '' || segment === '.' || segment === '..' || UNSAFE_SEGMENT.test(segment)) return null;
    }
    return segments.map((segment) => encodeURIComponent(segment)).join('/');
}

function strip(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(strip);
    if (value && typeof value === 'object') {
        const out: Record<string, unknown> = {};
        for (const [key, inner] of Object.entries(value as Record<string, unknown>)) {
            if (!STRIPPED_KEYS.has(key)) out[key] = strip(inner);
        }
        return out;
    }
    return value;
}

/** JSON 응답 본문에서 토큰 필드를 지운다. JSON 이 아니거나 읽을 수 없으면 그대로 둔다. */
export function sanitizeProxyBody(body: string, contentType: string | null): string {
    if (body === '' || !contentType?.toLowerCase().includes('json')) return body;
    try {
        return JSON.stringify(strip(JSON.parse(body)));
    } catch {
        return body;
    }
}
