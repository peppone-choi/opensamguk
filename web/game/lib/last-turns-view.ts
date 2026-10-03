// 지난 순 서랍(P-W04)의 보기 모델. `/api/last-turns`(내 12순 · 세력 요약)를 서랍 줄로 바꾼다. React 없음.
// 분류는 서버 EventKind.section 정본(공용 recordSection — K4 · K5 합의)을 그대로 쓴다. 화면에서 다시 묶지 않는다.
import { eventKindLabel, recordSection, RECORD_SECTION_LABEL, RECORD_SECTION_ORDER, type ChipTone, type RecordSection } from '@opensamguk/ui';
import type { LastTurnEntry, LastTurns } from './campaign-reads';

/** 범위 탭 — 내 12순(순마다 묶음) · 부 · 세력(세력 요약) · 전체(둘을 날짜순으로 합침). */
export type FeedScope = 'MINE' | 'NATION' | 'ALL';
export const FEED_SCOPES: readonly { readonly value: FeedScope; readonly label: string }[] = [
    { value: 'MINE', label: '내 12순' }, { value: 'NATION', label: '부 · 세력' }, { value: 'ALL', label: '전체' },
];

/** 분류 거르기 — 전체 + 5분류(보드 짧은 이름). */
export type FeedFilter = 'ALL' | RecordSection;
const SHORT: Readonly<Record<RecordSection, string>> = { PERSONAL: '개인', RETINUE_NATION: '부 · 세력', COURT: '조정', BATTLE: '전장', WORLD: '천하' };
export const FEED_FILTERS: readonly { readonly value: FeedFilter; readonly label: string }[] = [
    { value: 'ALL', label: '전체' }, ...RECORD_SECTION_ORDER.map((s) => ({ value: s, label: SHORT[s] })),
];

export interface FeedHrefs {
    readonly court: string;
    readonly yuedan: string;
}

export interface FeedItem {
    readonly key: string;
    /** 모르는 종류는 null — 분류 칩을 달지 않고 「전체」에서만 보인다(지어 넣지 않는다). */
    readonly section: RecordSection | null;
    readonly sectionLabel: string | null;
    readonly title: string;
    readonly text: string;
    /** 「3월 상순」 — 묶음 밖(부 · 세력 · 전체)에서 제목 뒤에 붙인다. */
    readonly when: string;
    readonly status: { readonly label: string; readonly tone: ChipTone } | null;
    readonly action: { readonly label: string; readonly href: string } | null;
    /** 전장 보고 — 서버가 refs · facts 를 지워 누구 · 어디 · 리플레이를 못 싣는다(EventFeedReader, K5-07 보강 대기). */
    readonly battleDetailPending: boolean;
}

export interface FeedTurn {
    readonly key: string;
    /** 「200년 3월 상순」. */
    readonly label: string;
    /** 「3월 상순」 — 빈 순 접은 한 줄에 쓴다. */
    readonly short: string;
    readonly items: readonly FeedItem[];
}

interface Dated { readonly year: number; readonly month: number; readonly phase: number; readonly phaseLabel: string }

const YUEDAN_KINDS = new Set(['yuedan.assessed', 'yuedan.announced', 'renown.event', 'retinue.departureJudged', 'retinue.departed']);

function feedItem(entry: LastTurnEntry, at: Dated, key: string, hrefs: FeedHrefs): FeedItem {
    const section = recordSection(entry.kind);
    const action = section === 'COURT' ? { label: '조정에서 보기 →', href: hrefs.court }
        : YUEDAN_KINDS.has(entry.kind) ? { label: '월단평 열기 →', href: hrefs.yuedan }
        : null;
    return {
        key,
        section,
        sectionLabel: section ? RECORD_SECTION_LABEL[section] : null,
        title: eventKindLabel(entry.kind) ?? '기록',
        text: entry.text,
        when: `${at.month}월 ${at.phaseLabel}`,
        status: entry.kind === 'input.rejected' ? { label: '무효', tone: 'rust' } : null,
        action,
        battleDetailPending: section === 'BATTLE',
    };
}

const pass = (filter: FeedFilter) => (item: FeedItem) => filter === 'ALL' || item.section === filter;

/** 내 12순 — 서버 순서(최근 순부터) 그대로, 빈 순도 한 묶음(화면은 접은 한 줄로). */
export function myTurns(data: LastTurns, filter: FeedFilter, hrefs: FeedHrefs): readonly FeedTurn[] {
    return data.turns.map((t) => {
        const key = `${t.year}-${t.month}-${t.phase}`;
        return {
            key,
            label: `${t.year}년 ${t.month}월 ${t.phaseLabel}`,
            short: `${t.month}월 ${t.phaseLabel}`,
            items: t.entries.map((e, i) => feedItem(e, t, `m${key}-${i}`, hrefs)).filter(pass(filter)),
        };
    });
}

/** 부 · 세력 — 세력 요약(본인 세력 현 점령 · 상실, 월단평 발표). 옛 패널이 버리던 날짜를 붙인다. */
export function nationItems(data: LastTurns, filter: FeedFilter, hrefs: FeedHrefs): readonly FeedItem[] {
    return data.nationSummary.map((e, i) => feedItem(e, e, `n${e.year}-${e.month}-${e.phase}-${i}`, hrefs)).filter(pass(filter));
}

const order = (a: Dated) => a.year * 100 + a.month * 4 + a.phase;

/** 전체 — 내 기록과 세력 요약을 최근 순부터 합친다(같은 순이면 내 기록 먼저). */
export function allItems(data: LastTurns, filter: FeedFilter, hrefs: FeedHrefs): readonly FeedItem[] {
    const mine = data.turns.flatMap((t) => t.entries.map((e, i) => ({ at: order(t), mine: 0, item: feedItem(e, t, `m${t.year}-${t.month}-${t.phase}-${i}`, hrefs) })));
    const nation = data.nationSummary.map((e, i) => ({ at: order(e), mine: 1, item: feedItem(e, e, `n${e.year}-${e.month}-${e.phase}-${i}`, hrefs) }));
    return [...mine, ...nation].sort((a, b) => b.at - a.at || a.mine - b.mine).map((r) => r.item).filter(pass(filter));
}

/** 머리 부제 「200년 2월 하순 – 3월 중순」 — 받은 순의 처음과 끝. 순이 없으면 null. */
export function rangeText(data: LastTurns): string | null {
    const turns = data.turns;
    if (turns.length === 0) return null;
    const newest = turns[0];
    const oldest = turns[turns.length - 1];
    const from = `${oldest.year}년 ${oldest.month}월 ${oldest.phaseLabel}`;
    if (turns.length === 1) return from;
    // 같은 해면 해를, 같은 달이면 달까지 뺀다(보드 「200년 3월 상순 – 중순」).
    const to = newest.year !== oldest.year ? `${newest.year}년 ${newest.month}월 ${newest.phaseLabel}`
        : newest.month !== oldest.month ? `${newest.month}월 ${newest.phaseLabel}` : newest.phaseLabel;
    return `${from} – ${to}`;
}

/**
 * 손잡이 칩 「새 기록 n」 — 지금 순(서버 turns[0], 아직 진행 중)과 방금 끝난 순(turns[1])의 내 기록 수.
 * 서버는 창을 지금 세계 순부터 거꾸로 만들고, 엔진은 기록을 그 순 날짜로 찍는다. 그래서 방금 끝난 순의 결과는 대개 turns[1] 에 있다(#1218 리뷰).
 */
export function recentCount(data: LastTurns): number {
    return data.turns.slice(0, 2).reduce((n, t) => n + t.entries.length, 0);
}
