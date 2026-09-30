// 명령 흐름 상태 — 순수 함수(React 밖에서 시험한다). 설계서 §2.1 「상태 유지 규칙」.
//
//  - 명령별 초안: 흐름이 열려 있는 동안 inputId마다 입력값을 기억한다. 다른 명령에 갔다 와도 그대로다.
//  - 같은 종류 인자 이어받기: 인자 이름이 같으면 같은 종류다(목적 구역 · 대상 인물 · 첩보할 군).
//    새 명령의 초안에 그 값이 아직 없을 때만 옮긴다(이미 적은 값은 덮지 않는다).
//    옮긴 값이 새 명령의 후보에 없으면 화면이 dropInvalid로 비우고 「이 명령에서는 고를 수 없는 곳입니다」를 한 줄 보인다.
//  - 예약에 성공하면 닫지 않고 다음 빈 순으로 간다. 빈 순이 없으면 full = true.

export type ArgValue = string | number | readonly number[] | null;
export type Draft = Readonly<Record<string, ArgValue>>;

export interface FlowState {
    /** 0–11 — 지금 채우는 순. */
    readonly slot: number;
    readonly inputId: string | null;
    readonly drafts: Readonly<Record<string, Draft>>;
    /** 방금 이어받은 인자 이름(안내 한 줄용). */
    readonly carried: readonly string[];
    /** 12순이 다 찼다 — 「12순이 다 찼습니다 — 채운 순을 눌러 바꾸세요」. */
    readonly full: boolean;
}

/** 이어받는 인자 이름 — 서버 인자 키 그대로. 같은 키 = 같은 종류(설계서 §2.1). */
export const CARRY_KEYS: readonly string[] = ['destinationProvinceId', 'targetGeneralId', 'commanderyId', 'targetCountyId'];

export const SLOT_COUNT = 12;

/** 명령을 고르기 전에 받은 대상(지도 「여기로 명령」 · 인물 「이 사람에게」). 처음 고른 명령이 이어받는다. */
const SEED = '__seed';

export function initialFlow(slot: number, inputId: string | null = null, seed: Draft = {}): FlowState {
    const s = clampSlot(slot);
    const drafts = inputId ? { [inputId]: { ...seed } } : Object.keys(seed).length > 0 ? { [SEED]: { ...seed } } : {};
    return { slot: s, inputId, drafts, carried: [], full: false };
}

export function clampSlot(slot: number): number {
    return Number.isInteger(slot) && slot >= 0 && slot < SLOT_COUNT ? slot : 0;
}

/** 명령을 고른다 — 앞 명령의 같은 종류 값을 이어받는다. 이미 그 명령에 적은 값은 그대로 둔다. */
export function selectCommand(state: FlowState, inputId: string): FlowState {
    if (state.inputId === inputId) return { ...state, carried: [] };
    const prev = state.inputId ? state.drafts[state.inputId] ?? {} : state.drafts[SEED] ?? {};
    const current = state.drafts[inputId] ?? {};
    const next: Record<string, ArgValue> = { ...current };
    const carried: string[] = [];
    for (const key of CARRY_KEYS) {
        const v = prev[key];
        if (v != null && next[key] == null) {
            next[key] = v;
            carried.push(key);
        }
    }
    return { ...state, inputId, drafts: { ...state.drafts, [inputId]: next }, carried };
}

export function setArg(state: FlowState, key: string, value: ArgValue): FlowState {
    if (!state.inputId) return state;
    const draft = { ...(state.drafts[state.inputId] ?? {}), [key]: value };
    return { ...state, drafts: { ...state.drafts, [state.inputId]: draft }, carried: state.carried.filter(k => k !== key) };
}

/** 이어받은 값이 새 명령의 후보에 없을 때 비운다. 비웠으면 dropped = true(안내 한 줄). */
export function dropInvalid(state: FlowState, key: string, isValid: (value: ArgValue) => boolean): { state: FlowState; dropped: boolean } {
    if (!state.inputId) return { state, dropped: false };
    const draft = state.drafts[state.inputId] ?? {};
    const v = draft[key];
    if (v == null || isValid(v)) return { state, dropped: false };
    const rest: Record<string, ArgValue> = { ...draft };
    delete rest[key];
    return { state: { ...state, drafts: { ...state.drafts, [state.inputId]: rest } }, dropped: true };
}

export function currentDraft(state: FlowState): Draft {
    return state.inputId ? state.drafts[state.inputId] ?? {} : {};
}

/** 순 띠에서 순을 고른다. 초안은 그대로다(순이 바뀌어도 적던 값은 남는다). */
export function selectSlot(state: FlowState, slot: number): FlowState {
    return { ...state, slot: clampSlot(slot), full: false };
}

/**
 * 예약 성공 뒤 — 닫지 않고 다음 빈 순으로 간다. filled = 서버가 돌려준 채운 순(방금 채운 순 포함).
 * 지금 순 뒤에서 먼저 찾고, 없으면 앞에서 찾는다. 하나도 없으면 full.
 */
export function afterReserved(state: FlowState, filled: ReadonlySet<number>): FlowState {
    const taken = new Set(filled);
    taken.add(state.slot);
    for (let step = 1; step <= SLOT_COUNT; step++) {
        const s = (state.slot + step) % SLOT_COUNT;
        if (!taken.has(s)) return { ...state, slot: s, full: false, carried: [] };
    }
    return { ...state, full: true, carried: [] };
}

/** 여는 순 — 「이번 순에 할 일」은 다음 빈 순(없으면 01순 + full). */
export function firstEmptySlot(filled: ReadonlySet<number>): { slot: number; full: boolean } {
    for (let s = 0; s < SLOT_COUNT; s++) if (!filled.has(s)) return { slot: s, full: false };
    return { slot: 0, full: true };
}
