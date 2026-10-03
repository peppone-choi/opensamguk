// 로그인 뒤 이동 주소(`?next=`) — 같은 출처의 경로 + 질의만 쓴다. 아니면 로비.
// 역슬래시 · 제어문자(풀어 본 값 포함)가 든 값은 받지 않는다(브라우저가 경로 앞 `/\` 를 `//` 처럼 읽는 등 해석이 갈린다).

const FALLBACK = '/lobby';
// eslint-disable-next-line no-control-regex
const UNSAFE = /[\\\u0000-\u001f\u007f]/;

function decodedOnce(value: string): string {
    try {
        return decodeURIComponent(value);
    } catch {
        return value;
    }
}

export function safeNextPath(next: string | null | undefined, origin: string): string {
    if (!next || !next.startsWith('/') || next.startsWith('//')) return FALLBACK;
    if (UNSAFE.test(next) || UNSAFE.test(decodedOnce(next))) return FALLBACK;
    let url: URL;
    try {
        url = new URL(next, origin);
    } catch {
        return FALLBACK;
    }
    if (url.origin !== new URL(origin).origin) return FALLBACK;
    return `${url.pathname}${url.search}`;
}
