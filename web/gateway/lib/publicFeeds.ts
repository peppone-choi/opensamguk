// 로그인 · 로비의 공개 경로(/api/server-events · /api/server-imperial)가 game-api 를 부르고 내보내는 규칙.
// 근거: C0 인계(meta reports/opensamguk/tasks/2026-10-01-c0-public-login-handoff.md) · C8 적색 fixture 78건
// (__tests__/fixtures/c8-public-feeds.json). game-api 가 permitAll 이라는 것만 믿지 않는다:
// - 등록부로 푼 origin 만 부른다 · 자격증명 없이(credentials omit) · 리다이렉트는 따라가지 않는다(redirect error).
// - 정상 상태 코드 + JSON + 크기 한도 + 시간 한도를 통과한 본문만 읽고, 공개 필드 허용 목록으로 새로 만든다.
// - 사건 하나가 틀리면 그 사건만 버린다(drop-only 투영, C0 수용). 겉 모양이 틀리거나 요청보다 많으면 통째로 502.
// - 실패는 늘 고정 문구 + no-store. upstream 본문 · 오류 문장을 밖으로 흘리지 않는다.

/** C8 방어 제안값 — 확정 제품 계약이 아니다(C0 인계 4). 바꾸면 fixture 의 oversize · timeout 경우도 함께 본다. */
export const PROPOSED_MAX_BODY_BYTES = 64 * 1024;
/** C8 방어 제안값 — 확정 제품 계약이 아니다(C0 인계 4). */
export const PROPOSED_TIMEOUT_MS = 5000;
/** 정상 공개 응답만 30초 캐시한다(K5 구현안). 실패 · 비정상 상태는 no-store. */
export const PUBLIC_CACHE = 'public, max-age=30';

export type UpstreamRead =
    | { readonly kind: 'ok'; readonly status: number; readonly json: unknown }
    | { readonly kind: 'status'; readonly status: number }
    | { readonly kind: 'fail' };

async function readLimited(response: Response, maxBytes: number): Promise<string | null> {
    if (!response.body) return '';
    const reader = response.body.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    try {
        for (;;) {
            const { done, value } = await reader.read();
            if (done) break;
            total += value.byteLength;
            if (total > maxBytes) {
                await reader.cancel().catch(() => undefined);
                return null;
            }
            chunks.push(value);
        }
    } catch {
        return null;
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.byteLength; }
    return new TextDecoder().decode(bytes);
}

/**
 * 공개 읽기 한 번. `readStatuses` 에 든 상태만 본문을 JSON 으로 읽는다(그 밖은 상태만 돌려준다).
 * 연결 실패 · 리다이렉트 · 시간 초과 · JSON 아님 · 크기 초과 · 본문 읽기 실패는 모두 'fail'.
 */
export async function readPublicJson(url: string, readStatuses: readonly number[]): Promise<UpstreamRead> {
    let response: Response;
    try {
        response = await fetch(url, {
            cache: 'no-store',
            credentials: 'omit',
            redirect: 'error',
            signal: AbortSignal.timeout(PROPOSED_TIMEOUT_MS),
        });
    } catch {
        return { kind: 'fail' };
    }
    if (!readStatuses.includes(response.status)) {
        await response.body?.cancel().catch(() => undefined);
        return { kind: 'status', status: response.status };
    }
    // 매개변수(charset 등)를 뗀 형식이 정확히 application/json 이어야 한다 — 앞글자만 보면 jsonp · json-seq 가 통과한다(C8 #1098 P3).
    const mediaType = (response.headers.get('content-type') ?? '').split(';')[0].trim().toLowerCase();
    if (mediaType !== 'application/json') return { kind: 'fail' };
    const text = await readLimited(response, PROPOSED_MAX_BODY_BYTES).catch(() => null);
    if (text === null) return { kind: 'fail' };
    try {
        return { kind: 'ok', status: response.status, json: JSON.parse(text) as unknown };
    } catch {
        return { kind: 'fail' };
    }
}

