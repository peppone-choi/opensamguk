// 계절 사건 보기 모델(P-K07, K8) — 이름은 원장 확정값, 효과는 방향만, 모르는 것은 짐작하지 않는다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { countyEvents, effectMarks, effectMarkText, SEASON_EVENT_LABEL, SEASON_EVENT_LABELS, seasonEventLabel, type SeasonEventsState } from '@/lib/season-events';

// 표류 검사 — 종류 · 이름은 data/curated/han/seasonal-events.json(CONFIRMED, 게임 용어)과 같아야 한다.
describe('종류 이름 = 원장 확정값', () => {
    const ledger = JSON.parse(readFileSync(resolve(__dirname, '../../../data/curated/han/seasonal-events.json'), 'utf8')) as {
        rows: Array<{ kind: string; label: string; status: string }>;
    };
    it('종류 집합과 이름이 원장과 같다(순서까지)', () => {
        expect(ledger.rows.every((r) => r.status === 'CONFIRMED')).toBe(true);
        expect(Object.entries(SEASON_EVENT_LABEL)).toEqual(ledger.rows.map((r) => [r.kind, r.label]));
        expect(SEASON_EVENT_LABELS).toBe(ledger.rows.map((r) => r.label).join(' · '));
    });
});

describe('seasonEventLabel', () => {
    it('아는 종류는 원장 이름, 모르는 종류는 「계절 사건」(짐작하지 않는다)', () => {
        expect(seasonEventLabel('LOCUST')).toBe('황충');
        expect(seasonEventLabel('RAINY_PASSAGE')).toBe('우기 통행');
        expect(seasonEventLabel('TYPHOON')).toBe('계절 사건');
    });
});

describe('effectMarks — 방향만(보드 「▼ 민심 등」)', () => {
    it('민심 · 호구 · 전답 · 유민 · 길 순서, 음수는 ▼ · 양수는 ▲, 0 · 빈 칸은 그리지 않는다', () => {
        const marks = effectMarks({ trust: -2, population: -10, agriculture: 0, displaced: 5, passageClosed: true });
        expect(marks.map(effectMarkText)).toEqual(['▼ 민심', '▼ 호구', '▲ 유민', '길 닫힘']);
        expect(effectMarks({}).length).toBe(0);
        expect(effectMarks({ agriculture: -3, passageClosed: false }).map(effectMarkText)).toEqual(['▼ 전답']);
    });
    it('수치는 글자에 나오지 않는다', () => {
        const text = effectMarks({ trust: -7, population: -120 }).map(effectMarkText).join(' ');
        expect(text).not.toMatch(/\d/);
    });
    it('숫자가 아닌 값은 버린다', () => {
        expect(effectMarks({ trust: Number.NaN, population: undefined }).length).toBe(0);
    });
});

describe('countyEvents — 이 현의 사건만', () => {
    const ready: SeasonEventsState = {
        kind: 'ready',
        occurrences: [
            { countyId: 11, kind: 'DROUGHT', effect: { trust: -2 } },
            { countyId: 12, kind: 'FLOOD', effect: { population: -5 } },
            { countyId: 11, kind: 'LOCUST', effect: { agriculture: -4 } },
        ],
    };
    it('읽기 없음 · 셈하지 못함 · 현을 모름이면 빈 목록', () => {
        expect(countyEvents(undefined, 11)).toEqual([]);
        expect(countyEvents({ kind: 'unavailable' }, 11)).toEqual([]);
        expect(countyEvents(ready, null)).toEqual([]);
    });
    it('그 현 것만 받은 순서대로', () => {
        expect(countyEvents(ready, 11).map((o) => o.kind)).toEqual(['DROUGHT', 'LOCUST']);
        expect(countyEvents(ready, 13)).toEqual([]);
    });
});
