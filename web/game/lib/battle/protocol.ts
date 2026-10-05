// 실시간 전투 wire 어댑터(P-C04 목록 · P-C03 참가 · 배치) — **초안 — C2 병합 때 맞춤.**
//
// 서버는 아직 이 형태를 보내지 않는다. main 에는 join-ticket · WS 입장(기본 꺼짐)만 있고, 활성 목록 · v2 SNAPSHOT/ACK 는 C2 draft(#1243 등)다.
// 그래서 이 파일은 C2 v2 계약 초안을 그대로 옮긴 형(型)과 해석기만 둔다. C2 가 병합되면 그 PR 이 이 파일을 실제 계약에 맞춘다(CEO 10-05 a).
// 고정 자료(가짜 전투)는 시험에만 쓰고, 제품 화면은 서버가 꺼져 있는 동안 「전투가 열리지 않음」을 유지한다.
//
// 출처(메타 저장소):
// - 계약판 reports/opensamguk/tasks/2026-09-30-api-contract-board.md 행 K6-11 ~ K6-14a(171–175행),
//   「C2 → K6 계약 변경 제안」(5951 · 6203 · 6264행), 「K6 → C2 소비 답」(03:57:55 절)
// - C2 계약 reports/opensamguk/tasks/2026-10-01-c2-ws-contract.md — 「D-BATTLE … v2 개정안」(sourceKey · DEPLOYMENT_MOVE),
//   「K6 검토 19항목에 대한 v2 답」 #1(목록) · #2(units) · #4(deployment) · #9(boardId) · #15(시간) · #16(환경),
//   「C1/C7 인계: v2 숫자」(Long = 10진 문자열) · 거절 사유 표(10개).
// 지어낸 enum 은 없다. 정해지지 않은 값(kind · pacingMode · 단계 대응 밖)은 문자열 그대로 받고, 화면은 아는 값만 이름을 붙인다.

// ---- 공통 ----

/** 부곡 열쇠 — v2 는 실제 참전 부곡마다 이 열쇠로 봉인한다(FormationSlot 여섯 자리 · slot:null 없음). */
export type SourceKey =
    | { readonly kind: 'RETINUE'; readonly sourceId: number }
    | { readonly kind: 'CITY_GARRISON_BUGOK'; readonly cityId: number; readonly sourceId: number };

/** 판 칸 — 64×64, row-major, 각 0–63(K6-14a: 화면 투영은 web 몫, 픽셀은 서버 정본이 아니다). */
export interface Cell {
    readonly row: number;
    readonly col: number;
}

export const BOARD_SIZE = 64;

/** 6명령 · 3집결점(현 엔진 집합, C2 계약 K6-14/15). */
export const BATTLE_ORDERS = ['CHARGE', 'ATTACK', 'FORMATION', 'DEFEND', 'WALL', 'RETREAT'] as const;
export type BattleOrder = (typeof BATTLE_ORDERS)[number];
export const RALLY_POINTS = ['HOME', 'CENTER', 'ENEMY'] as const;
export type RallyPoint = (typeof RALLY_POINTS)[number];

/** 6명령의 화면 이름 — 보드 명령 막대(CMDS 「돌격 · 공격 · 대형 · 수비 · 성벽 · 후퇴」)와 계약 순서가 같다. */
export const ORDER_LABEL: Readonly<Record<BattleOrder, string>> = {
    CHARGE: '돌격', ATTACK: '공격', FORMATION: '대형', DEFEND: '수비', WALL: '성벽', RETREAT: '후퇴',
};

/** 부곡 열쇠의 안정 문자열(목록 key · 선택 상태). */
export function sourceKeyId(key: SourceKey): string {
    return key.kind === 'RETINUE' ? `RETINUE:${key.sourceId}` : `CITY_GARRISON_BUGOK:${key.cityId}:${key.sourceId}`;
}

export function sameCell(a: Cell, b: Cell): boolean {
    return a.row === b.row && a.col === b.col;
}

const LONG_MAX = BigInt('9223372036854775807');

