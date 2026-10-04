// 게임 프록시(`/api/game/**`)의 경로 허용 목록. 화면은 game-api 의 `api/**` 와 턴 알림 `sse/turn` 만 부른다.
// 내부 · 관리 경로(internal · actuator)는 어느 자리에 있어도 보내지 않는다.

const TURN_SSE = 'sse/turn';
const BLOCKED_SEGMENTS = new Set(['internal', 'actuator']);
// eslint-disable-next-line no-control-regex
const UNSAFE_SEGMENT = /[\u0000-\u001f\u007f\\/]/;

/** Next 가 푼 경로 조각으로 위에 보낼 경로를 만든다. 허용하지 않으면 null. 조각은 다시 인코딩한다. */
export function gameProxyPath(segments: readonly string[]): string | null {
    if (segments.join('/') === TURN_SSE) return TURN_SSE;
    if (segments.length < 2 || segments[0] !== 'api') return null;
    for (const segment of segments) {
        if (segment === '' || segment === '.' || segment === '..' || UNSAFE_SEGMENT.test(segment)) return null;
        if (BLOCKED_SEGMENTS.has(segment.toLowerCase())) return null;
    }
    return segments.map((segment) => encodeURIComponent(segment)).join('/');
}
