// All game-api calls go through the same-origin server-side proxy at /api/game/[...path].
// The proxy reads the httpOnly sam_access cookie and attaches Authorization: Bearer to game-api(:8081),
// so the JWT never reaches client JS. Paths below keep their /api/... prefix (game-api's own routes);
// the proxy strips the /api/game segment and forwards /api/... verbatim.
const BASE = '/api/game';

import type {
    FrontInfoResponse,
    GlobalMenuResponse,
    GameConstResponse,
    ClaimableResponse,
    ClaimResponse,
    MapPreviewResponse,
} from './types';

async function get<T>(path: string): Promise<T> {
    const res = await fetch(`${BASE}${path}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
    return res.json() as Promise<T>;
}

async function post<T>(path: string, body: unknown): Promise<T> {
    const res = await fetch(`${BASE}${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!res.ok) throw new Error(`${res.status}: ${res.statusText}`);
    return res.json() as Promise<T>;
}

export const api = {
    get,
    post,

    // Identity envelope + server-driven menu/const (F2 Wave 1)
    frontInfo: () => get<FrontInfoResponse>('/api/front-info'),
    globalMenu: () => get<GlobalMenuResponse>('/api/global-menu'),
    gameConst: () => get<GameConstResponse>('/api/const'),

    // World map snapshot (F2 Wave 4 MapViewer) — same endpoint the gateway lobby MapPreview consumes.
    mapPreview: () => get<MapPreviewResponse>('/api/map/preview'),

    // Possession (장수 점유 / 빙의) — AUTH (identity resolved from Bearer)
    claimable: () => get<ClaimableResponse>('/api/generals/claimable'),
    claim: (generalId: number) => post<ClaimResponse>('/api/general/claim', { generalId }),

    // My pages
    myPage: <T>() => get<T>('/api/my-page'),
    myGenerals: <T>() => get<T>('/api/my-generals'),
    myCities: <T>() => get<T>('/api/my-cities'),
    myBoss: <T>() => get<T>('/api/my-boss'),
    myNationDetail: <T>() => get<T>('/api/my-nation-detail'),
    city: <T>(id: number) => get<T>(`/api/city/${id}`),
    generals: <T>() => get<T>('/api/generals'),
    tournament: <T>() => get<T>('/api/tournament'),

    // Rankings
    rankings: {
        bestGenerals: <T>() => get<T>('/api/rankings/best-generals'),
        emperor: <T>() => get<T>('/api/rankings/emperor'),
        emperorDetail: <T>(id: number) => get<T>(`/api/rankings/emperor/${id}`),
        allGenerals: <T>() => get<T>('/api/rankings/generals'),
        kingdoms: <T>() => get<T>('/api/rankings/kingdoms'),
        npcs: <T>() => get<T>('/api/rankings/npcs'),
        hallOfFame: <T>() => get<T>('/api/rankings/hall-of-fame'),
        traffic: <T>() => get<T>('/api/rankings/traffic'),
    },

    // P6 pages
    auctions: <T>() => get<T>('/api/auctions'),
    betting: <T>() => get<T>('/api/bettings'),
    // Mailbox — parameterized by mailbox id (spec §7). game-api: GET /api/mailbox/{mailbox}.
    // No-arg overload (legacy default) kept for callers that still hit the bare route.
    mailbox: <T>(mailbox?: number) =>
        get<T>(mailbox == null ? '/api/mailbox' : `/api/mailbox/${mailbox}`),
    mailboxUnread: <T>(mailbox: number) => get<T>(`/api/mailbox/${mailbox}/unread`),
    message: <T>(id: number) => get<T>(`/api/messages/${id}`),
    // Message accept/decline (game-api takes ?generalId= — pass the caller's own id).
    messageAccept: <T>(id: number, generalId: number) =>
        post<T>(`/api/messages/${id}/accept?generalId=${generalId}`, null),
    messageDecline: <T>(id: number, generalId: number) =>
        post<T>(`/api/messages/${id}/decline?generalId=${generalId}`, null),
    diplomacy: <T>() => get<T>('/api/diplomacy'),

    // Commands.
    //  - game-api CommandController STILL requires ?generalId= (a `@RequestParam`, not yet a verified
    //    `@AuthenticationPrincipal`) and accepts an optional ?turnIdx= (reservable slot, default 0).
    //  - We pass the caller's own generalId (from front-info.general.generalId) + turnIdx as query params
    //    and the collected args as the JSON body. SECURITY FOLLOW-UP (backend, do NOT fix here):
    //    CommandController should validate the passed generalId against the authenticated principal.
    //    generalId is OPTIONAL here only so the pre-existing W1–W4 sub-pages (auction/betting/…) that
    //    call api.command(code, args) keep compiling; the F2 main-screen modal ALWAYS passes it.
    command: <T>(code: string, args: unknown, generalId?: number, turnIdx = 0) =>
        post<T>(
            generalId == null
                ? `/api/command/${code}`
                : `/api/command/${code}?generalId=${generalId}&turnIdx=${turnIdx}`,
            args,
        ),
    availableCommands: <T>(generalId?: number) =>
        get<T>(generalId == null ? '/api/commands/available' : `/api/commands/available?generalId=${generalId}`),

    // Simulator
    simulateBattle: <T>(body: unknown) => post<T>('/api/simulate-battle', body),
};
