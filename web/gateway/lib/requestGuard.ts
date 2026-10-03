// 게이트웨이 `/api/**` 의 비-GET 요청 확인(미들웨어에서 부른다).
// - 출처: 브라우저가 보내는 Sec-Fetch-Site 가 있으면 그것으로 본다(same-origin · none 만). 운영 프록시가 Host 를
//   어떻게 넘기든 판정이 흔들리지 않는다. 없으면(옛 브라우저) Origin 이 이 호스트와 같아야 한다.
//   둘 다 없으면 브라우저 밖(서버 간) 호출로 보고 지나게 한다.
// - 본문: 본문이 있으면 Content-Type 이 JSON 이어야 한다(아니면 415). 초상 올리기(multipart)는 출처만 본다.

const MUTATING = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
const MULTIPART_PATHS = new Set(['/api/account/profile-icon']);
const JSON_TYPE = /^application\/json(\s*;|$)/i;

export type RequestCheck = { readonly status: 403 | 415; readonly error: string } | null;

interface CheckInput {
    readonly method: string;
    readonly pathname: string;
    readonly headers: Headers;
    /** 요청 주소의 호스트(req.nextUrl.host). Host 머리글과 함께 「같은 호스트」로 본다. */
    readonly host: string;
}

function sameOrigin(headers: Headers, requestHost: string): boolean {
    const site = headers.get('sec-fetch-site');
    // same-site(형제 하위 도메인) · cross-site 는 거절한다. none 은 사람이 주소창 · 북마크로 직접 연 요청이라 POST 로는 오지 않지만,
    // Fetch Metadata 권장 규칙(same-origin · none 허용)에 맞춰 둔다.
    if (site !== null) return site === 'same-origin' || site === 'none';
    const origin = headers.get('origin');
    if (origin !== null) {
        if (origin === 'null') return false;
        let host: string;
        try {
            host = new URL(origin).host;
        } catch {
            return false;
        }
        const own = [headers.get('host'), headers.get('x-forwarded-host'), requestHost].filter((h): h is string => !!h);
        return own.some((h) => h.split(',')[0].trim().toLowerCase() === host.toLowerCase());
    }
    return true;
}

function hasBody(headers: Headers): boolean {
    const length = Number(headers.get('content-length') ?? '0');
    return (Number.isFinite(length) && length > 0) || headers.has('transfer-encoding');
}

export function checkApiRequest({ method, pathname, headers, host }: CheckInput): RequestCheck {
    if (!MUTATING.has(method.toUpperCase())) return null;
    if (!sameOrigin(headers, host)) return { status: 403, error: '허용되지 않은 요청입니다.' };
    if (MULTIPART_PATHS.has(pathname)) return null;
    if (hasBody(headers) && !JSON_TYPE.test((headers.get('content-type') ?? '').trim())) {
        return { status: 415, error: 'JSON 본문만 받습니다.' };
    }
    return null;
}