const isRecord = (value: unknown): value is Record<string, unknown> => typeof value === 'object' && value !== null && !Array.isArray(value);
const isInt = (value: unknown): value is number => typeof value === 'number' && Number.isSafeInteger(value);
const positive = (value: unknown): value is number => isInt(value) && value > 0;
const nonNegative = (value: unknown): value is number => isInt(value) && value >= 0;
// 보루 참조 계약(C8 2026-10-01 · meta reports/opensamguk/tasks/2026-10-01-c8-roadfort-ref-contract.md).
// 서버 보루 ID 는 RoadFort.siteId(edgeId, row, col) = "$edgeId@$row,$col"(RoadFortState.kt), legacy 안정 ID 도 그대로 받는다.
/** legacy 보루 ID · 도로 edge ID(서버 isStableKey). 128자 한도는 이 부분에만 건다. */
const ROAD_STABLE_ID = /^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/;
/** site 좌표 한 칸: 문자열 전체가 0 또는 앞자리 0 없는 10자리 이하 십진(유니코드 숫자 · 부호 · 소수 · 지수 거절). */
const ROAD_FORT_CELL = /^(0|[1-9][0-9]{0,9})$/;
const KOTLIN_INT_MAX = 2147483647;
/** edge 128 + '@' + 10 + ',' + 10. 구성 한도에서 이미 따라 나오는 값이라, 이 검사는 긴 입력을 split 전에 끊는 몫이다. */
const ROAD_FORT_SITE_MAX_LENGTH = 150;
const roadFortCell = (part: string) => ROAD_FORT_CELL.test(part) && Number(part) <= KOTLIN_INT_MAX;

/** ROAD_FORT 전용 검사. 판정만 하고 원문을 고치지 않는다(trim · 치환 · parseInt 없음). 공용 ref 검사는 넓히지 않는다. */
function roadFortId(value: unknown): boolean {
    if (typeof value !== 'string') return false;
    if (ROAD_STABLE_ID.test(value)) return true;
    if (value.length > ROAD_FORT_SITE_MAX_LENGTH) return false;
    const [edge, cells, ...extraAt] = value.split('@');
    if (extraAt.length > 0 || cells === undefined || !ROAD_STABLE_ID.test(edge)) return false;
    const [row, col, ...extraComma] = cells.split(',');
    return extraComma.length === 0 && col !== undefined && roadFortCell(row) && roadFortCell(col);
}

type RefCheck = (value: unknown) => boolean;
const REF_TYPES: Readonly<Record<string, RefCheck>> = {
    CITY: positive,
    FROM_NATION: nonNegative,
    TO_NATION: nonNegative,
    ROAD_FORT: roadFortId,
};

/** 천하 정세 사건 종류별 필수 · 선택 역할(서버 EventKind WORLD 절 · C0 인계 표). */
const WORLD_KINDS: Readonly<Record<string, { readonly required: readonly string[]; readonly optional: readonly string[] }>> = {
    'county.ownerChanged': { required: ['CITY', 'FROM_NATION', 'TO_NATION'], optional: [] },
    'roadFort.captured': { required: ['ROAD_FORT', 'TO_NATION'], optional: ['FROM_NATION'] },
    'yuedan.announced': { required: [], optional: [] },
    'server.catchUpFinished': { required: [], optional: [] },
};

export interface PublicWorldEvent {
    readonly id: number;
    readonly kind: string;
    readonly section: 'WORLD';
    readonly occurredAt: { readonly year: number; readonly month: number; readonly phase: number; readonly ordinal: number };
    /** 도시 · 세력은 정수 id(세력 0 = 주인 없음), 보루는 안정 문자열 id. */
    readonly refs: Readonly<Record<string, number | string>>;
    /** WORLD 사건은 공개 사실이 없다 — 늘 빈 객체. */
    readonly facts: Readonly<Record<string, never>>;
}

