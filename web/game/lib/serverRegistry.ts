import { GAME_API_URL } from './server-api';

const CANONICAL_SERVER_ID = /^[a-z0-9]{1,48}$/;
const RESERVED_SERVER_IDS = new Set([
    'all',
    'main',
    'admin1',
    'admin2',
    'admin5',
    'admin7',
    'admin8',
    'auction',
    'battle-center',
    'betting',
    'board',
    'chief-center',
    'city',
    'coming-soon',
    'diplomacy',
    'generals',
    'global-diplomacy',
    'history',
    'inherit',
    'join',
    'mailbox',
    'map',
    'my',
    'my-boss',
    'my-cities',
    'my-generals',
    'my-nation',
    'nation',
    'nation-betting',
    'nation-finance',
    'npc-control',
    'rankings',
    'register',
    'select-pool',
    'simulator',
    'tournament',
    'tournament-admin',
    'troop',
    'vote',
    'world-log',
]);

// 인게임 멀티서버 해석(서버사이드 전용) — 서버명 → 해당 서버 game-api 내부 URL.
//
// SERVER_REGISTRY_JSON는 모두 유효한 canonical entry여야 한다. 하나라도 잘못되면 collection 전체를
// 거부한다. 다만 이 컨테이너 자신의 canonical SERVER_ID는 registry 없이도 GAME_API_URL로 해석한다.

function isPublicServerId(serverId: string): boolean {
    return CANONICAL_SERVER_ID.test(serverId) && !RESERVED_SERVER_IDS.has(serverId);
}

function expectedGameApiUrl(serverId: string): string {
    return `http://s${serverId}-game-api:8081`;
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function originForEntry(serverId: string, entry: unknown): string | undefined {
    if (!isPublicServerId(serverId)) return undefined;
    const expected = expectedGameApiUrl(serverId);
    if (typeof entry === 'string') return entry === expected ? expected : undefined;
    if (!isRecord(entry)) return undefined;
    if ('id' in entry && (typeof entry.id !== 'string' || entry.id !== serverId)) return undefined;
    if (
        'gameApiUrl' in entry &&
        (typeof entry.gameApiUrl !== 'string' || entry.gameApiUrl !== expected)
    ) {
        return undefined;
    }
    return expected;
}

function parseOrigins(value: unknown): Map<string, string> | undefined {
    const origins = new Map<string, string>();
    if (Array.isArray(value)) {
        for (const entry of value) {
            if (!isRecord(entry) || typeof entry.id !== 'string') return undefined;
            const origin = originForEntry(entry.id, entry);
            if (!origin || origins.has(entry.id)) return undefined;
            origins.set(entry.id, origin);
        }
        return origins;
    }
    if (!isRecord(value)) return undefined;
    for (const [serverId, entry] of Object.entries(value)) {
        const origin = originForEntry(serverId, entry);
        if (!origin || origins.has(serverId)) return undefined;
        origins.set(serverId, origin);
    }
    return origins;
}

function runtimeOrigins(): ReadonlyMap<string, string> {
    const raw = process.env.SERVER_REGISTRY_JSON;
    if (!raw || raw.trim() === '') return new Map();
    try {
        const parsed: unknown = JSON.parse(raw);
        return parseOrigins(parsed) ?? new Map();
    } catch {
        return new Map();
    }
}

function configuredServerId(): string | undefined {
    const serverId = process.env.SERVER_ID;
    return serverId && isPublicServerId(serverId) ? serverId : undefined;
}

/** 서버명 → game-api 내부 origin. 선택자가 없거나 이 컨테이너의 ID일 때만 GAME_API_URL을 반환한다. */
export function resolveGameApiUrl(serverId: string | undefined | null): string | undefined {
    if (serverId == null) return GAME_API_URL;
    if (!isPublicServerId(serverId)) return undefined;
    if (serverId === configuredServerId()) return GAME_API_URL;
    return runtimeOrigins().get(serverId);
}
