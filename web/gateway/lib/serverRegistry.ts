import serversData from '@/config/servers.json';

// 서버 레지스트리 — 멀티서버 라우팅의 단일 해석 지점(서버사이드 전용).
//
// 공개 목록(id/name/status/turnterm/gameUrl)은 config/servers.json에 빌드타임 baked → 클라이언트
// (로비/로그인 탭·표)가 그대로 import. 내부 game-api 주소(gameApiUrl)는 환경마다 달라(dev=localhost,
// prod=컨테이너망 DNS, 서버마다 상이) 런타임 env로 오버라이드한다. 이 모듈은 route handler에서만
// 호출되며 process.env를 읽으므로 클라이언트 번들에 내부 주소가 새지 않는다.

export interface ServerEntry {
    id: string;
    name: string;
    status?: string;
    turnterm?: number;
    /** 게임 프론트(web/game) 진입 URL — 클라이언트 노출 OK(공개). */
    gameUrl?: string;
    /** 해당 서버 game-api 내부 주소(맵/현황 read) — 서버사이드 해석 전용. */
    gameApiUrl?: string;
}

const BAKED = (serversData.servers as ServerEntry[]) ?? [];
// 기본 서버 = 목록 첫 항목. 단일서버 prod의 GAME_API_ORIGIN 하위호환 대상.
const DEFAULT_ID = BAKED[0]?.id;

/**
 * 런타임 내부 레지스트리(prod 멀티서버). env `SERVER_REGISTRY_JSON` = 서버별 내부 game-api 주소.
 * 허용 형식:
 *   - 배열:  `[{"id":"main","gameApiUrl":"http://game-api:8081"}, ...]`
 *   - 맵:    `{"main":"http://game-api:8081","bbae":"http://bbae-api:8081"}`
 *   - 맵+객체: `{"main":{"gameApiUrl":"http://game-api:8081"}, ...}`
 * 파싱 실패/형식 오류는 무시하고 폴백 체인으로 — 절대 throw 안 함(라우트 500 방지).
 */
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

/** baked 공개 목록에서 서버 1건 조회. */
export function getServer(id: string): ServerEntry | undefined {
    return BAKED.find((s) => s.id === id);
}

/**
 * 서버별 내부 game-api origin 해석(서버사이드 전용). 우선순위:
 *  1. SERVER_REGISTRY_JSON[id]            — prod 멀티서버(서버마다 다른 컨테이너망 DNS)
 *  2. 기본서버면 GAME_API_ORIGIN          — 단일서버 prod 하위호환(현 라이브 배포)
 *  3. servers.json gameApiUrl             — dev 기본값(localhost:8081)
 * 어느 것도 없으면 undefined → 호출측이 404 처리.
 */
export function resolveGameApiOrigin(id: string): string | undefined {
    const reg = runtimeOrigins();
    if (reg[id]) return reg[id];
    if (id === DEFAULT_ID && process.env.GAME_API_ORIGIN) return process.env.GAME_API_ORIGIN;
    return getServer(id)?.gameApiUrl;
}
