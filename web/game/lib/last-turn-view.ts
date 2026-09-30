// 지난 순 서랍(P-W04)의 보기 모델 — `/api/last-turns` 를 12순 묶음 · 기록 5분류로 바꾼다. React 없음.
//
// 5분류는 서버 EventKind.section 한 표(`@opensamguk/ui` recordSection)만 쓴다 — 화면에서 다시 묶지 않는다(K4 · K5 합의).
// 짓지 않는 것: 리플레이 고리(REPLAY ref, K5-07 보강 전), 문장 생성(K4-03 다음 단계 GameEventDto — 지금은 서버 text 그대로),
// 「실행됨」 같은 일반 상태(알 수 있는 무효 · 응답 대기만 칩).

import { recordSection, type RecordSection } from '@opensamguk/ui';
import type { GamePhase, LastTurnEntry, LastTurns } from './campaign-reads';
import { phaseText } from './territory-view';

/** 5분류 — 서버 EventKind 표 그대로(옛 county.captured · lost 도 서버 표에 WORLD 로 남아 있다). 모르는 종류는 null(분류 칩 없음). */
export function entrySection(kind: string): RecordSection | null {
    return recordSection(kind);
}

const ordinal = (p: GamePhase) => p.year * 36 + (p.month - 1) * 3 + (p.phase - 1);
const fromOrdinal = (n: number): GamePhase => ({ year: Math.floor(n / 36), month: Math.floor((n % 36) / 3) + 1, phase: (n % 3) + 1 });

export type EntryStatus = 'invalid' | 'awaitingReply' | null;
export type EntryShortcut = 'reply' | 'yuedan' | 'county' | 'why';

export interface LastTurnItem {
    readonly key: string;
    readonly section: RecordSection | null;
    readonly text: string;
    readonly status: EntryStatus;
    readonly shortcuts: readonly EntryShortcut[];
    readonly countyId: number | null;
    readonly dispatchId: string | null;
}

export interface LastTurnGroup {
    readonly key: string;
    readonly when: string;
    readonly items: readonly LastTurnItem[];
}

const num = (v: unknown) => (typeof v === 'number' && Number.isFinite(v) ? v : null);
const str = (v: unknown) => (typeof v === 'string' && v ? v : null);

function item(e: LastTurnEntry, key: string, pendingDispatchIds: ReadonlySet<string>): LastTurnItem {
    const dispatchId = str(e.refs?.dispatchId);
    const countyId = num(e.refs?.countyId);
    const awaiting = e.kind === 'court.dispatchReceived' && dispatchId != null && pendingDispatchIds.has(dispatchId);
    const status: EntryStatus = e.kind === 'input.rejected' ? 'invalid' : awaiting ? 'awaitingReply' : null;
    const shortcuts: EntryShortcut[] = [];
    if (awaiting) shortcuts.push('reply');
    if (e.kind.startsWith('yuedan.')) shortcuts.push('yuedan');
    if (status === 'invalid') shortcuts.push('why');
    if (countyId != null) shortcuts.push('county');
    return { key, section: entrySection(e.kind), text: e.text, status, shortcuts, countyId, dispatchId };
}

export type LastTurnTab = 'mine' | 'nation' | 'all';
export const LAST_TURN_TAB_LABEL: Readonly<Record<LastTurnTab, string>> = { mine: '내 12순', nation: '부 · 세력', all: '전체' };

/**
 * 12순 묶음 — `now`(지금 순) 바로 앞 12순을 새것부터. 기록 없는 순도 묶음으로 남긴다(화면은 접은 한 줄 「— 기록 없음」).
 * 탭: 내 12순 = 내 기록(turns), 부 · 세력 = 세력 요약(nationSummary), 전체 = 둘 다. 분류 거르기는 section 으로.
 */
export function lastTurnGroups(data: LastTurns, now: GamePhase, tab: LastTurnTab, section: RecordSection | null,
    pendingDispatchIds: ReadonlySet<string> = new Set()): LastTurnGroup[] {
    const end = ordinal(now) - 1;
    const byPhase = new Map<number, LastTurnItem[]>();
    const push = (p: GamePhase, it: LastTurnItem) => {
        const k = ordinal(p);
        if (k > end || k <= end - 12) return;
        byPhase.set(k, [...(byPhase.get(k) ?? []), it]);
    };
    if (tab !== 'nation') data.turns.forEach((t, ti) => t.entries.forEach((e, ei) => push(t, item(e, `t${ti}-${ei}`, pendingDispatchIds))));
    if (tab !== 'mine') data.nationSummary.forEach((e, i) => push(e, item(e, `n${i}`, pendingDispatchIds)));
    const groups: LastTurnGroup[] = [];
    for (let k = end; k > end - 12; k -= 1) {
        const items = (byPhase.get(k) ?? []).filter((it) => section == null || it.section === section);
        groups.push({ key: String(k), when: phaseText(fromOrdinal(k)), items });
    }
    return groups;
}

/** 서랍 머리 부제 — 「200년 2월 상순 – 200년 3월 하순」. */
export function rangeText(now: GamePhase): string {
    const end = ordinal(now) - 1;
    return `${phaseText(fromOrdinal(end - 11))} – ${phaseText(fromOrdinal(end))}`;
}

/** 손잡이 · 칩의 새 기록 수 — 12순 안 전체 항목 수. */
export function entryCount(groups: readonly LastTurnGroup[]): number {
    return groups.reduce((n, g) => n + g.items.length, 0);
}
