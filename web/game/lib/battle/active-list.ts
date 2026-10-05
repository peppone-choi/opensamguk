// 내 전투 목록(P-C04, 계약판 K6-11 `GET /api/battles/active?generalId=`) — v2 행 해석 · 단계별 표시.
//
// 서버 모양은 C2 활성 목록(#1396 BattleActiveEntry, 계약판 6203행 정정 표 · K6 → C2 소비 답 「3. 단계별 표시」 · 「4. A안」)이다.
// - Long 값은 10진 문자열이다: worldId · sourceKeys[].sourceId 가 문자열로 온다(숫자로 와도 받되 0 · 음수 · 선행 0 은 거절).
// - phase 는 정정 표의 표시 단계만 이름을 붙인다. 그 밖(READY 원문 등)은 「상태 확인 중」으로 두고 지어 바꾸지 않는다(READY 는 C2 답 대기).
//   JOINING 은 실제 마감(joinDeadlineAt)이 있을 때만 — 없으면 허위 JOINING 이라 「상태 확인 중」.
// - 끝난 전투(APPLIED)는 활성 목록에 오지 않는다(BattleActiveSessionReader 의 phase IN 목록이 뺀다). 그래서 이 목록에 ENDED · 리플레이는 없다 —
//   끝난 전투 · 리플레이는 리플레이 원천(C2 형태 합의 · K10 #1403)의 일이다(CEO 10-06 정정).
// - 장소 · 양쪽은 서버가 아직 만들지 않는다(null + SOURCE_NOT_AVAILABLE) — 화면은 서버 대기.
// - 「입장」은 내 부곡(mySeat.sourceKeys)이 있을 때만(일기토는 이 화면에서 들어가지 않는다).
// - 빈 배열은 아직 「전투 없음」이 아니다(A안 — producer 가 main 에 들어오면 C2 알림 뒤 작은 PR 로 바꾼다). 판단은 쓰는 쪽(use-active-battles).

/** 활성 목록에 오는 표시 단계(정정 표에서 APPLIED → ENDED 는 이 목록에 오지 않아 뺀다). */
export const ACTIVE_PHASES = ['JOINING', 'LIVE', 'RESOLVING', 'RESULT_PENDING', 'RESULT_BLOCKED', 'QUARANTINED'] as const;
export type ActivePhase = (typeof ACTIVE_PHASES)[number];

export interface ActiveBattleRow {
    /** 10진 문자열 그대로(숫자로 바꾸지 않는다). */
    readonly battleId: string;
    /** 10진 문자열(방 주소 `?world=` 에 그대로 붙는다). */
    readonly worldId: string;
    /** FIELD · SIEGE · DUEL — 모르는 값은 그대로 받고 화면은 「전투」. */
    readonly kind: string;
    /** 아는 표시 단계면 그 값, 아니면 null(「상태 확인 중」). */
    readonly phase: ActivePhase | null;
    /** JOINING 마감(epoch ms) — JOINING 일 때만. */
    readonly joinDeadlineAt: number | null;
    /** 내 부곡 수(mySeat.sourceKeys). */
    readonly seatCount: number;
    readonly place: string | null;
    readonly sides: readonly string[] | null;
}

const POSITIVE = /^[1-9][0-9]{0,18}$/;

/** 양의 정수 id — 10진 문자열(선행 0 없음) 또는 양의 정수 숫자. 문자열로 맞춰 돌려준다. */
function positiveId(value: unknown): string | null {
    if (typeof value === 'string') return POSITIVE.test(value) ? value : null;
    if (typeof value === 'number' && Number.isSafeInteger(value) && value > 0) return String(value);
    return null;
}

function isSourceKeyRow(value: unknown): boolean {
    const k = value as Record<string, unknown> | null;
    if (k == null || typeof k !== 'object' || positiveId(k.sourceId) == null) return false;
    if (k.kind === 'RETINUE') return true;
    return k.kind === 'CITY_GARRISON_BUGOK' && positiveId(k.cityId) != null;
}

/** 장소 — 문자열(#1396) 또는 `{name}` 꼴(계약 v2 place). 그 밖은 null(서버 대기). */
function placeName(value: unknown): string | null {
    if (typeof value === 'string' && value.trim() !== '') return value;
    const name = (value as { name?: unknown } | null)?.name;
    return typeof name === 'string' && name.trim() !== '' ? name : null;
}