function publicEvent(value: unknown): PublicWorldEvent | null {
    if (!isRecord(value) || !positive(value.id) || typeof value.kind !== 'string' || value.section !== 'WORLD') return null;
    const rule = Object.prototype.hasOwnProperty.call(WORLD_KINDS, value.kind) ? WORLD_KINDS[value.kind] : undefined;
    if (!rule) return null;
    const at = value.occurredAt;
    if (!isRecord(at) || !isInt(at.year) || at.year < 1 || at.year > 9999 || !isInt(at.month) || at.month < 1 || at.month > 12
        || !isInt(at.phase) || at.phase < 1 || at.phase > 3 || !nonNegative(at.ordinal)) return null;
    if (!isRecord(value.refs) || !isRecord(value.facts) || Object.keys(value.facts).length > 0) return null;
    const refs: Record<string, number | string> = {};
    for (const [role, ref] of Object.entries(value.refs)) {
        const allowed = rule.required.includes(role) || rule.optional.includes(role);
        if (!allowed || !REF_TYPES[role]?.(ref)) return null; // 모르는 · 비공개 역할이나 틀린 값이 섞이면 그 사건을 버린다
        refs[role] = ref as number | string;
    }
    if (!rule.required.every((role) => role in refs)) return null;
    return {
        id: value.id,
        kind: value.kind,
        section: 'WORLD',
        occurredAt: { year: at.year, month: at.month, phase: at.phase, ordinal: at.ordinal },
        refs,
        facts: {},
    };
}

/**
 * game-api /api/world-events 본문 → 공개 사건만. 겉 모양이 틀리거나(events 배열 아님) 요청한 수보다 많으면 null(경로가 502).
 * nextCursor 는 화면이 쓰지 않아 내보내지 않는다.
 */
export function publicWorldEvents(raw: unknown, requestedLimit: number): { readonly events: readonly PublicWorldEvent[] } | null {
    if (!isRecord(raw) || !Array.isArray(raw.events) || raw.events.length > requestedLimit) return null;
    return { events: raw.events.flatMap((event) => { const kept = publicEvent(event); return kept ? [kept] : []; }) };
}

export interface PublicImperialBadge {
    readonly lineCode: string;
    readonly lineName: string;
    /** 황제가 있는 城. 城 밖이면 null(조정 城으로 대신 적지 않는다). */
    readonly emperorCityId: number | null;
}

export type PublicImperialStatus = 'READY' | 'NOT_SEEDED' | 'STATE_UNAVAILABLE';

const NODE_KINDS = new Set(['LAND_PROVINCE', 'WATER_ZONE']);
const cityOrNull = (badge: Record<string, unknown>, key: string) => key in badge && (badge[key] === null || positive(badge[key]));

/** 서버 배지 7필드를 검사한 뒤 화면이 쓰는 3필드만 낸다(C0: 더 좁은 화면 투영). 틀리면 null. */
function publicBadge(value: unknown): PublicImperialBadge | null {
    if (!isRecord(value) || typeof value.lineCode !== 'string' || typeof value.lineName !== 'string' || !positive(value.emperorGeneralId)
        || typeof value.emperorNodeKind !== 'string' || !NODE_KINDS.has(value.emperorNodeKind)
        || typeof value.emperorNodeId !== 'string' || value.emperorNodeId === ''
        || !cityOrNull(value, 'emperorCityId') || !cityOrNull(value, 'courtCityId')) return null;
    return { lineCode: value.lineCode, lineName: value.lineName, emperorCityId: value.emperorCityId as number | null };
}

/**
 * game-api /api/imperial/presence 의 (HTTP 상태, 본문) → 공개 응답. 상태 짝이 서버 계약과 다르면 null(경로가 502):
 * 200 은 READY · NOT_SEEDED 만, 409 는 STATE_UNAVAILABLE 만. READY 가 아니면 배지는 늘 비운다. READY 배지가 하나라도 틀리면 null.
 */
export function publicImperialPresence(httpStatus: number, raw: unknown): { readonly status: PublicImperialStatus; readonly badges: readonly PublicImperialBadge[] } | null {
    if (!isRecord(raw) || !Array.isArray(raw.badges)) return null;
    if (httpStatus === 200 && raw.status === 'READY') {
        // 배지 하나라도 틀리면 통째로 거절한다 — 틀린 배지만 버리면 「황제 없음(공위)」으로 잘못 읽힌다.
        const badges = raw.badges.map(publicBadge);
        return badges.every((badge): badge is PublicImperialBadge => badge !== null) ? { status: 'READY', badges } : null;
    }
    if (httpStatus === 200 && raw.status === 'NOT_SEEDED') return { status: 'NOT_SEEDED', badges: [] };
    if (httpStatus === 409 && raw.status === 'STATE_UNAVAILABLE') return { status: 'STATE_UNAVAILABLE', badges: [] };
    return null;
}
