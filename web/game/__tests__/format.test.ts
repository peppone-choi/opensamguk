import { describe, expect, it } from 'vitest';
import { formatTurn, formatYearMonthPhase } from '@/lib/format';

describe('format turn phases', () => {
    it('formats three ten-day phases per month and thirty-six turns per year', () => {
        expect(formatTurn(0)).toBe('184년 1월 상순');
        expect(formatTurn(1)).toBe('184년 1월 중순');
        expect(formatTurn(2)).toBe('184년 1월 하순');
        expect(formatTurn(3)).toBe('184년 2월 상순');
        expect(formatTurn(35)).toBe('184년 12월 하순');
        expect(formatTurn(36)).toBe('185년 1월 상순');
    });

    it('formats server year month phase text without a fixed fallback phase', () => {
        expect(formatYearMonthPhase(200, 3, '하순')).toBe('200년 3월 하순');
        expect(formatYearMonthPhase(200, 3, null)).toBe('200년 3월');
    });
});