/** v2 Long 값(epoch · revision · eventSeq …)은 JSON 10진 문자열 — `0|[1-9][0-9]*`, 0..2^63−1, epoch 는 1부터. */
export function isLongString(value: unknown, minOne = false): value is string {
    if (typeof value !== 'string' || !/^(0|[1-9][0-9]*)$/.test(value)) return false;
    const n = BigInt(value);
    return n <= LONG_MAX && (!minOne || n >= BigInt(1));
}

function isInt(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number {
    return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

function isCell(value: unknown): value is Cell {
    const c = value as Cell | null;
    return c != null && typeof c === 'object' && isInt(c.row, 0, BOARD_SIZE - 1) && isInt(c.col, 0, BOARD_SIZE - 1);
}

function isSourceKey(value: unknown): value is SourceKey {
    const k = value as Record<string, unknown> | null;
    if (k == null || typeof k !== 'object') return false;
    if (k.kind === 'RETINUE') return isInt(k.sourceId, 1);
    if (k.kind === 'CITY_GARRISON_BUGOK') return isInt(k.cityId, 1) && isInt(k.sourceId, 1);
    return false;
}

/** 서버가 값을 못 줄 때의 꼴 — `null` + 사유 코드(예: SOURCE_NOT_PINNED · RULE_PIN_MISSING · SOURCE_NOT_AVAILABLE). */
export type Maybe<T> = { readonly value: T } | { readonly value: null; readonly reason: string | null };

function maybe<T>(value: T | null | undefined, reason: unknown): Maybe<T> {
    return value == null ? { value: null, reason: typeof reason === 'string' ? reason : null } : { value };
}

// ---- K6-11 활성 목록(GET /api/battles/active?generalId=) — v2 행 제안 ----

/** 표시 단계(6203행 정정 표). 이 여덟 밖의 값은 화면이 「상태 확인 중」으로 둔다. */
export const DISPLAY_PHASES = ['JOINING', 'LIVE', 'RESOLVING', 'RESULT_PENDING', 'RESULT_BLOCKED', 'ENDED', 'QUARANTINED'] as const;
export type DisplayPhase = (typeof DISPLAY_PHASES)[number];

export interface ActiveBattleRow {
    /** 10진 문자열 그대로(숫자로 바꾸지 않는다 — K6 소비 답). */
    readonly battleId: string;
    readonly worldId: number;
    /** 코드 목록은 C2 대기 — 문자열 그대로. */
    readonly kind: string;
    readonly sourcePhase: string;
    /** 아는 여덟 값이면 그 값, 아니면 null(「상태 확인 중」). */
    readonly phase: DisplayPhase | null;
    readonly pacingMode: string | null;
    /** JOINING 일 때만 의미가 있다. 없으면 남은 시간을 그리지 않는다. */
    readonly joinDeadlineAt: string | null;
    readonly mySourceKeys: readonly SourceKey[];
}

export function decodeActiveBattles(raw: unknown): ActiveBattleRow[] | null {
    if (!Array.isArray(raw)) return null;
    const rows: ActiveBattleRow[] = [];
    for (const r of raw as Record<string, unknown>[]) {
        if (r == null || typeof r !== 'object') return null;
        const battleId = typeof r.battleId === 'string' ? r.battleId : typeof r.battleId === 'number' ? String(r.battleId) : null;
        if (!battleId || !isInt(r.worldId, 0)) return null;
        const sk = (r.mySeat as { sourceKeys?: unknown } | null | undefined)?.sourceKeys;
        const keys = Array.isArray(sk) ? sk.filter(isSourceKey) : [];
        const phase = typeof r.phase === 'string' && (DISPLAY_PHASES as readonly string[]).includes(r.phase) ? (r.phase as DisplayPhase) : null;
        rows.push({
            battleId,
            worldId: r.worldId as number,
            kind: typeof r.kind === 'string' ? r.kind : '',
            sourcePhase: typeof r.sourcePhase === 'string' ? r.sourcePhase : '',
            phase,
            pacingMode: typeof r.pacingMode === 'string' ? r.pacingMode : null,
            joinDeadlineAt: typeof r.joinDeadlineAt === 'string' ? r.joinDeadlineAt : null,
            mySourceKeys: keys,
        });
    }
    return rows;
}

// ---- K6-12 · K6-13 접속 ----

export function joinTicketPath(worldId: number, battleId: string): string {
    return `/api/battles/${worldId}/${encodeURIComponent(battleId)}/join-ticket`;
}

/** WS 주소 — Cookie · Authorization 을 쓰지 않고 하위 프로토콜에 티켓을 싣는다(계약판 K6-13). */
export function battleSocketUrl(origin: string, serverId: string, worldId: number, battleId: string, lastSeenEventSeq: string): string {
    const base = origin.replace(/^http/, 'ws');
    return `${base}/ws/battles/${encodeURIComponent(serverId)}/${worldId}/${encodeURIComponent(battleId)}?lastSeenEventSeq=${lastSeenEventSeq}`;
}

export function battleSubprotocols(ticket: string): string[] {
    return ['battle.v1', ticket];
}

// ---- K6-14 서버 프레임(v2 초안) ----

export interface SnapshotUnit {
    readonly sourceKey: SourceKey;
    readonly ownerGeneralId: number;
    readonly cell: Cell;
    readonly troops: number;
    readonly morale: number;
    readonly order: BattleOrder | null;
    readonly rally: RallyPoint | null;
}

export interface Deployment {
    readonly revision: string;
    readonly defaultPinned: boolean;
    /** DB joinDeadlineAt 기준 남은 밀리초 — 화면이 받은 시각으로 보정한다. */
    readonly remainingMillis: number | null;
    readonly allowedCells: readonly Cell[];
    readonly ownPositions: readonly { readonly sourceKey: SourceKey; readonly cell: Cell }[];
}

export interface Environment {
    readonly weather: Maybe<string>;
    readonly night: Maybe<boolean>;
    readonly season: Maybe<string>;
    readonly objective: Maybe<string>;
}

export interface Snapshot {
    readonly t: 'SNAPSHOT';
    readonly battleId: string;
    readonly sessionEpoch: string;
    readonly worldId: number;
    readonly tick: number;
    readonly eventSeq: string;
    readonly pacingMode: string | null;
    readonly joinDeadlineAt: string | null;
    readonly authorityRevision: string;
    readonly field: { readonly boardId: number; readonly kind: string; readonly terrainInputSha256: string | null };
    readonly units: readonly SnapshotUnit[];
    readonly deployment: Deployment | null;
    readonly tickHz: Maybe<number>;
    readonly maxTicks: Maybe<number>;
    readonly environment: Environment;
}

/** 거절 사유 10개(C2 계약 표). 표 밖 코드는 서버가 보내지 않는다 — 와도 「알 수 없는 거절」로 둔다. */
export const REJECT_CODES = [
    'IDEMPOTENCY_CONFLICT', 'STALE_EPOCH', 'STALE_AUTHORITY', 'UNAUTHORIZED', 'SESSION_CLOSED',
    'STALE_DEPLOYMENT', 'INVALID_SPAWN',
    'STALE_TICK', 'INVALID_SCOPE', 'UNSUPPORTED_INTENT',
] as const;
export type RejectCode = (typeof REJECT_CODES)[number];

/** 거절 사유의 화면 글자(쉬운 말). 회복 조언은 도움말 사유(useReasonHelp)가 붙인다(C7 전술 registry 대기). */
export const REJECT_TEXT: Readonly<Record<RejectCode, string>> = {
    IDEMPOTENCY_CONFLICT: '같은 명령 번호로 다른 내용을 보냈습니다',
    STALE_EPOCH: '전투가 새로 시작돼 화면을 다시 맞춰야 합니다',
    STALE_AUTHORITY: '지휘권이 바뀌어 화면을 다시 맞춰야 합니다',
    UNAUTHORIZED: '내 부곡이 아닙니다',
    SESSION_CLOSED: '받는 시간이 끝났습니다',
    STALE_DEPLOYMENT: '배치가 먼저 바뀌었습니다 — 다시 보고 옮기세요',
    INVALID_SPAWN: '그 칸으로는 옮길 수 없습니다',
    STALE_TICK: '명령을 받는 때가 지났습니다',
    INVALID_SCOPE: '고른 부곡으로는 이 명령을 보낼 수 없습니다',
    UNSUPPORTED_INTENT: '이 전투에서 쓸 수 없는 명령입니다',
};

/**
 * 화면을 다시 맞춰야 하는 거절 — 배치 · 지휘권 · 전투 회차가 서버에서 먼저 바뀌어 화면의 기대 값이 낡았다. 새 SNAPSHOT 을 받아야
 * 기대 값과 자리가 함께 맞는다(ACK 의 current 는 기대 값만 주고 자리는 주지 않는다 — C2 계약 C1/C7 인계 「SNAPSHOT 재기준화」).
 */
export const RESYNC_CAUSE: Readonly<Partial<Record<RejectCode, string>>> = {
    STALE_DEPLOYMENT: '배치가 먼저 바뀌었습니다',
    STALE_AUTHORITY: '지휘권이 바뀌었습니다',
    STALE_EPOCH: '전투가 새로 시작됐습니다',
};

export interface Ack {
    readonly t: 'ACK';
    readonly battleId: string;
    readonly clientCommandId: string;
    readonly inputId: string | null;
    readonly verdict: 'ACCEPTED' | 'REJECTED';
    /** 표 안 코드면 그 코드, 표 밖이면 null(「알 수 없는 거절」). ACCEPTED 면 null. */
    readonly reasonCode: RejectCode | null;
    readonly replayed: boolean;
    readonly deploymentRevisionAfter: string | null;
}

/** 조작권 바뀜(C2 v2 답 #3) — producer 가 기록한 때에만 온다. 단순 연결 부재로 DISCONNECTED 를 추정하지 않는다(서버 몫). */
export interface Authority {
    readonly t: 'AUTHORITY';
    readonly sourceKey: SourceKey;
    readonly controller: 'HUMAN' | 'AI';
    readonly reason: string | null;
    readonly authorityRevision: string;
}

export type ServerFrame = Snapshot | Ack | Authority | { readonly t: 'IGNORED'; readonly type: string };

export type DecodeResult = { readonly ok: true; readonly frame: ServerFrame } | { readonly ok: false; readonly error: string };

function decodeSnapshot(f: Record<string, unknown>): DecodeResult {
    const field = f.field as Record<string, unknown> | null;
    if (!isInt(f.worldId, 0) || !isInt(f.tick, 0) || !isLongString(f.eventSeq) || !isLongString(f.authorityRevision)) return { ok: false, error: 'SNAPSHOT_HEAD' };
    if (field == null || typeof field !== 'object' || !isInt(field.boardId, 0, 213)) return { ok: false, error: 'SNAPSHOT_FIELD' };
    if (!Array.isArray(f.units)) return { ok: false, error: 'SNAPSHOT_UNITS' };
    const units: SnapshotUnit[] = [];
    for (const u of f.units as Record<string, unknown>[]) {
        if (u == null || !isSourceKey(u.sourceKey) || !isInt(u.ownerGeneralId, 1) || !isCell(u.cell) || !isInt(u.troops, 0) || !isInt(u.morale, 0)) {
            return { ok: false, error: 'SNAPSHOT_UNIT' };
        }
        units.push({
            sourceKey: u.sourceKey, ownerGeneralId: u.ownerGeneralId as number, cell: u.cell, troops: u.troops as number, morale: u.morale as number,
            order: (BATTLE_ORDERS as readonly unknown[]).includes(u.order) ? (u.order as BattleOrder) : null,
            rally: (RALLY_POINTS as readonly unknown[]).includes(u.rally) ? (u.rally as RallyPoint) : null,
        });
    }
    let deployment: Deployment | null = null;
    const d = f.deployment as Record<string, unknown> | null | undefined;
    if (d != null) {
        if (typeof d !== 'object' || !isLongString(d.revision) || !Array.isArray(d.allowedCells) || !Array.isArray(d.ownPositions)) return { ok: false, error: 'DEPLOYMENT' };
        const allowed = (d.allowedCells as unknown[]).filter(isCell);
        const own = (d.ownPositions as Record<string, unknown>[]).filter((p) => p != null && isSourceKey(p.sourceKey) && isCell(p.cell))
            .map((p) => ({ sourceKey: p.sourceKey as SourceKey, cell: p.cell as Cell }));
        if (allowed.length !== d.allowedCells.length || own.length !== d.ownPositions.length) return { ok: false, error: 'DEPLOYMENT_CELL' };
        deployment = {
            revision: d.revision as string,
            defaultPinned: d.defaultPinned === true,
            remainingMillis: isInt(d.remainingMillis, 0) ? (d.remainingMillis as number) : null,
            allowedCells: allowed,
            ownPositions: own,
        };
    }
    const env = (f.environment ?? {}) as Record<string, unknown>;
    const envReason = env.unavailableReason;
    return {
        ok: true,
        frame: {
            t: 'SNAPSHOT',
            battleId: String(f.battleId),
            sessionEpoch: f.sessionEpoch as string,
            worldId: f.worldId as number,
            tick: f.tick as number,
            eventSeq: f.eventSeq as string,
            pacingMode: typeof f.pacingMode === 'string' ? f.pacingMode : null,
            joinDeadlineAt: typeof f.joinDeadlineAt === 'string' ? f.joinDeadlineAt : null,
            authorityRevision: f.authorityRevision as string,
            field: { boardId: field.boardId as number, kind: typeof field.kind === 'string' ? field.kind : '', terrainInputSha256: typeof field.terrainInputSha256 === 'string' ? field.terrainInputSha256 : null },
            units,
            deployment,
            // 규칙 핀이 없으면 서버가 null + 사유(RULE_PIN_MISSING)를 준다 — v1 숫자(10Hz · 3,000틱)를 화면이 채우지 않는다(C2 #15).
            tickHz: maybe(isInt(f.tickHz, 1) ? (f.tickHz as number) : null, f.unavailableReason),
            maxTicks: maybe(isInt(f.maxTicks, 1) ? (f.maxTicks as number) : null, f.unavailableReason),
            environment: {
                weather: maybe(typeof env.weather === 'string' ? env.weather : null, envReason),
                night: maybe(typeof env.night === 'boolean' ? env.night : null, envReason),
                season: maybe(typeof env.season === 'string' ? env.season : null, envReason),
                objective: maybe(typeof env.objective === 'string' ? env.objective : null, envReason),
            },
        },
    };
}

function decodeAck(f: Record<string, unknown>): DecodeResult {
    const receipt = f.receipt as Record<string, unknown> | null;
    if (typeof f.clientCommandId !== 'string' || receipt == null || typeof receipt !== 'object') return { ok: false, error: 'ACK_HEAD' };
    const verdict = receipt.verdict;
    if (verdict !== 'ACCEPTED' && verdict !== 'REJECTED') return { ok: false, error: 'ACK_VERDICT' };
    const code = typeof receipt.reasonCode === 'string' && (REJECT_CODES as readonly string[]).includes(receipt.reasonCode) ? (receipt.reasonCode as RejectCode) : null;
    return {
        ok: true,
        frame: {
            t: 'ACK',
            battleId: String(f.battleId),
            clientCommandId: f.clientCommandId,
            inputId: typeof f.inputId === 'string' ? f.inputId : null,
            verdict,
            reasonCode: verdict === 'REJECTED' ? code : null,
            replayed: f.replayed === true,
            deploymentRevisionAfter: isLongString(receipt.deploymentRevisionAfter) ? receipt.deploymentRevisionAfter : null,
        },
    };
}

/** 서버 텍스트 프레임 하나를 해석한다. 봉투(schemaVersion 2 · t · battleId · sessionEpoch)가 틀리면 오류, 모르는 t 는 IGNORED(DELTA 등 후속). */
export function decodeServerFrame(text: string): DecodeResult {
    let f: Record<string, unknown>;
    try {
        f = JSON.parse(text) as Record<string, unknown>;
    } catch {
        return { ok: false, error: 'JSON' };
    }
    if (f == null || typeof f !== 'object' || Array.isArray(f)) return { ok: false, error: 'FRAME' };
    if (f.schemaVersion !== 2 || typeof f.t !== 'string' || f.battleId == null || !isLongString(f.sessionEpoch, true)) return { ok: false, error: 'ENVELOPE' };
    if (f.t === 'SNAPSHOT') return decodeSnapshot(f);
    if (f.t === 'ACK') return decodeAck(f);
    if (f.t === 'AUTHORITY') {
        if (!isSourceKey(f.sourceKey) || (f.controller !== 'HUMAN' && f.controller !== 'AI') || !isLongString(f.authorityRevision)) return { ok: false, error: 'AUTHORITY' };
        return { ok: true, frame: { t: 'AUTHORITY', sourceKey: f.sourceKey, controller: f.controller, reason: typeof f.reason === 'string' ? f.reason : null, authorityRevision: f.authorityRevision } };
    }
    return { ok: true, frame: { t: 'IGNORED', type: f.t } };
}

// ---- K6-15 클라이언트 프레임(v2 초안) ----

export interface DeploymentMoveArgs {
    readonly clientCommandId: string;
    readonly sourceKey: SourceKey;
    readonly targetCell: Cell;
    readonly expectedEpoch: string;
    readonly expectedAuthorityRevision: string;
    readonly expectedDeploymentRevision: string;
}

/** 배치 옮기기 — 한 부곡 · 한 칸. 기대 값(epoch · 지휘권 · 배치 revision)은 마지막 SNAPSHOT/ACK 의 값이다. */
export function deploymentMove(args: DeploymentMoveArgs): string {
    return JSON.stringify({
        schemaVersion: 2,
        t: 'DEPLOYMENT_MOVE',
        clientCommandId: args.clientCommandId,
        sourceKey: args.sourceKey,
        targetCell: { row: args.targetCell.row, col: args.targetCell.col },
        expectedEpoch: args.expectedEpoch,
        expectedAuthorityRevision: args.expectedAuthorityRevision,
        expectedDeploymentRevision: args.expectedDeploymentRevision,
    });
}

export type CommandScope = { readonly sourceKeys: readonly SourceKey[] } | { readonly allMine: true };

export interface BattleCommandArgs {
    readonly clientCommandId: string;
    readonly expectedEpoch: string;
    readonly expectedAuthorityRevision: string;
    /** 마지막으로 본 틱(JSON number). */
    readonly issuedTick: number;
    readonly scope: CommandScope;
    readonly order: BattleOrder;
    readonly rally: RallyPoint;
}

/** 전투 명령 — 고른 부곡(sourceKeys, 빈 목록 · 중복 금지) 또는 내 부곡 전부(allMine). 전부 받거나 전부 거절된다(C2 v2 #10). */
export function battleCommand(args: BattleCommandArgs): string {
    const scope = 'allMine' in args.scope
        ? { allMine: true as const }
        : { sourceKeys: [...new Map(args.scope.sourceKeys.map((k) => [sourceKeyId(k), k])).values()].sort((a, b) => sourceKeyId(a).localeCompare(sourceKeyId(b))) };
    return JSON.stringify({
        schemaVersion: 2,
        t: 'COMMAND',
        clientCommandId: args.clientCommandId,
        expectedEpoch: args.expectedEpoch,
        expectedAuthorityRevision: args.expectedAuthorityRevision,
        issuedTick: args.issuedTick,
        scope,
        intentType: args.order,
        intentPayload: { rally: args.rally },
    });
}

/** 명령 번호 — 재전송 멱등의 열쇠(같은 번호 · 같은 내용은 첫 영수증을 다시 받는다). */
export function newClientCommandId(): string {
    return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID() : `c-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}
