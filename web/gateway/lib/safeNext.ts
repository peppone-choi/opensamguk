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

// 게이트웨이(:3000)와 게임(:3001)을 따로 띄운 로컬 개발에서는 게임이 자기 전체 주소를 next 로 넘긴다.
// 받는 곳은 설정된 게임 공개 주소(NEXT_PUBLIC_GAME_URL)의 출처 하나뿐이다 — 그 출처가 게이트웨이와 같으면(nginx 한 출처)
// 이 길은 닫히고 safeNextPath 만 쓴다. 돌려줄 때는 사용자 정보 · 조각을 버리고 출처 + 경로 + 질의만 다시 짓는다.
export function safeNextGameUrl(next: string | null | undefined, origin: string, gameUrl: string): string | null {
    if (!next || next.startsWith('/')) return null;
    if (UNSAFE.test(next) || UNSAFE.test(decodedOnce(next))) return null;
    let gameOrigin: string;
    let url: URL;
    try {
        gameOrigin = new URL(gameUrl).origin;
        url = new URL(next);
    } catch {
        return null;
    }
    if (gameOrigin === 'null' || gameOrigin === new URL(origin).origin) return null;
    if (url.origin !== gameOrigin) return null;
    return `${gameOrigin}${url.pathname}${url.search}`;
}
