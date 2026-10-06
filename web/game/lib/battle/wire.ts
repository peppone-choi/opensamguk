// 실시간 전투 wire 공통 형(型) · 검사 — **초안 — C2 병합 때 맞춤.** 입구는 protocol.ts(여기 것을 다시 내보낸다).
// 부곡 열쇠 · 칸 · 6명령 · 집결점 · Long(10진 문자열) · 서버가 못 준 값(Maybe). 출처는 protocol.ts 머리 주석.

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

/**
 * 집결 단추 번호 ↔ 집결점(원장 D114, CEO 10-05): 집결 1 = HOME · 2 = CENTER · 3 = ENEMY — 계약 enum 순서이자 거리 순서.
 * 단추 글자는 보드대로 「집결 n」, 풀이는 쉬운 말(RALLY_HINT).
 */
export const RALLY_NUMBER: Readonly<Record<RallyPoint, 1 | 2 | 3>> = { HOME: 1, CENTER: 2, ENEMY: 3 };
export const RALLY_HINT: Readonly<Record<RallyPoint, string>> = { HOME: '우리 쪽', CENTER: '가운데', ENEMY: '적 쪽' };

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

export function isInt(value: unknown, min = 0, max = Number.MAX_SAFE_INTEGER): value is number {
    return typeof value === 'number' && Number.isInteger(value) && value >= min && value <= max;
}

export function isCell(value: unknown): value is Cell {
    const c = value as Cell | null;
    return c != null && typeof c === 'object' && isInt(c.row, 0, BOARD_SIZE - 1) && isInt(c.col, 0, BOARD_SIZE - 1);
}

export function isSourceKey(value: unknown): value is SourceKey {
    const k = value as Record<string, unknown> | null;
    if (k == null || typeof k !== 'object') return false;
    if (k.kind === 'RETINUE') return isInt(k.sourceId, 1);
    if (k.kind === 'CITY_GARRISON_BUGOK') return isInt(k.cityId, 1) && isInt(k.sourceId, 1);
    return false;
}

/** 서버가 값을 못 줄 때의 꼴 — `null` + 사유 코드(예: SOURCE_NOT_PINNED · RULE_PIN_MISSING · SOURCE_NOT_AVAILABLE). */
export type Maybe<T> = { readonly value: T } | { readonly value: null; readonly reason: string | null };

export function maybe<T>(value: T | null | undefined, reason: unknown): Maybe<T> {
    return value == null ? { value: null, reason: typeof reason === 'string' ? reason : null } : { value };
}
