export function formatNumber(n: number | null | undefined): string {
    // 방어: undefined/null/NaN(shape mismatch·미배선 필드)에 toLocaleString 호출 시 런타임 크래시 방지 → '-'.
    if (n == null || Number.isNaN(n)) return '-';
    return n.toLocaleString('ko-KR');
}

export function formatRemaining(closeDate: string, nowMs: number): string {
    const diff = new Date(closeDate).getTime() - nowMs;
    if (diff <= 0) return '마감';
    const h = Math.floor(diff / 3600000);
    const m = Math.floor((diff % 3600000) / 60000);
    const s = Math.floor((diff % 60000) / 1000);
    if (h > 0) return `${h}시간 ${m}분`;
    return `${m}분 ${s}초`;
}

export function formatDate(date: string): string {
    return new Date(date).toLocaleString('ko-KR', {
        year: 'numeric',
        month: '2-digit',
        day: '2-digit',
        hour: '2-digit',
        minute: '2-digit',
    });
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
