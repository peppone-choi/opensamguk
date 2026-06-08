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
    WorldMapResponse,
    PublicGeneral,
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

// ── 전황 (World-Log) read 계약 ────────────────────────────────────────────────
// game-api `GET /api/world-log` (WorldLogController) → {entries:[{id,year,month,text}]}.
// 월드 전체 글로벌 이력(log_entry SYSTEM 스코프)을 최신순 30건 반환. `text`는 패러티 로그
// 원문(devsam 색/태그 마크업 포함) 그대로 — 표시 렌더는 프론트(history와 동일 v-html 패턴).
// (W4 read surface 전용이라 도메인 types 모듈을 건드리지 않고 여기 인라인 정의·export.)
export interface WorldLogEntry {
    id: number;
    year: number;
    month: number;
    text: string;
}

export interface WorldLogResponse {
    entries: WorldLogEntry[];
}

// ── 어드민 read 계약 (B3c/B4c — _admin5/_admin7/_admin8) ─────────────────────────
// game-api `GET /api/admin/*` (AdminReadController) — 전부 READ-only(JPA read). 0.9.0 단일 ADMIN
// 롤 게이트: 비로그인 401 / ADMIN 아님 403. (W4 read surface 전용이라 도메인 types 모듈을
// 건드리지 않고 BE DTO(AdminReadDto.kt) shape를 그대로 여기 인라인 정의·export. legacy 정렬키/
// 라벨/state 문자열은 BE에서 verbatim으로 내려온다 — FE는 가공 없이 렌더.)

/** `_admin5.php:57-83` 정렬 select 옵션(value+label, verbatim). */
export interface AdminNationStatsSortOption {
    value: number;
    label: string;
}

/** `_admin5.php:135-260` 국가별 통계 1행. 자릿수 가공은 BE에서 완료(ROUND/AVG/SUM). */
export interface AdminNationStatsRow {
    nationId: number;
    name: string;
    color: string;
    power: number;
    genCnt: number;
    cityCnt: number;
    tech: number;
    strategicCmdLimit: number;
    gold: number;
    rice: number;
    avgGold: number;
    avgRice: number;
    avgLeadership: number;
    avgStrength: number;
    avgIntel: number;
    avgExpLevel: number;
    dex1: number;
    dex2: number;
    dex3: number;
    dex4: number;
    dex5: number;
    sumCrew: number;
    sumLeadership: number;
    pop: number;
    popMax: number;
    popRate: number;
    agri: number;
    comm: number;
    secu: number;
    wall: number;
    def: number;
}

/**
 * B3a 응답 봉투. historyStats/sabotageLog는 legacy `_admin5`에 있으나 opensamguk 스키마 원천
 * 부재로 BLOCKED(BE가 빈 리스트 + *Blocked=true로 표기 — 값 날조 금지). FE는 blocked일 때
 * 해당 섹션을 "원천 부재" 안내로 대체한다.
 */
export interface AdminNationStatsResponse {
    type: number;
    type2: number;
    sortOptions: AdminNationStatsSortOption[];
    sortOptions2: AdminNationStatsSortOption[];
    rows: AdminNationStatsRow[];
    historyStats: unknown[];
    historyStatsBlocked: boolean;
    sabotageLog: string[];
    sabotageLogBlocked: boolean;
}

/** `_admin7.php:15-31` queryMap 정렬 옵션(verbatim 4종). */
export interface AdminGeneralSortOption {
    queryType: string;
    label: string;
}

/** `_admin7.php:113-114` 대상장수 select 1행 — `name (turnTimeHm)`. */
export interface AdminGeneralSelectOption {
    no: number;
    name: string;
    turnTimeHm: string;
}

/** `_admin7.php:133-168` 장수 상세 + 4개 로그 패널(각 newest-first, text=패러티 로그 원문). */
export interface AdminGeneralDetail {
    no: number;
    name: string;
    nationId: number;
    npc: number;
    leadership: number;
    strength: number;
    intel: number;
    officerLevel: number;
    turnTime: string | null;
    actionLog: string[];
    battleDetailLog: string[];
    historyLog: string[];
    battleResultLog: string[];
}

/** B4a 응답 봉투. */
export interface AdminGeneralLogResponse {
    queryType: string;
    sortOptions: AdminGeneralSortOption[];
    generalList: AdminGeneralSelectOption[];
    gen: number;
    detail: AdminGeneralDetail | null;
}

/** `_admin8.php:78-111` 외교 관계 1행(me<you, state!=2, state desc). stateText는 verbatim. */
export interface AdminDiplomacyRow {
    me: number;
    meName: string;
    meColor: string;
    you: number;
    youName: string;
    youColor: string;
    state: number;
    stateText: string;
    term: number;
}