/** 양쪽 — 문자열 목록(#1396) 또는 `{commanderName}` 목록(계약 v2 sides). 하나라도 이름이 없으면 null(서버 대기). */
function sideNames(value: unknown): string[] | null {
    if (!Array.isArray(value) || value.length === 0) return null;
    const names = value.map((s) => (typeof s === 'string' ? s : (s as { commanderName?: unknown } | null)?.commanderName));
    return names.every((n): n is string => typeof n === 'string' && n.trim() !== '') ? names : null;
}

/** 목록 응답 해석 — 배열이 아니거나 행 하나라도 머리(battleId · worldId · mySeat)가 틀리면 null(목록을 지어내지 않는다). */
export function decodeActiveBattles(raw: unknown): ActiveBattleRow[] | null {
    if (!Array.isArray(raw)) return null;
    const rows: ActiveBattleRow[] = [];
    for (const r of raw as Record<string, unknown>[]) {
        if (r == null || typeof r !== 'object') return null;
        const battleId = typeof r.battleId === 'string' && r.battleId !== '' ? r.battleId : positiveId(r.battleId);
        const worldId = positiveId(r.worldId);
        const keys = (r.mySeat as { sourceKeys?: unknown } | null | undefined)?.sourceKeys;
        if (!battleId || !worldId || !Array.isArray(keys) || !keys.every(isSourceKeyRow)) return null;
        const deadline = typeof r.joinDeadlineAt === 'string' ? Date.parse(r.joinDeadlineAt) : NaN;
        const known = typeof r.phase === 'string' && (ACTIVE_PHASES as readonly string[]).includes(r.phase) ? (r.phase as ActivePhase) : null;
        // JOINING 은 실제 마감이 있을 때만(허위 JOINING 금지 — 정정 표).
        const phase = known === 'JOINING' && !Number.isFinite(deadline) ? null : known;
        rows.push({
            battleId,
            worldId,
            kind: typeof r.kind === 'string' ? r.kind : '',
            phase,
            joinDeadlineAt: phase === 'JOINING' ? deadline : null,
            seatCount: keys.length,
            place: placeName(r.place),
            sides: sideNames(r.sides),
        });
    }
    return rows;
}

export const KIND_LABEL: Readonly<Record<string, string>> = { FIELD: '야전', SIEGE: '공성', DUEL: '일기토' };
export const kindLabel = (kind: string): string => KIND_LABEL[kind] ?? '전투';

export type RowAction = 'enter' | null;

/** 단계별 상태 칩 · 행동(K6 → C2 소비 답 「3. 단계별 표시」). tone 은 os-chip 꼬리 이름. */
export const PHASE_VIEW: Readonly<Record<ActivePhase, { readonly label: string; readonly tone: '' | 'bronze' | 'rust' | 'info'; readonly action: RowAction }>> = {
    JOINING: { label: '참가 대기', tone: 'bronze', action: 'enter' },
    LIVE: { label: '진행 중', tone: 'rust', action: 'enter' },
    RESOLVING: { label: '판정 중', tone: 'info', action: null },
    RESULT_PENDING: { label: '결과 반영 기다림', tone: 'info', action: null },
    RESULT_BLOCKED: { label: '결과 반영이 막힘 — 운영 확인 중', tone: 'rust', action: null },
    QUARANTINED: { label: '확인 중 — 운영이 살피는 중', tone: '', action: null },
};
export const UNKNOWN_PHASE = { label: '상태 확인 중', tone: '' as const, action: null };

/** 그 행에서 할 수 있는 것 — 입장은 참가 대기 · 진행 중이고 내 부곡이 있을 때만(일기토 제외). */
export function rowAction(row: ActiveBattleRow): RowAction {
    const action = row.phase ? PHASE_VIEW[row.phase].action : null;
    return action === 'enter' && row.seatCount > 0 && row.kind !== 'DUEL' ? 'enter' : null;
}

/** 정렬은 화면이 한다: JOINING(마감 빠른 순) → LIVE → 나머지(받은 차례). */
export function sortActiveBattles(rows: readonly ActiveBattleRow[]): ActiveBattleRow[] {
    const rank = (r: ActiveBattleRow) => (r.phase === 'JOINING' ? 0 : r.phase === 'LIVE' ? 1 : 2);
    return rows.map((r, i) => ({ r, i })).sort((a, b) => rank(a.r) - rank(b.r)
        || (a.r.phase === 'JOINING' && b.r.phase === 'JOINING' ? (a.r.joinDeadlineAt ?? 0) - (b.r.joinDeadlineAt ?? 0) : 0)
        || a.i - b.i).map((x) => x.r);
}

export function activeBattlesPath(generalId: number): string {
    return `/api/battles/active?generalId=${generalId}`;
}
