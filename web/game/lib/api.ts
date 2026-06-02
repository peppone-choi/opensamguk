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
    GeneralListResponse,
    TournamentResponse,
    DiplomacyLettersResponse,
    DiplomacyConflictResponse,
    NationFinanceResponse,
    ChiefReservedResponse,
    NpcPolicyResponse,
    InheritPointResponse,
    BoardResponse,
    VoteListResponse,
    VoteDetailResponse,
    TroopListResponse,
    HistoryResponse,
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

    // ── F4 action-page READ endpoints (read-only; all via the /api/game proxy) ──
    // game-api = read-only JPA on existing tables; one-daemon-write rule.
    // Endpoints with no backing rows in the fresh scenario_1010 seed (board / vote /
    // troop / history / tournament) return an EMPTY/zeroed shape GRACEFULLY (200),
    // mirroring F3's emperor/traffic empty defaults — never a 500, never fabricated.
    // These are PUBLIC reads (game-api permits all); identity-scoped endpoints
    // (board secret-room, npc-policy, chief-reserved, inherit, nation finance)
    // resolve the caller from the verified @AuthenticationPrincipal in-controller.

    // 전체 장수 (page 14 / 세력 장수 P0) — public, permission=0 fields.
    generalsList: () => get<GeneralListResponse>('/api/generals'),
    // 토너먼트 (page 12/13/11-bracket) — state/bracket/standings/rankings/msg.
    tournamentView: () => get<TournamentResponse>('/api/tournament'),
    // 외교부 (page 1) — letter list (nations + letters map + myNationID).
    diplomacyLetters: () => get<DiplomacyLettersResponse>('/api/diplomacy/letters'),
    // 중원정보 (page 2) — global matrix + per-city 분쟁% conflict feed.
    diplomacyConflict: () => get<DiplomacyConflictResponse>('/api/diplomacy/conflict'),
    // 내무부 (page 3) — gold/rice/income/outcome/policy/warSettingCnt/msgs/editable.
    nationFinance: (id: number) => get<NationFinanceResponse>(`/api/nation/${id}/finance`),
    // 사령부 (page 7) — 8 chief posts (lv 12/11/10/9/8/7/6/5) + reserved turns.
    chiefReserved: () => get<ChiefReservedResponse>('/api/nation/chief-reserved'),
    // NPC 정책 (page 8) — default+current policy/priorities/lastSetters/env.
    npcPolicy: () => get<NpcPolicyResponse>('/api/nation/npc-policy'),
    // 유산 (page 15) — inherit items/buffs/costs/availability/logs/currentStat.
    inheritPoint: () => get<InheritPointResponse>('/api/inherit-point'),
    // 회의실 / 기밀실 (page 4) — articles+comments, permission-gated by ?secret=.
    board: (secret = false) => get<BoardResponse>(`/api/board?secret=${secret}`),
    // 설문 조사 (page 5) — vote list.
    votes: () => get<VoteListResponse>('/api/votes'),
    // 설문 조사 (page 5) — vote detail + results + myVote + userCnt.
    vote: (id: number) => get<VoteDetailResponse>(`/api/votes/${id}`),
    // 부대 편성 (page 6) — troop list (leader/members/reservedCommandBrief/turnTime).
    troops: () => get<TroopListResponse>('/api/troops'),
    // 연감 (page 16) — ng_history range + per-month records; ?yearMonth selects month.
    history: (yearMonth?: number) =>
        get<HistoryResponse>(yearMonth == null ? '/api/history' : `/api/history?yearMonth=${yearMonth}`),

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