/** B4b 응답 봉투 — 전 국가간 외교 행 리스트. */
export interface AdminDiplomacyAllResponse {
    relations: AdminDiplomacyRow[];
}

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
    // In-game world map (W9) — fog 포함(spyList/shownByGeneralList/myCity/myNation). 좌표는 없으므로
    // MapPreview와 id로 머지해 렌더한다. neutralView/showMe 인자(기본 showMe=1로 내 도시 노출).
    worldMap: (neutralView = 0, showMe = 1) =>
        get<WorldMapResponse>(`/api/map?neutralView=${neutralView}&showMe=${showMe}`),

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
        kingdomRoster: <T>() => get<T>('/api/rankings/kingdom-roster'),
        npcs: <T>() => get<T>('/api/rankings/npcs'),
        hallOfFame: <T>() => get<T>('/api/rankings/hall-of-fame'),
        traffic: <T>() => get<T>('/api/rankings/traffic'),
    },

    // P6 pages
    // 거래장(자원 경매) D1 — game-api `GET /api/auctions` → AuctionResourceListResponse
    //   {result, buyRice[], sellRice[], recentLogs[], generalID}. 호출부는 envelope 통째로 받는다
    //   (legacy SammoAPI.Auction.GetActiveResourceAuctionList와 동형).
    auctions: <T>() => get<T>('/api/auctions'),
    // 유니크 경매 D2 — `GET /api/auctions/unique` → UniqueItemAuctionListResponse {result, list[], obfuscatedName}.
    auctionsUnique: <T>() => get<T>('/api/auctions/unique'),
    // 유니크 경매 상세 D3 — `GET /api/auctions/{id}/unique-detail`
    //   → {result, auction, bidList[], obfuscatedName, remainPoint}. 부재 시 404 + 한글 메시지.
    auctionUniqueDetail: <T>(id: number) => get<T>(`/api/auctions/${id}/unique-detail`),
    // 베팅 목록 D4 — `GET /api/bettings` → BettingListResponse {result, bettingList(Map<id,item>), year, month}.
    betting: <T>() => get<T>('/api/bettings'),
    // 베팅 상세 D5 — `GET /api/bettings/{id}/detail`(per-OWNER, 인증 필요)
    //   → {result, bettingInfo(raw), bettingDetail[], myBetting[], remainPoint, year, month}.
    bettingDetail: <T>(id: number) => get<T>(`/api/bettings/${id}/detail`),
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

    // B1 Join — 장수생성(재야 등록). 202=성공, 200 BLOCKED=deny.
    join: (body: { name: string; leadership: number; strength: number; intel: number; character: string; pic?: boolean }) =>
        post<{ status: string; requestId?: string; reason?: string }>('/api/join', body),

    // ── F4 action-page READ endpoints (read-only; all via the /api/game proxy) ──
    // game-api = read-only JPA on existing tables; one-daemon-write rule.
    // Endpoints with no backing rows in the fresh scenario_1010 seed (board / vote /
    // troop / history / tournament) return an EMPTY/zeroed shape GRACEFULLY (200),
    // mirroring F3's emperor/traffic empty defaults — never a 500, never fabricated.
    // These are PUBLIC reads (game-api permits all); identity-scoped endpoints
    // (board secret-room, npc-policy, chief-reserved, inherit, nation finance)
    // resolve the caller from the verified @AuthenticationPrincipal in-controller.

    // 전체 장수 (page 14 / 세력 장수 P0) — public, permission=0 fields.
    // 백엔드 GeneralsController는 PublicGeneral의 **bare 배열**을 반환한다(래퍼 아님).
    generalsList: () => get<PublicGeneral[]>('/api/generals'),
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
    // 전황 (World-Log) — log_entry SYSTEM 스코프 글로벌 이력 최신순 30건. 신선 시드면 빈 목록.
    worldLog: () => get<WorldLogResponse>('/api/world-log'),

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

    // ── C1-α write submit 래퍼 (wire 코드 기존; 백엔드 신규 로직/핸들러/wire 없음) ──────────────────────
    // 모두 단일 mutation seam(POST /api/command/{code} → CommandController → CommandReserveService)을
    // 경유한다. wire 코드는 CommandWireMapper에 이미 등록됨:
    //   diploSendLetter:279 · diploRollbackLetter:287 · diploDestroyLetter:291 · boardArticle:208 · boardComment:214.
    // 얇은 래퍼로 호출부(페이지)가 코드 문자열을 직접 알 필요 없게 한다(generalId는 명령 인테이크 필수).
    // args 모양은 legacy ajax 폼 필드(=devsam-core hwe/j_*.php의 Util::getPost 키)와 동일하게 맞춘다.
    commands: {
        // 외교 서신 보내기 — legacy j_diplomacy_send_letter.php(brief/detail/destNation/prevNo).
        diploSendLetter: <T>(
            args: { destNation: number; brief: string; detail: string; prevNo: number | null },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/diploSendLetter?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 외교 서신 회수(제안 단계 송신측) — legacy j_diplomacy_rollback_letter.php(letterNo).
        diploRollbackLetter: <T>(args: { letterNo: number }, generalId: number, turnIdx = 0) =>
            post<T>(`/api/command/diploRollbackLetter?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 외교 서신 파기(승인 단계, 상호 동의 2단계) — legacy j_diplomacy_destroy_letter.php(letterNo).
        diploDestroyLetter: <T>(args: { letterNo: number }, generalId: number, turnIdx = 0) =>
            post<T>(`/api/command/diploDestroyLetter?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 게시판 글쓰기(회의실/기밀실) — legacy j_board_article_add.php(isSecret/title/text).
        boardArticle: <T>(
            args: { isSecret: boolean; title: string; text: string },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/boardArticle?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 게시판 댓글 — legacy j_board_comment_add.php(articleNo/text, maxlength 250).
        boardComment: <T>(args: { articleNo: number; text: string }, generalId: number, turnIdx = 0) =>
            post<T>(`/api/command/boardComment?generalId=${generalId}&turnIdx=${turnIdx}`, args),

        // ── 거래장/경매 (C1 AuctionResource/AuctionUniqueItem) ───────────────────────────────────
        // CommandWireMapper.intakeCodes에 모두 기존 등록(auctionBid:135 / auctionOpenBuyRice:259 /
        // auctionOpenSellRice:266 / auctionOpenUnique:273). args 키는 mapper가 파싱하는 키(=legacy ajax
        // 폼 필드)와 byte-동일하게 맞춘다.
        // 입찰 — legacy SammoAPI.Auction.Bid{BuyRice,SellRice,Unique}Auction({auctionID, amount}).
        //   mapper는 `auctionId`/`amount`/(선택)`tryExtendCloseDate`를 읽는다(자원/유니크 동일 typed 명령).
        auctionBid: <T>(
            args: { auctionId: number; amount: number; tryExtendCloseDate?: boolean },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/auctionBid?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 쌀 매수 경매 등록 — legacy SammoAPI.Auction.OpenBuyRiceAuction.
        //   {amount, startBidAmount, finishBidAmount, closeTurnCnt}.
        auctionOpenBuyRice: <T>(
            args: { amount: number; startBidAmount: number; finishBidAmount: number; closeTurnCnt: number },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/auctionOpenBuyRice?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 금(쌀 매도) 경매 등록 — legacy SammoAPI.Auction.OpenSellRiceAuction(동일 폼 필드).
        auctionOpenSellRice: <T>(
            args: { amount: number; startBidAmount: number; finishBidAmount: number; closeTurnCnt: number },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/auctionOpenSellRice?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        // 유니크 아이템 경매 등록 — legacy SammoAPI.Auction.OpenUniqueAuction. mapper는 `itemId`/`amount`.
        auctionOpenUnique: <T>(
            args: { itemId: string; amount: number },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/auctionOpenUnique?generalId=${generalId}&turnIdx=${turnIdx}`, args),

        // ── 베팅 (C1 BettingDetail) ────────────────────────────────────────────────────────────
        // CommandWireMapper.intakeCodes `placeBet`:128. legacy SammoAPI.Betting.Bet({bettingID,
        //   bettingType, amount}). mapper는 `bettingId`(camel)/`bettingType`(number[])/`amount`를 읽는다.
        placeBet: <T>(
            args: { bettingId: number; bettingType: number[]; amount: number },
            generalId: number,
            turnIdx = 0,
        ) => post<T>(`/api/command/placeBet?generalId=${generalId}&turnIdx=${turnIdx}`, args),
    },

    // Simulator
    simulateBattle: <T>(body: unknown) => post<T>('/api/simulate-battle', body),

    // ── 어드민 read (B3c/B4c — 게임서버 내, web/game) ────────────────────────────────
    // game-api AdminReadController — 전부 READ-only. 프록시가 httpOnly sam_access 쿠키를
    // Bearer로 붙여 보내므로 별도 헤더 주입 불필요. 비ADMIN은 game-api가 403, 비로그인은 401.
    admin: {
        // 일제정보(_admin5) — 국가별 통계 + 정렬(type 0~17, type2 0~6).
        nationStats: (type = 0, type2 = 0) =>
            get<AdminNationStatsResponse>(`/api/admin/nation-stats?type=${type}&type2=${type2}`),
        // 로그정보(_admin7) — 장수 상세 + 4개 로그 패널 + 정렬(queryMap 4종).
        generalLog: (gen = 0, queryType?: string) =>
            get<AdminGeneralLogResponse>(
                queryType == null
                    ? `/api/admin/general-log?gen=${gen}`
                    : `/api/admin/general-log?gen=${gen}&query_type=${queryType}`,
            ),
        // 외교정보(_admin8) — 전 국가간 외교 전체(마스킹 없음).
        diplomacyAll: () => get<AdminDiplomacyAllResponse>('/api/admin/diplomacy-all'),
    },
};
