// 보루 지명 — 서버 이름만 쓰고, 없으면 내부 id 대신 고정 문구. 고르는 값 · 거르기는 그대로인지.
import { describe, expect, it } from 'vitest';
import type { RoadFort, RoadForts } from '../lib/campaign-reads';
import { buildArgs, fromRoadForts, type CommandOptions } from '../lib/command-flow/options';
import { roadFortLabel } from '../lib/road-fort-label-view';

const FALLBACK = '보루 · 장소 이름 확인 불가';

const fort = (id: string, provinceId: string, provinceName: string | null | undefined, canBesiege = true): RoadFort => ({
    id, edgeId: `E-${id}`, provinceId, ...(provinceName === undefined ? {} : { provinceName }),
    row: 0, col: 0, ownerNationId: 2, wall: 120, garrison: 45, besiegerGeneralId: null, siegeProgress: 0, canBesiege,
});
const read = (forts: RoadFort[]): RoadForts => ({ status: 'READY', roadMode: true, forts, gates: [] });
const readyOf = (o: CommandOptions) => {
    if (o.state !== 'READY') throw new Error(`not ready: ${o.state}`);
    return o;
};

describe('roadFortLabel', () => {
    it('서버 이름의 앞뒤 공백을 걷어 사람 지명으로 쓴다', () => {
        expect(roadFortLabel({ provinceName: '  호뢰관 ' })).toBe('보루 · 호뢰관');
    });
    it.each([['없음', undefined], ['null', null], ['빈 문자열', ''], ['공백뿐', ' \t ']] as const)(
        '이름 %s이면 고정 문구로 둔다', (_, provinceName) => {
            expect(roadFortLabel(provinceName === undefined ? {} : { provinceName })).toBe(FALLBACK);
        });
});

describe('fromRoadForts 보루 후보', () => {
    it('이름이 없어도 내부 구역 id를 이름처럼 보이지 않는다', () => {
        const o = readyOf(fromRoadForts(read([fort('F-1', 'P-77', undefined), fort('F-2', 'P-78', null), fort('F-3', 'P-79', '  ')])));
        const labels = o.fields[0].candidates.map(c => c.label);
        expect(labels).toEqual([FALLBACK, FALLBACK, FALLBACK]);
        for (const label of labels) expect(label).not.toMatch(/P-7\d/);
    });

    it('같은 이름의 다른 보루는 fort.id로 따로 고르고 보내는 fortId도 각자다', () => {
        const o = readyOf(fromRoadForts(read([fort('F-1', 'P-1', '하비'), fort('F-2', 'P-2', '하비 ')])));
        const [field] = o.fields;
        expect(field).toMatchObject({ key: 'fortId', kind: 'choice' });
        expect(field.candidates.map(c => [c.value, c.label])).toEqual([['F-1', '보루 · 하비'], ['F-2', '보루 · 하비']]);
        expect(buildArgs(o, { fortId: 'F-2' })).toEqual({ ok: true, args: { fortId: 'F-2' } });
        expect(buildArgs(o, { fortId: 'F-1' })).toEqual({ ok: true, args: { fortId: 'F-1' } });
    });

    it('성벽 · 수비 표기와 에울 수 없는 보루 거르기는 그대로다', () => {
        const o = readyOf(fromRoadForts(read([fort('F-1', 'P-1', '소패'), fort('F-2', 'P-2', '하비', false)])));
        expect(o.available).toBe(true);
        expect(o.fields[0].candidates).toEqual([
            { value: 'F-1', label: '보루 · 소패', available: true, reason: null, detail: '성벽 120 · 수비 45' },
        ]);
        expect(buildArgs(o, { fortId: 'F-2' })).toEqual({ ok: false, missing: ['fortId'] });
    });

    it('이름은 있어도 에울 보루가 없으면 막힘 사유를 쓴다', () => {
        const o = readyOf(fromRoadForts(read([fort('F-1', 'P-1', '소패', false)])));
        expect(o).toMatchObject({ available: false, reason: '에울 수 있는 보루가 없습니다' });
        expect(o.fields[0].candidates).toEqual([]);
    });

    it('읽기가 준비되지 않으면 이름과 무관하게 읽을 수 없음으로 둔다', () => {
        expect(fromRoadForts({ ...read([fort('F-1', 'P-1', '소패')]), status: 'RULESET_DISABLED' }))
            .toEqual({ state: 'UNREADABLE', status: 'RULESET_DISABLED' });
    });
});
