// 시야 · 첩보(P-C06) 모델 — `/api/visibility` + `/api/scout-options`. K6 설계서 §3.6, 시야 계약 §4.
// - 단계: 다 보임(FULL) · 첩보(INTEL, 「N순 전」 — 만료 없음) · 안 보임(FOG). 색만으로 가르지 않는다(글자 칩).
// - 누출 금지: 역정보는 첩보 스냅숏에 섞여 오고 표식이 없다. 이 화면은 「가짜 · 역정보 · 의심」을 어디에도 쓰지 않는다.
// - 시야 출처(sources)는 받은 응답의 모양을 엄격히 가린다. 키 없음(옛 서버) · 빈 목록 · 목록이 아님 · 알 수 없는 행을 서로 다르게 보이고,
//   알 수 없는 행은 고치거나 버리지 않고 「알 수 없음」으로 남긴다. 위치는 같은 응답의 郡國만 맞춘다(따로 읽지 않는다).
//   provinceId · refId 는 화면에 쓰지 않는다(날 id 금지). 반경은 서버 정수 그대로 — 기본값 · 추정 시야 없음.
// - 서버의 「읽지 못한 출처 기록」 수(invalidSourceRecords)와 이 화면이 가린 행 수는 다른 값이라 따로 둔다. 둘 다 알림일 뿐 첩보를 막지 않는다.
import type { ScoutOptions, Visibility, VisionTier } from '../campaign-reads';

export const TIER_LABEL: Readonly<Record<VisionTier, string>> = { FULL: '다 보임', INTEL: '첩보', FOG: '안 보임' };
export const TIER_ORDER: readonly VisionTier[] = ['INTEL', 'FOG', 'FULL'];

export type VisionSourceKind = 'SELF' | 'OWN_CORPS' | 'RETINUE' | 'TERRITORY' | 'SCOUT_POST' | 'WATCHTOWER_BEACON';
export const SOURCE_LABEL: Readonly<Record<VisionSourceKind, string>> = {
    SELF: '내 위치',
    OWN_CORPS: '내 군단',
    RETINUE: '부 인물',
    TERRITORY: '우리 세력 영토',
    SCOUT_POST: '정찰 배치',
    WATCHTOWER_BEACON: '망루·봉화',
};

/** 서버 `Int` 의 범위 — 반경 · 郡國 번호 · 기록 수는 이 안의 0 이상 정수만, refId 는 이 안의 정수(음수 포함)만 받는다. */
const SERVER_INT_MIN = -2_147_483_648;
const SERVER_INT_MAX = 2_147_483_647;
const isServerInt = (v: unknown): v is number => typeof v === 'number' && Number.isInteger(v) && v >= SERVER_INT_MIN && v <= SERVER_INT_MAX;
const isServerCount = (v: unknown): v is number => isServerInt(v) && v >= 0;
const isRecord = (v: unknown): v is Readonly<Record<string, unknown>> => typeof v === 'object' && v !== null && !Array.isArray(v);
const isSourceKind = (v: unknown): v is VisionSourceKind => typeof v === 'string' && Object.prototype.hasOwnProperty.call(SOURCE_LABEL, v);

export interface IntelSourceRow {
    /** 알 수 없는 종류 · 행이면 null. */
    readonly kind: VisionSourceKind | null;
    /** 같은 응답의 郡國 이름 — 번호가 잘못됐거나 맞는 郡國이 없으면 null. */
    readonly place: string | null;
    /** 서버 반경 — 정수가 아니거나 범위 밖이면 null. */
    readonly radius: number | null;
    /** 한 칸이라도 계약 모양과 어긋난 행. */
    readonly malformed: boolean;
}

export type IntelSources =
    | { readonly state: 'absent' }
    | { readonly state: 'malformed' }
    | { readonly state: 'listed'; readonly rows: readonly IntelSourceRow[]; readonly malformedRows: number };

/** 서버의 읽지 못한 출처 기록 수 — 없음 · 잘못된 값은 0 으로 두지 않는다. */
export type ServerInvalidSources =
    | { readonly state: 'missing' }
    | { readonly state: 'invalid' }
    | { readonly state: 'count'; readonly count: number };

