import { GATEWAY_API_URL } from './server-api';
import type { User } from './types';

/**
 * `sam_refresh` 쿠키로 gateway-api `/auth/refresh`를 호출해 새 access/refresh 토큰을 발급받는다.
 * `/api/auth/me`와 `/api/game/[...path]` 프록시가 공유한다(둘 다 401 → refresh → 재시도 패턴).
 */
export type AuthTokens = { accessToken: string; refreshToken: string; user: User };

export async function refreshAccessToken(
    refreshToken: string,
): Promise<{ ok: true; data: AuthTokens } | { ok: false; status: number }> {
    const rr = await fetch(`${GATEWAY_API_URL}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
        cache: 'no-store',
    });
    if (rr.ok) {
        return { ok: true, data: (await rr.json()) as AuthTokens };
    }
    return { ok: false, status: rr.status };
}
