// All game-api calls go through the same-origin server-side proxy at /api/game/[...path].
// The proxy reads the httpOnly sam_access cookie and attaches Authorization: Bearer to game-api(:8081),
// so the JWT never reaches client JS. Paths below keep their /api/... prefix (game-api's own routes);
// the proxy strips the /api/game segment and forwards /api/... verbatim.
const BASE = '/api/game';

import { countyDetailPath } from './county-detail';
import { adminPeoplePath, countiesPath, peoplePath } from './directory-paths';
import { personDetailPath } from './person-detail';
import type {
    FrontInfoResponse,
    GameConstResponse,
    MapPreviewResponse,
    PublicGeneral,
    DiplomacyConflictResponse,
    BoardResponse,
    HistoryResponse,
    IntakeOutcome,
    IntakeQueued,
    IntakeDenied,
    ReservedCommandsResponse,
} from './types';

export type GeneralLogType = 'generalAction' | 'battleDetail' | 'battleResult' | 'generalHistory';

export interface GeneralLogResponse {
    result: boolean;
    reqType?: GeneralLogType;
    generalID?: number;
    log?: Record<string, string>;
    reason?: string;
}

export interface CommandResultPending {
    status: 'PENDING';
    requestId: string;
    phase?: 'reservationAccepted';
}

export interface CommandResultResolved {
    status: 'RESOLVED';
    requestId: string;
    ok: boolean;
    type: string;
    reason?: string;
    result: Record<string, unknown>;
}

export type CommandResultResponse = CommandResultPending | CommandResultResolved;

export interface DiplomaticMessageRequestAccepted {
    readonly status: 'AVAILABLE';
    readonly requestId: string;
}

// ── 게임 관리 서버 상태 읽기 — `GET /api/admin/game-settings`(AdminReadController) ──────────────
// 게임 관리(P-A03) 서버 상태 탭이 지금 상태 · 연월 · 마지막 턴을 이 응답에서 읽는다. 게임 설정 바꾸기는 운영 콘솔(P-G09) 몫이라
// 여기엔 읽기 모양만 둔다. 비로그인 401 / ADMIN 아님 403.

export interface AdminBlockedWrite {
    label: string;
    reason: string;
    code?: string | null;
    enabled?: boolean;
}

export interface AdminGameSettingsResponse {
    msg: string;
    logWritable: boolean;
    scenarioCode: string | null;
    scenarioText?: string | null;
    mapCode?: string | null;
    year: number | null;
    month: number | null;
    turnPhase?: number | null;
    turnPhaseText?: string | null;
    status?: string | null;
    starttime: string | null;
    startyear: number | null;
    maxgeneral: number | null;
    maxnation: number | null;
    turntime: string | null;
    turnterm: number | null;
    turnOptions: number[];
    blockedWrites: AdminBlockedWrite[];
}

// sam_access(15분)가 만료되면 game-api가 401을 준다. sam_refresh는 path=/api/auth로 좁혀 심어져
// /api/game/** 프록시엔 절대 안 실리므로(web/game/lib/cookies.ts:7, 구조적 계약은
// __tests__/cookie-refresh-path-scope.test.ts) 서버 프록시는 재시도를 할 수 없다 — sam_refresh가
// 실제로 도달하는 유일한 경로인 /api/auth/me를 여기서 호출해 재발급을 받고, 원 요청을 딱 1회만
// 재시도한다. body는 이미 JSON.stringify된 문자열이라 두 번째 fetch에 그대로 재사용해도 안전하다.
export async function fetchGame(path: string, init?: RequestInit): Promise<Response> {
    const res = await fetch(`${BASE}${path}`, init);
    if (res.status !== 401) return res;
    const refreshed = await fetch('/api/auth/me', { cache: 'no-store' });
    if (!refreshed.ok) return res;
    return fetch(`${BASE}${path}`, init);
}

/** 게임 API 읽기 실패 — 상태와 서버 코드(`{error:{code}}`, 없으면 null)를 싣는다. 문장은 예전 그대로(`403: Forbidden`). */
export class GameHttpError extends Error {
    constructor(readonly status: number, readonly code: string | null, message: string) {
        super(message);
        this.name = 'GameHttpError';
    }
}

