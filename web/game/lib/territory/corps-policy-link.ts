// 군단 → 영지 방침(P-T01) 주소 문맥. 군단 화면이 고른 내 군단을 `?view=policy&scope=CORPS&orderId=…` 로 넘기고,
// 영지가 같은 주소를 읽는다. orderId 는 `/api/corps` 의 corpsId(= 방침 행 orderId) 불투명 문자열 그대로다 —
// 숫자 · 장수 id 로 바꾸지 않고 주소 인코딩만 한다. 권한 · 대상 존재는 목적지의 최신 방침 읽기가 정한다.
import { isPathServerId, resolveServerGamePath } from '../serverGameUrl';

/** 주소로 받는 orderId 길이 상한 — 넘으면 잘못된 주소로 본다(잘라 쓰지 않는다). */
export const CORPS_ORDER_ID_MAX = 200;

export type TerritoryPolicyQuery =
    /** 문맥 없음 — 영지 기본 동작 그대로. */
    | { readonly kind: 'none' }
    /** 군단 방침 목록만(대상 없이). */
    | { readonly kind: 'corpsList' }
    | { readonly kind: 'corps'; readonly orderId: string }
    /** 방침 문맥을 주었지만 읽을 수 없는 값 — 다른 대상으로 대신하지 않는다. */
    | { readonly kind: 'invalid' };

const NONE: TerritoryPolicyQuery = { kind: 'none' };

/**
 * 영지 주소 → 방침 문맥. `view=policy` 일 때만 본다. scope 가 없으면 문맥 없음(기본 동작), scope=CORPS 면 orderId 유무로
 * 목록 · 대상, 그 밖의 scope · 빈 orderId · scope 없는 orderId · 제어 문자 · 너무 긴 값은 invalid.
 */
export function parseTerritoryPolicyQuery(query: { get(name: string): string | null } | null | undefined): TerritoryPolicyQuery {
    if (!query || query.get('view') !== 'policy') return NONE;
    const scope = query.get('scope');
    const orderId = query.get('orderId');
    if (scope == null) return orderId == null ? NONE : { kind: 'invalid' };
    if (scope !== 'CORPS') return { kind: 'invalid' };
    if (orderId == null) return { kind: 'corpsList' };
    if (orderId.trim() === '' || orderId.length > CORPS_ORDER_ID_MAX || hasControlChar(orderId)) return { kind: 'invalid' };
    return { kind: 'corps', orderId };
}

function hasControlChar(value: string): boolean {
    for (let i = 0; i < value.length; i += 1) {
        const code = value.charCodeAt(i);
        if (code < 0x20 || code === 0x7f) return true;
    }
    return false;
}

/** 영지 기준 주소(`/game/<서버>/territory` 등)에 군단 방침 문맥을 붙인다. orderId 가 null 이면 군단 목록. */
export function corpsPolicyHref(territoryBase: string, orderId: string | null): string {
    const query = `view=policy&scope=CORPS${orderId != null ? `&orderId=${encodeURIComponent(orderId)}` : ''}`;
    return `${territoryBase}${territoryBase.includes('?') ? '&' : '?'}${query}`;
}

/** 지금 탭 주소의 명시 서버(`/game/<서버>/…`). 없으면 null — 쿠키보다 이 값을 먼저 쓴다(다른 탭이 쿠키를 바꿔도). */
export function tabServerId(pathname: string | null | undefined): string | null {
    const segments = (pathname ?? '').split('/');
    if (segments[1] !== 'game') return null;
    const id = segments[2] ?? '';
    return isPathServerId(id) ? id : null;
}

/** 지금 탭 서버가 있으면 그 서버의 영지, 없으면 호출부가 준 기존 주소(쿠키 기반). */
export function territoryBaseFor(pathname: string | null | undefined, fallback: string): string {
    const server = tabServerId(pathname);
    return server ? resolveServerGamePath(undefined, server, '/game', 'territory') : fallback;
}
