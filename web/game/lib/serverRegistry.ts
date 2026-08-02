import { GAME_API_URL } from './server-api';

const CANONICAL_SERVER_ID = /^[a-z0-9]{1,48}$/;

// 인게임 멀티서버 해석(서버사이드 전용) — 서버명 → 해당 서버 game-api 내부 URL.
//
// ONE game-frontend가 여러 서버를 서빙한다(서브도메인/basePath 불요). 입장 URL `/game?server=bbae`가
// middleware로 `sam_server` 쿠키를 심으면, /api/game route handler가 이 쿠키로 대상 game-api를 고른다.
// env `SERVER_REGISTRY_JSON`은 gateway(로비)와 동일 포맷 — 배열 `[{"id","gameApiUrl"}]` 또는 맵.
// 선택자가 없을 때만 GAME_API_URL(= 기본 game-api)을 사용한다. process.env는 서버에서만 읽혀
// 내부 주소가 클라이언트 번들에 노출되지 않는다.

function isCanonicalServerId(serverId: string): boolean {
    return CANONICAL_SERVER_ID.test(serverId);
}

function runtimeOrigins(): Record<string, string> {
    const raw = process.env.SERVER_REGISTRY_JSON;
    if (!raw) return {};
    try {
        const parsed = JSON.parse(raw);
        const out: Record<string, string> = {};
        if (Array.isArray(parsed)) {
            for (const e of parsed) {
                if (
                    e &&
                    typeof e.id === 'string' &&
                    isCanonicalServerId(e.id) &&
                    typeof e.gameApiUrl === 'string' &&
                    e.gameApiUrl.trim()
                ) {
                    out[e.id] = e.gameApiUrl.trim();
                }
            }
        } else if (parsed && typeof parsed === 'object') {
            for (const [id, v] of Object.entries(parsed)) {
                if (!isCanonicalServerId(id)) continue;
                if (typeof v === 'string' && v.trim()) {
                    out[id] = v.trim();
                } else if (
                    v &&
                    typeof v === 'object' &&
                    typeof (v as { gameApiUrl?: unknown }).gameApiUrl === 'string' &&
                    (v as { gameApiUrl: string }).gameApiUrl.trim()
                ) {
                    out[id] = (v as { gameApiUrl: string }).gameApiUrl.trim();
                }
            }
        }
        return out;
    } catch {
        return {};
    }
}

/** 서버명 → game-api 내부 origin. 선택자가 없을 때만 기본 GAME_API_URL을 반환한다. */
export function resolveGameApiUrl(serverId: string | undefined | null): string | undefined {
    if (serverId == null) return GAME_API_URL;
    if (!isCanonicalServerId(serverId)) return undefined;
    return runtimeOrigins()[serverId];
}
