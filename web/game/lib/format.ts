export function formatNumber(n: number | null | undefined): string {
    // 방어: undefined/null/NaN(shape mismatch·미배선 필드)에 toLocaleString 호출 시 런타임 크래시 방지 → '-'.
    if (n == null || Number.isNaN(n)) return '-';
    return n.toLocaleString('ko-KR');
}

export const TURN_PHASE_LABELS = ['상순', '중순', '하순'] as const;

export function formatYearMonthPhase(
    year: number | null | undefined,
    month: number | null | undefined,
    phaseText?: string | null,
): string {
    const y = year ?? 0;
    const m = month ?? 0;
    return `${y}년 ${m}월${phaseText ? ` ${phaseText}` : ''}`;
}

export function formatTurn(turn: number): string {
    const year = Math.floor(turn / 36) + 184;
    const turnOfYear = ((turn % 36) + 36) % 36;
    const month = Math.floor(turnOfYear / 3) + 1;
    const phase = TURN_PHASE_LABELS[turnOfYear % 3];
    return `${year}년 ${month}월 ${phase}`;
}
