// 황제 소재(K8 요청, game-api /api/imperial/presence) 클라이언트 계약. 화면 규칙(설계서 P-G02 · P-G04):
// READY → 「황제 — 허현」, NOT_SEEDED → 아무것도 보이지 않는다(빈 문구도 없음), 그 밖(409 · 연결 실패) → 오류 한 줄 + 다시 시도.
/** 게이트웨이 공개 경로가 내보내는 칸(lib/publicFeeds 허용 목록). */
export interface ImperialBadge {
    readonly lineCode: string;
    readonly lineName: string;
    readonly emperorCityId: number | null;
}

export type ImperialPresence =
    | { readonly kind: 'ready'; readonly badges: readonly ImperialBadge[] }
    | { readonly kind: 'hidden' }
    | { readonly kind: 'error' };

function isBadge(value: unknown): value is ImperialBadge {
    if (typeof value !== 'object' || value === null) return false;
    const v = value as Record<string, unknown>;
    return typeof v.lineCode === 'string' && typeof v.lineName === 'string'
        && (v.emperorCityId === null || typeof v.emperorCityId === 'number');
}

export async function fetchImperialPresence(serverId: string, signal?: AbortSignal): Promise<ImperialPresence> {
    try {
        const response = await fetch(`/api/server-imperial/${encodeURIComponent(serverId)}`, { cache: 'no-store', signal });
        const data = (await response.json().catch(() => null)) as { status?: unknown; badges?: unknown } | null;
        if (response.ok && data?.status === 'NOT_SEEDED') return { kind: 'hidden' };
        if (response.ok && data?.status === 'READY' && Array.isArray(data.badges)) {
            const badges = data.badges.filter(isBadge);
            return badges.length > 0 ? { kind: 'ready', badges } : { kind: 'hidden' };
        }
        return { kind: 'error' };
    } catch {
        return { kind: 'error' };
    }
}

/** 황제가 있는 곳 — 城 안이면 그 현 이름(모르면 「어느 현」), 城 밖이면 「성 밖」. 이름을 지어내지 않는다. */
export function emperorPlace(badge: ImperialBadge, cityName: (id: number) => string | undefined): string {
    if (badge.emperorCityId == null) return '성 밖';
    return cityName(badge.emperorCityId) ?? '어느 현';
}