async function errorCodeOf(res: Response): Promise<string | null> {
    try {
        const body = (await res.json()) as { error?: { code?: unknown } } | null;
        return typeof body?.error?.code === 'string' ? body.error.code : null;
    } catch {
        return null;
    }
}

async function get<T>(path: string, signal?: AbortSignal): Promise<T> {
    const res = await fetchGame(path, { cache: 'no-store', signal });
    if (!res.ok) throw new GameHttpError(res.status, await errorCodeOf(res), `${res.status}: ${res.statusText}`);
    return res.json() as Promise<T>;
}

async function post<T>(path: string, body: unknown): Promise<T> {
    const res = await fetchGame(path, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        // 4xx/5xx 본문에 BE가 보낸 실제 사유(reason/error/message)가 있으면 그대로 싣는다 —
        // 날조 없음(서버 문자열만). 본문 없음/비JSON이면 상태줄로 던진다.
        let detail = '';
        try {
            const parsed: unknown = await res.json();
            if (parsed && typeof parsed === 'object') {
                const p = parsed as Record<string, unknown>;
                const msg = p.reason ?? p.error ?? p.message;
                if (typeof msg === 'string' && msg.length > 0) detail = msg;
            }
        } catch {
            // 본문 파싱 실패 — 상태줄 폴백.
        }
        throw new Error(detail || `${res.status}: ${res.statusText}`);
    }
    return res.json() as Promise<T>;
}

async function patch<T>(path: string, body: unknown): Promise<T> {
    const res = await fetchGame(path, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
    });
    if (!res.ok) {
        let detail = '';
        try {
            const parsed: unknown = await res.json();
            if (parsed && typeof parsed === 'object') {
                const p = parsed as Record<string, unknown>;
                const msg = p.reason ?? p.error ?? p.message;
                if (typeof msg === 'string' && msg.length > 0) detail = msg;
            }
        } catch {
            detail = '';
        }
        throw new Error(detail || `${res.status}: ${res.statusText}`);
    }
    return res.json() as Promise<T>;
}

// ── 인테이크 결과 타입 가드 (W0-1 — P0-04/P0-06 근원 처리) ─────────────────────
// 200 BLOCKED/UNKNOWN도 res.ok라 post()가 정상 resolve된다 — 호출부는 반드시 이 가드로
// 분기해야 한다. queued(202)는 "접수/예약"이지 성공 확정이 아니다(엔진 비동기 deny 가능,
// 결과 회신 채널은 W0-4). denied.reason은 PHP 동결 회귀 deny 문자열 — 그대로 노출할 것.

/** 202 큐잉(intake 수락) 여부. 성공 확정 아님 — "접수/예약" 시멘틱으로만 표시. */
export function isIntakeQueued(o: IntakeOutcome): o is IntakeQueued {
    return o.status === 'AVAILABLE';
}

/** precheck/큐 조작 deny(200 BLOCKED·UNKNOWN) 여부. reason을 그대로 노출(날조 금지). */
export function isIntakeDenied(o: IntakeOutcome): o is IntakeDenied {
    return o.status === 'BLOCKED' || o.status === 'UNKNOWN';
}

