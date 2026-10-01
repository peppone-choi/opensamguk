// 계절 달력 — 머리줄 계절 패널(설계 보드 V31SystemSeason · MSeason, 내용 K8 설계서 P-K07).
//
// 1년 36순 · 1달 3순, 봄 3–5 · 여름 6–8 · 가을 9–11 · 겨울 12–2(docs/design/external-world-season-events.md 「계절」,
// data/curated/han/world-event-values.json 확정값). 달력의 틀은 고정이라 서버 없이 그린다.
// 「지금 몇 월 몇 순」은 서버 값(front-info global.month · turnPhase)만 쓴다 — 값이 없으면 null 이고 짐작하지 않는다.

export type SeasonName = '봄' | '여름' | '가을' | '겨울';

export const PHASES_PER_MONTH = 3;
export const PHASES_PER_YEAR = 36;
export const PHASE_LABEL = ['상순', '중순', '하순'] as const;

/** 달(1–12) → 계절. 인덱스 0 = 1월. */
const SEASON_OF_MONTH: readonly SeasonName[] = ['겨울', '겨울', '봄', '봄', '봄', '여름', '여름', '여름', '가을', '가을', '가을', '겨울'];

/** 서버가 준 달이 1–12 정수인가. 아니면 계절을 짐작하지 않는다. */
export function isGameMonth(v: unknown): v is number {
    return typeof v === 'number' && Number.isInteger(v) && v >= 1 && v <= 12;
}

/** 달 → 계절. 셸 머리줄 칩(GameFrame)과 계절 패널이 이 한 곳을 쓴다. */
export function seasonOf(month: number): SeasonName {
    if (!isGameMonth(month)) throw new RangeError(`month ${month}`);
    return SEASON_OF_MONTH[month - 1];
}

const nextMonth = (m: number) => (m % 12) + 1;
const prevMonth = (m: number) => ((m + 10) % 12) + 1;

/** 서버가 준 지금 순. 둘 중 하나라도 없거나 범위 밖이면 null(「확인 중」). */
export interface GameMoment {
    readonly month: number;
    /** 1 상순 · 2 중순 · 3 하순. */
    readonly phase: 1 | 2 | 3;
}

export function momentFrom(month: number | null | undefined, phase: number | null | undefined): GameMoment | null {
    if (!isGameMonth(month)) return null;
    if (phase !== 1 && phase !== 2 && phase !== 3) return null;
    return { month, phase };
}

/** 1월 상순 = 0 … 12월 하순 = 35. */
export function phaseIndex(m: GameMoment): number {
    return (m.month - 1) * PHASES_PER_MONTH + (m.phase - 1);
}

export function momentLabel(m: GameMoment): string {
    return `${m.month}월 ${PHASE_LABEL[m.phase - 1]}`;
}

/** 이 달이 든 계절의 첫 달 · 끝 달(겨울은 해를 넘는다: 12 → 2). */
export function seasonSpan(month: number): { readonly name: SeasonName; readonly startMonth: number; readonly endMonth: number } {
    const name = seasonOf(month);
    let start = month;
    while (seasonOf(prevMonth(start)) === name && prevMonth(start) !== month) start = prevMonth(start);
    let end = month;
    while (seasonOf(nextMonth(end)) === name && nextMonth(end) !== month) end = nextMonth(end);
    return { name, startMonth: start, endMonth: end };
}

/** 달력 머리 — 1월부터 12월까지 계절 띠(겨울은 앞 · 뒤 두 토막). */
export interface SeasonSegment {
    readonly name: SeasonName;
    readonly startMonth: number;
    readonly endMonth: number;
}

export function calendarSegments(): readonly SeasonSegment[] {
    const out: SeasonSegment[] = [];
    for (let m = 1; m <= 12; m++) {
        const name = seasonOf(m);
        const last = out[out.length - 1];
        if (last && last.name === name && last.endMonth === m - 1) out[out.length - 1] = { ...last, endMonth: m };
        else out.push({ name, startMonth: m, endMonth: m });
    }
    return out;
}

export interface SeasonNow {
    readonly season: SeasonName;
    readonly endMonth: number;
    /** 지금 순을 빼고 이 계절 끝 순(끝 달 하순)까지 남은 순. */
    readonly remainingPhases: number;
    readonly next: { readonly name: SeasonName; readonly startMonth: number; readonly endMonth: number };
}

export function seasonNow(m: GameMoment): SeasonNow {
    const span = seasonSpan(m.month);
    const now = phaseIndex(m);
    const endIndex = (span.endMonth - 1) * PHASES_PER_MONTH + (PHASES_PER_MONTH - 1);
    const remainingPhases = endIndex >= now ? endIndex - now : endIndex + PHASES_PER_YEAR - now;
    const nextSpan = seasonSpan(nextMonth(span.endMonth));
    return { season: span.name, endMonth: span.endMonth, remainingPhases, next: nextSpan };
}

/** 달력 칸 36개의 상태 — 지금 순을 모르면 모두 'unknown'(지난 · 지금을 칠하지 않는다). */
export type CalendarCell = 'past' | 'now' | 'future' | 'unknown';

export function calendarCells(m: GameMoment | null): readonly CalendarCell[] {
    const now = m ? phaseIndex(m) : -1;
    return Array.from({ length: PHASES_PER_YEAR }, (_, i) => (m === null ? 'unknown' : i < now ? 'past' : i === now ? 'now' : 'future'));
}

/** 계절 소식 점 — 닫힌 길 · 내 영지 계절 사건 읽기(계약판 K8-08, C5)가 오기 전엔 늘 false. */
export function hasSeasonNews(): boolean {
    return false;
}