export function toIntelSources(vision: Visibility): IntelSources {
    if (vision.sources === undefined) return { state: 'absent' };
    const list = vision.sources;
    if (!Array.isArray(list)) return { state: 'malformed' };
    const names = new Map<number, string>();
    for (const c of vision.commanderies ?? []) if (!names.has(c.no)) names.set(c.no, c.name);
    const rows = list.map((item: unknown): IntelSourceRow => {
        if (!isRecord(item)) return { kind: null, place: null, radius: null, malformed: true };
        const kind = isSourceKind(item.kind) ? item.kind : null;
        const no = isServerCount(item.commanderyNo) ? item.commanderyNo : null;
        const radius = isServerCount(item.radius) ? item.radius : null;
        const provinceOk = item.provinceId === undefined || typeof item.provinceId === 'string';
        // refId 는 서버 `Int?` — null 이면 키째 빠진다(NON_NULL). 있으면 Int 범위 정수여야 한다.
        const refOk = item.refId === undefined || isServerInt(item.refId);
        return {
            kind,
            place: no != null ? names.get(no) ?? null : null,
            radius,
            malformed: kind == null || no == null || radius == null || !provinceOk || !refOk,
        };
    });
    return { state: 'listed', rows, malformedRows: rows.filter((r) => r.malformed).length };
}

export function toServerInvalidSources(vision: Visibility): ServerInvalidSources {
    if (vision.invalidSourceRecords === undefined) return { state: 'missing' };
    return isServerCount(vision.invalidSourceRecords) ? { state: 'count', count: vision.invalidSourceRecords } : { state: 'invalid' };
}

export interface IntelRow {
    readonly no: number;
    /** 첩보 대상 인자(commanderyId). */
    readonly id: string;
    readonly name: string;
    readonly tier: VisionTier;
    readonly ageTurns: number | null;
    /** 첩보 옵션 — 이 군이 첩보 후보면 가능 여부 · 사유, 후보가 아니면 null(단추를 그리지 않는다). */
    readonly scout: { readonly available: boolean; readonly code: string | null; readonly reason: string | null } | null;
}

export type IntelView =
    | { readonly state: 'unreadable'; readonly status: string }
    | {
        readonly state: 'ready';
        readonly groups: readonly { readonly tier: VisionTier; readonly rows: readonly IntelRow[] }[];
        readonly scoutBlocked: { readonly code: string | null; readonly reason: string | null } | null;
        readonly sources: IntelSources;
        readonly serverInvalidSources: ServerInvalidSources;
    };

export function toIntelView(vision: Visibility, scout: ScoutOptions | null): IntelView {
    if (vision.status !== 'READY') return { state: 'unreadable', status: vision.status };
    const scoutReady = scout?.status === 'READY' ? scout : null;
    const byNo = new Map((scoutReady?.options ?? []).map((o) => [o.no, o]));
    const rows: IntelRow[] = (vision.commanderies ?? []).map((c) => {
        const o = byNo.get(c.no);
        return {
            no: c.no, id: c.id, name: c.name, tier: c.tier, ageTurns: c.ageTurns ?? null,
            scout: o ? { available: o.available, code: o.code ?? null, reason: o.reason ?? null } : null,
        };
    });
    const groups = TIER_ORDER.map((tier) => ({
        tier,
        rows: rows.filter((r) => r.tier === tier).sort((a, b) => (b.ageTurns ?? -1) - (a.ageTurns ?? -1) || a.name.localeCompare(b.name, 'ko')),
    })).filter((g) => g.rows.length > 0);
    const blocked = scoutReady && scoutReady.available === false ? { code: scoutReady.code ?? null, reason: scoutReady.reason ?? null } : null;
    return { state: 'ready', groups, scoutBlocked: blocked, sources: toIntelSources(vision), serverInvalidSources: toServerInvalidSources(vision) };
}

/** 출처 행의 둘째 줄 — 고른 郡國 이름 · 반경. 모르는 칸은 「알 수 없음」으로 남긴다. */
export function sourceLine(row: IntelSourceRow): string {
    const place = row.place ?? '위치 알 수 없음';
    const radius = row.radius != null ? `반경 ${row.radius}칸` : '반경 알 수 없음';
    return `${place} · ${radius}`;
}

/** 첩보 행 둘째 줄 — 일반 문구만(역정보 표식 없음). */
export function tierLine(row: IntelRow): string {
    if (row.tier === 'INTEL') return row.ageTurns != null ? `${row.ageTurns}순 전 첩보 · 다시 첩보하면 갱신됩니다` : '첩보 · 다시 첩보하면 갱신됩니다';
    if (row.tier === 'FULL') return '지금 보입니다';
    return '보이지 않습니다';
}