export const api = {
    get,
    post,
    patch,

    // Identity envelope + server-driven menu/const (F2 Wave 1)
    dispatchOptions: (generalId: number, targetGeneralId?: number) =>
        get<import('./types').DispatchOptionsResponse>(`/api/commands/dispatch-options?generalId=${generalId}${targetGeneralId == null ? '' : `&targetGeneralId=${targetGeneralId}`}`),
    dispatchPending: (generalId: number) => get<import('./types').DispatchPendingResponse>(`/api/commands/dispatches?generalId=${generalId}`),
    courtDispatch: (generalId: number, args: {targetGeneralId: number; countyId: number}) =>
        post<IntakeOutcome>(`/api/commands/court/dispatch?generalId=${generalId}`, args),
    courtDispatchReply: (generalId: number, args: {dispatchId: string; accept: boolean}) =>
        post<IntakeOutcome>(`/api/commands/court/dispatchReply?generalId=${generalId}`, args),
    deployOptions: (generalId: number) => get<import('./types').DeployOptions>(`/api/deploy/options?generalId=${generalId}`),
    travelOptions: (inputId: import('./types').TravelActionId, generalId: number) => {
        const name = inputId === 'action.move' ? 'move' : inputId === 'action.forcedMarch' ? 'forced-march' : 'return';
        return get<import('./types').TravelOptions>(`/api/commands/${name}-options?generalId=${generalId}`);
    },
    fieldOptions: (inputId: import('./types').FieldActionId, generalId: number) => {
        const names: Record<import('./types').FieldActionId, string> = {
            'action.farm': 'farm', 'action.commerce': 'commerce', 'action.fortify': 'fortify',
            'action.repairWall': 'repair-wall', 'action.security': 'security', 'action.settle': 'settle',
            'action.selectResidents': 'select-residents', 'action.tour': 'tour',
        };
        return get<import('./types').FieldOptions>(`/api/commands/${names[inputId]}-options?generalId=${generalId}`);
    },
    militaryOptions: (inputId: import('./types').MilitaryActionId, generalId: number) => {
        const names: Record<import('./types').MilitaryActionId, string> = {
            'action.conscript': 'conscript', 'action.raiseVolunteers': 'raise-volunteers',
            'action.train': 'train', 'action.boostMorale': 'boost-morale',
            'action.muster': 'muster', 'action.demobilize': 'demobilize',
        };
        return get<import('./types').MilitaryOptions>(`/api/commands/${names[inputId]}-options?generalId=${generalId}`);
    },
    personalOptions: (inputId: import('./types').PersonalActionId, generalId: number) => {
        const names: Record<import('./types').PersonalActionId, string> = {
            'action.travel': 'travel', 'action.selfTrain': 'self-train', 'action.recuperate': 'recuperate',
            'action.retire': 'retire',
        };
        return get<import('./types').PersonalOptions>(`/api/commands/${names[inputId]}-options?generalId=${generalId}`);
    },
    peopleOptions: (inputId: import('./types').PeopleActionId, generalId: number) => {
        const names: Record<import('./types').PeopleActionId, string> = {
            'action.search': 'search', 'action.employ': 'employ',
            'action.persuadeCaptive': 'persuade-captive',
        };
        return get<import('./types').PeopleOptions>(`/api/commands/${names[inputId]}-options?generalId=${generalId}`);
    },
    politicalOptions: (generalId: number) =>
        get<import('./types').PoliticalOption[]>(`/api/commands/political-options?generalId=${generalId}`),
    politicalConsentOptions: (generalId: number) =>
        get<import('./types').PoliticalConsentOption[]>(`/api/commands/political-consent-options?generalId=${generalId}`),
    courtPoliticalConsent: (generalId: number, args: {issuerGeneralId:number;inputId:'action.abdicate'|'action.oath';accepted:boolean}) =>
        post<IntakeOutcome>(`/api/commands/court/politicalConsent?generalId=${generalId}`, args),
    transferOptions: (inputId: import('./types').TransferActionId, generalId: number) =>
        get<import('./types').TransferOptions>(`/api/commands/${inputId === 'action.gift' ? 'gift' : 'donate'}-options?generalId=${generalId}`),
    legacyDirectOptions: (inputId: import('./types').DirectActionId, generalId: number) =>
        get<import('./types').DirectActionOptions>(`/api/commands/legacy-direct-options?generalId=${generalId}&inputId=${encodeURIComponent(inputId)}`),
    legacyCourtOptions: (inputId: import('./types').CourtActionId, generalId: number) =>
        get<import('./types').CourtActionOptions>(`/api/commands/legacy-court-options?generalId=${generalId}&inputId=${encodeURIComponent(inputId)}`),
    courtLegacy: (inputId: import('./types').CourtActionId, generalId: number, args: Record<string,string|number>) =>
        post<IntakeOutcome>(`/api/commands/court/${inputId.slice(6)}?generalId=${generalId}`, args),
    // 휘하 조회 — 모두 `?generalId=` 로 본인 장수를 받는다. 휘하 규칙이 아닌 월드는 status 로 알린다.
    stratagemHand: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').StratagemHand>(`/api/commands/stratagem-hand?generalId=${generalId}`, signal),
    campaignYuedan: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Yuedan>(`/api/yuedan?generalId=${generalId}`, signal),
    warehouses: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Warehouses>(`/api/warehouses?generalId=${generalId}`, signal),
    /** 현 상세(계약판 K4-04, C10 #1351). 404(행정 縣이 아님)면 화면은 그 칸들을 「서버 대기」로, 그 밖의 실패는 「일부를 불러오지 못했습니다」로 둔다. */
    countyDetail: (generalId: number, cityId: number, signal?: AbortSignal) =>
        get<import('./county-detail').CountyDetailRead>(countyDetailPath(generalId, cityId), signal),
    campaignCounty: (generalId: number, cityId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').County>(`/api/county/${cityId}?generalId=${generalId}`, signal),
    campaignRetinue: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Retinue>(`/api/retinue?generalId=${generalId}`, signal),
    campaignSieges: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Sieges>(`/api/sieges?generalId=${generalId}`, signal),
    roadForts: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').RoadForts>(`/api/road-forts?generalId=${generalId}`, signal),
    courtReward: (generalId: number, args: {retainerId: number; money: number}) =>
        post<IntakeOutcome>(`/api/commands/court/reward?generalId=${generalId}`, args),
    campaignLastTurns: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').LastTurns>(`/api/last-turns?generalId=${generalId}&limit=12`, signal),
    campaignVisibility: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Visibility>(`/api/visibility?generalId=${generalId}`, signal),
    campaignCorps: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').CorpsList>(`/api/corps?generalId=${generalId}`, signal),
    campaignScoutOptions: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').ScoutOptions>(`/api/scout-options?generalId=${generalId}`, signal),
    campaignPosts: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Posts>(`/api/posts?generalId=${generalId}`, signal),
    campaignPolicies: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Policies>(`/api/policies?generalId=${generalId}`, signal),
    campaignWorks: (generalId: number, signal?: AbortSignal) =>
        get<import('./campaign-reads').Works>(`/api/works?generalId=${generalId}`, signal),
    /** 인물 일람 — 본인 계정으로 본다(`generalId` 없음). 시야 · 권한 밖 칸은 null. */
    people: (query: import('./directory-reads').PeopleQuery, cursor: string | null, signal?: AbortSignal) =>
        get<import('./directory-reads').PeoplePage>(peoplePath(query, cursor), signal),
    /** 인물 상세(계약판 K4-13, C10). 서버 경로가 없으면 404 — 화면은 지금 읽기(front-info · 부)로만 그린다(D124). */
    personDetail: (generalId: number, targetGeneralId: number, signal?: AbortSignal) =>
        get<import('./person-detail').PersonDetailRead>(personDetailPath(generalId, targetGeneralId), signal),
    nationSummary: (generalId: number, signal?: AbortSignal) =>
        get<import('./directory-reads').NationSummary>(`/api/nation/summary?generalId=${generalId}`, signal),
    counties: (generalId: number, scope: import('./directory-reads').CountyScope, commanderyId?: string | null, signal?: AbortSignal) =>
        get<import('./directory-reads').CountyDirectory>(countiesPath(generalId, scope, commanderyId), signal),
    /** 배치·방침·공사 — 12순 슬롯을 쓰지 않는 지속 입력. 접수는 202, 거절은 200 BLOCKED. */
    campaignDomestic: (generalId: number, kind: 'placement' | 'policy' | 'work' | 'reduce', body: unknown) =>
        post<IntakeOutcome>(`/api/commands/${kind === 'reduce' ? 'work' : kind}/${{ placement: 'assign', policy: 'set', work: 'start', reduce: 'reduce' }[kind]}?generalId=${generalId}`, body),
    enlistmentOptions: (generalId: number) => get<import('./types').EnlistmentOptionsResponse>(`/api/commands/enlistment-options?generalId=${generalId}`),
    frontInfo: (signal?: AbortSignal) => get<FrontInfoResponse>('/api/front-info', signal),
    gameConst: () => get<GameConstResponse>('/api/const'),

    // 지도 미리보기(작전실 · 기록 지도와 이름 풀이) — 게이트웨이 로비 MapPreview 와 같은 끝점.
    mapPreview: (signal?: AbortSignal) => get<MapPreviewResponse>('/api/map/preview', signal),
    // 황제 소재지(docs/design/imperial-presence-api.md). 409 STATE_UNAVAILABLE 도 본문이 있어 get() 대신 응답을 그대로 넘긴다 — 해석은 lib/imperial.ts.
    imperialPresenceResponse: (signal?: AbortSignal) => fetchGame('/api/imperial/presence', { cache: 'no-store', signal }),

    // My pages
    myPage: <T>() => get<T>('/api/my-page'),
    // Phase 4X-B 작전(spec v4.1 §6) — 국가 내부 정보.
    operations: <T>() => get<T>('/api/operations'),
    // 도시 목록(`[city, nation, name, level]` 4-튜플, CityListController) — 작전 목표 select 원천.
    cityList: <T>() => get<T>('/api/cities'),
    // Phase 4X-C 리플레이(spec v4.1 §6) — 공격국·수비국·본인만.
    battleReplay: <T>(id: number) => get<T>(`/api/battles/replays/${id}`),
    myGenerals: <T>() => get<T>('/api/my-generals'),
    myCities: <T>() => get<T>('/api/my-cities'),
    myNationDetail: <T>() => get<T>('/api/my-nation-detail'),
    city: <T>(id: number) => get<T>(`/api/city/${id}`),
    generals: <T>() => get<T>('/api/generals'),
    generalLog: (generalId: number, reqType: GeneralLogType, reqTo?: number) =>
        get<GeneralLogResponse>(
            reqTo == null
                ? `/api/general-log?generalID=${generalId}&reqType=${reqType}`
                : `/api/general-log?generalID=${generalId}&reqType=${reqType}&reqTo=${reqTo}`,
        ),

    // Rankings
    rankings: {
        bestGenerals: <T>() => get<T>('/api/rankings/best-generals'),
        kingdomRoster: <T>() => get<T>('/api/rankings/kingdom-roster'),
    },

    // P6 pages
    // Mailbox — parameterized by mailbox id (spec §7). game-api: GET /api/mailbox/{mailbox}.
    // No-arg overload (legacy default) kept for callers that still hit the bare route.
    mailbox: <T>(mailbox?: number) =>
        get<T>(mailbox == null ? '/api/mailbox' : `/api/mailbox/${mailbox}`),
    mailboxRecent: <T>(sequence = 0) => get<T>(`/api/mailbox/recent?sequence=${sequence}`),
    mailboxOld: <T>(to: number, type: string) => get<T>(`/api/mailbox/old?to=${to}&type=${encodeURIComponent(type)}`),
    contacts: <T>() => get<T>('/api/contacts'),
    message: <T>(id: number) => get<T>(`/api/messages/${id}`),
    // Message accept/decline (game-api takes ?generalId= — pass the caller's own id).
    messageAccept: (id: number, generalId: number) =>
        post<DiplomaticMessageRequestAccepted>(`/api/messages/${id}/accept?generalId=${generalId}`, null),
    messageDecline: (id: number, generalId: number) =>
        post<DiplomaticMessageRequestAccepted>(`/api/messages/${id}/decline?generalId=${generalId}`, null),
    diplomacy: <T>() => get<T>('/api/diplomacy'),

    // B1 Join — 장수생성(재야 등록). 202=성공, 200 BLOCKED=deny.
    join: (body: {
        name: string;
        leadership: number;
        strength: number;
        intel: number;
        politics: number;
        charm: number;
        character: string;
        pic?: boolean;
    }) =>
        post<{ status: string; requestId?: string; reason?: string }>('/api/join', body),
    commandResult: (requestId: string) => get<CommandResultResponse>(`/api/command/result/${requestId}`),

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
    // 중원정보 (page 2) — global matrix + per-city 분쟁% conflict feed.
    diplomacyConflict: () => get<DiplomacyConflictResponse>('/api/diplomacy/conflict'),
    // 회의실 / 기밀실 (page 4) — articles+comments, permission-gated by ?secret=.
    board: (secret = false) => get<BoardResponse>(`/api/board?secret=${secret}`),
    // 연감 (page 16) — ng_history range + per-month records; ?yearMonth selects month.
    history: (yearMonth?: number) =>
        get<HistoryResponse>(yearMonth == null ? '/api/history' : `/api/history?yearMonth=${yearMonth}`),

    // Commands.
    //  - game-api CommandController는 ?generalId=가 **필수**(@RequestParam — 인증 시 principal 본인
    //    소유 검증, 불일치 403). 누락하면 무조건 400 = "구매 요청에 실패했습니다" 류 영구 실패(P0-50).
    //    그래서 W0-1부터 generalId는 시그니처에서도 필수다 — 호출부는 front-info.general.generalId를
    //    넘긴다(컴파일 타임에 누락을 차단).
    //  - resolve 값은 IntakeOutcome(& T) — 200 BLOCKED/UNKNOWN도 resolve되므로 호출부는
    //    isIntakeQueued/isIntakeDenied로 분기한다(202=큐잉이지 성공 확정 아님 — P0-04/06).
    command: <T = unknown>(code: string, args: unknown, generalId: number, turnIdx = 0) =>
        post<IntakeOutcome & T>(`/api/command/${code}?generalId=${generalId}&turnIdx=${turnIdx}`, args),

    // 예약 명령 링 read — `GET /api/reserved-commands` (P0-01). 인증 principal 우선, generalId fallback.
    reservedCommands: (generalId?: number) =>
        get<ReservedCommandsResponse>(
            generalId == null ? '/api/reserved-commands' : `/api/reserved-commands?generalId=${generalId}`,
        ),

    // ── 예약 큐 조작 (W6e bulk/push/repeat × {general, nation}) ───────────────────────────────
    // PHP SammoAPI.Command.* / NationCommand.*(PushCommand·RepeatCommand·ReserveBulkCommand) 대응.
    // 응답 규약: 202 = 큐 갱신(bulk는 briefList 동봉) / 200 BLOCKED = PHP 동결 회귀 deny 문자열.
    // 한계값은 BE가 검증(push ±12, repeat 1..12; 사령부 실효 한계는 maxChiefTurn/2=6 — P0-10).
    commandQueue: {
        /** Push(장수) — 당기기/미루기. amount -12..12, 0 불가 (P0-02). */
        push: (generalId: number, amount: number) =>
            post<IntakeOutcome>(`/api/command/push?generalId=${generalId}`, { amount }),
    },

    // ── C1-α write submit 래퍼 (wire 코드 기존; 백엔드 신규 로직/핸들러/wire 없음) ──────────────────────
    // 모두 단일 mutation seam(POST /api/command/{code} → CommandController → CommandReserveService)을
    // 경유한다. wire 코드는 CommandWireMapper에 이미 등록됨:
    //   diploSendLetter:279 · diploRollbackLetter:287 · diploDestroyLetter:291 · boardArticle:208 · boardComment:214.
    // 얇은 래퍼로 호출부(페이지)가 코드 문자열을 직접 알 필요 없게 한다(generalId는 명령 인테이크 필수).
    // args 모양은 legacy ajax 폼 필드(=devsam-core hwe/j_*.php의 Util::getPost 키)와 동일하게 맞춘다.
    //
    // [W0-1] resolve 값은 IntakeOutcome(& T) — 200 BLOCKED/UNKNOWN도 **정상 resolve**된다.
    // await 후 무조건 성공 토스트(P0-04/06 위조)는 금지: isIntakeDenied(out)면 out.reason을
    // legacy 문자열 그대로 danger 토스트, isIntakeQueued(out)면 "접수" 시멘틱으로만 표시한다.
    commands: {

        // 서신 발송 — legacy SendMessage.php(mailbox, text).
        // CommandWireMapper.intakeCodes `sendMessage`:75.
        // mailbox: 9999=전체, 9000+nationId=국가, generalId=개인. 엔진 핸들러가 라우팅 결정.
        // PHP validateArgs: mailbox(required, integer), text(required, lengthMin 1).
        sendMessage: <T = unknown>(
            args: { mailbox: number; text: string },
            generalId: number,
            turnIdx = 0,
        ) => post<IntakeOutcome & T>(`/api/command/sendMessage?generalId=${generalId}&turnIdx=${turnIdx}`, args),

        // 서신 삭제 — legacy SammoAPI.Message.DeleteMessage({ msgID }).
        // CommandWireMapper.intakeCodes `deleteMessage`:83 → TurnDaemonCommand.DeleteMessage(msgID)
        // (mapper는 `msgID`/`msgId`를 읽는다). 계약 주의: deleteMessage는 인테이크 명령이라 game-api
        // CommandController가 precheck Blocked/Unknown이어도 isForecastReservable→202 reserveAccepted로
        // 재라우팅한다(이 엔드포인트에서 200 BLOCKED는 나오지 않는다). 엔진 MessageHandler.handleDelete의
        // 실제 deny(본인 아님/5분 초과/시스템 외교 등 PHP 동결 회귀 문자열)는 GET /api/command/result/{requestId}
        // (RESOLVED + 톱레벨 ok/reason) 채널로만 온다 → 호출부(mailbox 페이지)는 202 후 api.commandResult를 폴링한다.
        deleteMessage: <T = unknown>(
            args: { msgID: number },
            generalId: number,
            turnIdx = 0,
        ) => post<IntakeOutcome & T>(`/api/command/deleteMessage?generalId=${generalId}&turnIdx=${turnIdx}`, args),
        readLatestMessage: <T = unknown>(
            args: { type: 'private' | 'diplomacy'; msgID: number },
            generalId: number,
        ) => post<IntakeOutcome & T>(`/api/command/readLatestMessage?generalId=${generalId}`, args),
    },

    // ── 게임 관리(P-A03) — 운영자 읽기 · 서버 상태 ───────────────────────────────────────
    // 프록시가 httpOnly sam_access 쿠키를 Bearer 로 붙인다. 비ADMIN 은 403, 비로그인은 401.
    admin: {
        /** 서버 상태 탭의 지금 상태 · 연월 · 마지막 턴(옛 게임 설정 읽기 — 같은 월드를 서버 상태 바꾸기가 쓴다). */
        gameSettings: (signal?: AbortSignal) => get<AdminGameSettingsResponse>('/api/admin/game-settings', signal),
        /** 서버 상태(OPEN/PRE_OPEN/CLOSED) 바꾸기 — 202 는 접수일 뿐이다. 반영은 다시 읽어 확인한다. */
        serverStatus: (status: string) =>
            post<{ result: boolean; status?: string; reason?: string }>('/api/admin/server-status', { status }),
        /** 세력 개요 — 세력마다 세력 요약과 같은 모양(계약판 K5-13 · K4-09). */
        nations: (signal?: AbortSignal) => get<import('./admin-reads').AdminNationDirectory>('/api/admin/nations', signal),
        /** 사람 고르기 한 쪽(100명) — 끝까지 받기는 admin-reads.readAllAdminPeople. */
        people: (cursor: string | null, signal?: AbortSignal) =>
            get<import('./directory-reads').PeoplePage>(adminPeoplePath(cursor), signal),
    },
};

const COMMAND_RESULT_POLL_ATTEMPTS = 20;
const COMMAND_RESULT_POLL_INTERVAL_MS = 300;

/**
 * 결과가 나올 때까지 정본을 되묻는다. [signal]이 끊기면 남은 시도를 버린다 — OPENSAM-45의 push
 * 신호가 먼저 결론을 냈을 때 이미 필요 없어진 요청 19번을 마저 쏘지 않기 위해서다.
 */
export async function pollCommandResultResponse(
    requestId: string,
    signal?: AbortSignal,
): Promise<CommandResultResponse | null> {
    let lastPending: CommandResultPending | null = null;
    for (let attempt = 0; attempt < COMMAND_RESULT_POLL_ATTEMPTS; attempt += 1) {
        if (attempt > 0) {
            await new Promise<void>(resolve => setTimeout(resolve, COMMAND_RESULT_POLL_INTERVAL_MS));
        }
        if (signal?.aborted) return lastPending;
        const result = await api.commandResult(requestId).catch(error => {
            if (error instanceof Error) return null;
            throw error;
        });
        if (result?.status === 'RESOLVED') return result;
        if (result?.status === 'PENDING') lastPending = result;
    }
    return lastPending;
}
