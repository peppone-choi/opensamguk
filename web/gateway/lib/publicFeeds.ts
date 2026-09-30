// 로그인 · 로비의 공개 경로(/api/server-events · /api/server-imperial)가 내보내는 필드 허용 목록.
// game-api 가 permitAll 이라는 것만 믿지 않는다(C0 → C8 · C7 대조, 2026-10-01): upstream 원문을 흘리지 않고
// 화면에 필요한 공개 필드만 골라 새로 만든다. 모르는 칸 · 다른 분류 사건 · 허용되지 않은 역할은 버린다.

/** 천하 정세에 나올 수 있는 사건 종류와 그 종류가 가질 수 있는 역할(서버 EventKind WORLD 절, logic/record/EventKind.kt). */
const WORLD_KIND_REFS: Readonly<Record<string, readonly string[]>> = {
    'county.ownerChanged': ['CITY', 'FROM_NATION', 'TO_NATION'],
    'roadFort.captured': ['ROAD_FORT', 'FROM_NATION', 'TO_NATION'],
    'yuedan.announced': [],
    'server.catchUpFinished': [],
};

export interface PublicWorldEvent {
    readonly id: number;
    readonly kind: string;
    readonly section: 'WORLD';
    readonly occurredAt: { readonly year: number; readonly month: number; readonly phase: number; readonly ordinal: number };
    readonly refs: Readonly<Record<string, number>>;
    /** WORLD 사건은 공개 사실이 없다 — 늘 빈 객체로 낸다. */
    readonly facts: Readonly<Record<string, never>>;
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const isInt = (value: unknown): value is number => typeof value === 'number' && Number.isInteger(value);

function publicEvent(value: unknown): PublicWorldEvent | null {
    if (!isRecord(value) || !isInt(value.id) || typeof value.kind !== 'string' || value.section !== 'WORLD') return null;
    const roles = WORLD_KIND_REFS[value.kind];
    if (!roles) return null;
    const at = value.occurredAt;
    if (!isRecord(at) || !isInt(at.year) || !isInt(at.month) || !isInt(at.phase) || !isInt(at.ordinal)) return null;
    const refs: Record<string, number> = {};
    const raw = isRecord(value.refs) ? value.refs : {};
    for (const role of roles) {
        const id = raw[role];
        if (isInt(id) && id >= 0) refs[role] = id;
    }
    return {
        id: value.id,
        kind: value.kind,
        section: 'WORLD',
        occurredAt: { year: at.year, month: at.month, phase: at.phase, ordinal: at.ordinal },
        refs,
        facts: {},
    };
}

/** game-api /api/world-events 응답 → 공개 사건만. 형식이 틀리면 null(경로는 502 로 끊는다). */
export function publicWorldEvents(raw: unknown): { readonly events: readonly PublicWorldEvent[] } | null {
    if (!isRecord(raw) || !Array.isArray(raw.events)) return null;
    return { events: raw.events.flatMap((event) => { const kept = publicEvent(event); return kept ? [kept] : []; }) };
}

export interface PublicImperialBadge {
    readonly lineCode: string;
    readonly lineName: string;
    /** 황제가 있는 城. 城 밖이면 null. */
    readonly emperorCityId: number | null;
}

export type PublicImperialStatus = 'READY' | 'NOT_SEEDED' | 'STATE_UNAVAILABLE';

/** game-api /api/imperial/presence 응답 → 화면이 쓰는 칸만(황제 장수 id · 노드 id · 조정 城은 내보내지 않는다). */
export function publicImperialPresence(raw: unknown): { readonly status: PublicImperialStatus; readonly badges: readonly PublicImperialBadge[] } | null {
    if (!isRecord(raw) || (raw.status !== 'READY' && raw.status !== 'NOT_SEEDED' && raw.status !== 'STATE_UNAVAILABLE')) return null;
    if (raw.status !== 'READY') return { status: raw.status, badges: [] };
    if (!Array.isArray(raw.badges)) return null;
    const badges = raw.badges.flatMap((badge): PublicImperialBadge[] => {
        if (!isRecord(badge) || typeof badge.lineCode !== 'string' || typeof badge.lineName !== 'string') return [];
        const city = badge.emperorCityId;
        if (city !== null && !isInt(city)) return [];
        return [{ lineCode: badge.lineCode, lineName: badge.lineName, emperorCityId: city }];
    });
    return { status: 'READY', badges };
}
