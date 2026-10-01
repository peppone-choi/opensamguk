// 계절 달력 순수 계산 — 봄 3–5 · 여름 6–8 · 가을 9–11 · 겨울 12–2, 1년 36순.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
    calendarCells,
    calendarSegments,
    hasSeasonNews,
    momentFrom,
    momentLabel,
    phaseIndex,
    seasonNow,
    seasonOf,
    seasonSpan,
} from '@/lib/season';

describe('seasonOf · seasonSpan', () => {
    it('달 → 계절(원장 확정값과 같은 경계)', () => {
        expect([1, 2, 3, 5, 6, 8, 9, 11, 12].map(seasonOf)).toEqual(['겨울', '겨울', '봄', '봄', '여름', '여름', '가을', '가을', '겨울']);
    });
    it('겨울은 해를 넘는다', () => {
        expect(seasonSpan(12)).toEqual({ name: '겨울', startMonth: 12, endMonth: 2 });
        expect(seasonSpan(1)).toEqual({ name: '겨울', startMonth: 12, endMonth: 2 });
        expect(seasonSpan(4)).toEqual({ name: '봄', startMonth: 3, endMonth: 5 });
    });
    it('범위 밖 달은 받지 않는다', () => {
        expect(() => seasonOf(0)).toThrow(RangeError);
        expect(() => seasonOf(13)).toThrow(RangeError);
    });
});

describe('momentFrom — 서버 값만', () => {
    it('month · turnPhase(1–3)가 둘 다 맞아야 지금 순이 된다', () => {
        expect(momentFrom(3, 2)).toEqual({ month: 3, phase: 2 });
        expect(momentFrom(3, null)).toBeNull();
        expect(momentFrom(undefined, 2)).toBeNull();
        expect(momentFrom(3, 0)).toBeNull();
        expect(momentFrom(3, 4)).toBeNull();
        expect(momentFrom(13, 1)).toBeNull();
        expect(momentFrom(2.5, 1)).toBeNull();
    });
    it('순 번호 · 글자', () => {
        expect(phaseIndex({ month: 1, phase: 1 })).toBe(0);
        expect(phaseIndex({ month: 12, phase: 3 })).toBe(35);
        expect(momentLabel({ month: 3, phase: 2 })).toBe('3월 중순');
    });
});

describe('seasonNow — 남은 순 · 다음 계절', () => {
    it('3월 중순: 봄, 5월 하순까지 7순(보드 V31SystemSeason 예시와 같다), 다음은 여름 6–8월', () => {
        expect(seasonNow({ month: 3, phase: 2 })).toEqual({
            season: '봄', endMonth: 5, remainingPhases: 7, next: { name: '여름', startMonth: 6, endMonth: 8 },
        });
    });
    it('계절 끝 순이면 0순 남음', () => {
        expect(seasonNow({ month: 5, phase: 3 }).remainingPhases).toBe(0);
    });
    it('12월 상순 겨울: 해를 넘어 2월 하순까지 8순, 다음은 봄', () => {
        const now = seasonNow({ month: 12, phase: 1 });
        expect(now.remainingPhases).toBe(8);
        expect(now.endMonth).toBe(2);
        expect(now.next).toEqual({ name: '봄', startMonth: 3, endMonth: 5 });
    });
    it('1월 상순 겨울: 2월 하순까지 5순', () => {
        expect(seasonNow({ month: 1, phase: 1 }).remainingPhases).toBe(5);
    });
});

describe('calendarSegments · calendarCells', () => {
    it('1월부터 겨울 · 봄 · 여름 · 가을 · 겨울 다섯 토막, 합이 12달', () => {
        const segs = calendarSegments();
        expect(segs.map((s) => s.name)).toEqual(['겨울', '봄', '여름', '가을', '겨울']);
        expect(segs.reduce((n, s) => n + s.endMonth - s.startMonth + 1, 0)).toBe(12);
    });
    it('36칸 — 지금 순 앞은 지난, 뒤는 남은', () => {
        const cells = calendarCells({ month: 3, phase: 2 });
        expect(cells).toHaveLength(36);
        expect(cells.filter((c) => c === 'now')).toHaveLength(1);
        expect(cells.indexOf('now')).toBe(7);
        expect(cells.slice(0, 7).every((c) => c === 'past')).toBe(true);
        expect(cells.slice(8).every((c) => c === 'future')).toBe(true);
    });
    it('지금 순을 모르면 아무 칸도 칠하지 않는다(짐작 금지)', () => {
        expect(calendarCells(null).every((c) => c === 'unknown')).toBe(true);
    });
    it('계절 소식 점은 서버 읽기(K8-08)가 오기 전엔 없다', () => {
        expect(hasSeasonNews()).toBe(false);
    });
});

// 표류 검사 — 계절 경계는 서버 확정값(data/curated/han/world-event-values.json 「season-calendar」, 서버 SeasonCalendar.seasonForMonth)과 같아야 한다.
describe('계절 경계 = 서버 원장 확정값', () => {
    const ledger = JSON.parse(readFileSync(resolve(__dirname, '../../../data/curated/han/world-event-values.json'), 'utf8')) as {
        rows: Array<{ id: string; values?: Record<string, { value: number; status: string }> }>;
    };
    const cal = ledger.rows.find((r) => r.id === 'season-calendar');
    const starts: Array<[string, '봄' | '여름' | '가을' | '겨울']> = [
        ['springStartMonth', '봄'], ['summerStartMonth', '여름'], ['autumnStartMonth', '가을'], ['winterStartMonth', '겨울'],
    ];
    it.each(starts)('%s 가 %s 의 첫 달이다(그 전 달은 다른 계절)', (key, name) => {
        const v = cal?.values?.[key];
        expect(v?.status).toBe('CONFIRMED');
        const start = v!.value;
        expect(seasonOf(start)).toBe(name);
        expect(seasonOf(start === 1 ? 12 : start - 1)).not.toBe(name);
        expect(seasonSpan(start).startMonth).toBe(start);
    });
});
