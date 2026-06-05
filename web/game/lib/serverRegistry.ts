import { GAME_API_URL } from './server-api';

// 인게임 멀티서버 해석(서버사이드 전용) — 서버명 → 해당 서버 game-api 내부 URL.
//
// ONE game-frontend가 여러 서버를 서빙한다(서브도메인/basePath 불요). 입장 URL `/game?server=bbae`가
// middleware로 `sam_server` 쿠키를 심으면, /api/game route handler가 이 쿠키로 대상 game-api를 고른다.
// env `SERVER_REGISTRY_JSON`은 gateway(로비)와 동일 포맷 — 배열 `[{"id","gameApiUrl"}]` 또는 맵.
// 기본 서버(main/미선택/미지값)는 GAME_API_URL(= 기본 game-api). process.env는 서버에서만 읽혀
// 내부 주소가 클라이언트 번들에 노출되지 않는다.

function runtimeOrigins(): Record<string, string> {
    const raw = process.env.SERVER_REGISTRY_JSON;
    if (!raw) return {};
    try {
        const parsed = JSON.parse(raw);
        const out: Record<string, string> = {};
        if (Array.isArray(parsed)) {
            for (const e of parsed) {
                if (e && typeof e.id === 'string' && typeof e.gameApiUrl === 'string') out[e.id] = e.gameApiUrl;
            }
        } else if (parsed && typeof parsed === 'object') {
            for (const [id, v] of Object.entries(parsed)) {
                if (typeof v === 'string') out[id] = v;
                else if (v && typeof v === 'object' && typeof (v as { gameApiUrl?: unknown }).gameApiUrl === 'string') {
                    out[id] = (v as { gameApiUrl: string }).gameApiUrl;
                }
            }
        }
        return out;
    } catch {
        return {};
    }
}

/** 서버명 → game-api 내부 origin. 미선택/main/미지값 → 기본 GAME_API_URL. */
export function resolveGameApiUrl(serverId: string | undefined | null): string {
    if (!serverId || serverId === 'main') return GAME_API_URL;
    return runtimeOrigins()[serverId] ?? GAME_API_URL;
}
