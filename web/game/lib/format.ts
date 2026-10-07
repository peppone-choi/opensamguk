export function formatNumber(n: number | null | undefined): string {
    // 방어: undefined/null/NaN(shape mismatch·미배선 필드)에 toLocaleString 호출 시 런타임 크래시 방지 → '-'.
    if (n == null || Number.isNaN(n)) return '-';
    return n.toLocaleString('ko-KR');
}

export const TURN_PHASE_LABELS = ['상순', '중순', '하순'] as const;
