// @vitest-environment node
// C7 #1098 검토 권장(2026-10-01): 선택 역할 FROM_NATION · 한 응답에 공개/비공개가 섞인 경우 · 값 경계.
import { describe, expect, it } from 'vitest';
import { publicWorldEvents } from '@/lib/publicFeeds';

const at = { year: 200, month: 3, phase: 2, ordinal: 0 };
const owner = (refs: Record<string, unknown>, over: Record<string, unknown> = {}) =>
    ({ id: 1, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: at, refs, facts: {}, ...over });
const kept = (event: unknown) => (publicWorldEvents({ events: [event] }, 5)?.events.length ?? -1) === 1;

describe('선택 역할 FROM_NATION(보루 차지)', () => {
    it('있어도 없어도 남는다 · 세력 0 도 정상', () => {
        const base = { id: 2, kind: 'roadFort.captured', section: 'WORLD', occurredAt: at, facts: {} };
        expect(kept({ ...base, refs: { ROAD_FORT: 'fort-hulao', TO_NATION: 3 } })).toBe(true);
        expect(kept({ ...base, refs: { ROAD_FORT: 'fort-hulao', FROM_NATION: 0, TO_NATION: 3 } })).toBe(true);
        expect(kept({ ...base, refs: { ROAD_FORT: 'fort-hulao', FROM_NATION: 2 } })).toBe(false); // 필수 TO_NATION 없음
    });

    it('선택 역할 FROM_NATION: 0 은 버리지 않고 그대로 낸다(C8 #1098 권장)', () => {
        const base = { id: 2, kind: 'roadFort.captured', section: 'WORLD', occurredAt: at, facts: {} };
        expect(publicWorldEvents({ events: [{ ...base, refs: { ROAD_FORT: 'fort-hulao', FROM_NATION: 0, TO_NATION: 3 } }] }, 5)?.events[0].refs)
            .toEqual({ ROAD_FORT: 'fort-hulao', FROM_NATION: 0, TO_NATION: 3 });
        expect(publicWorldEvents({ events: [{ ...base, refs: { ROAD_FORT: 'fort-hulao', TO_NATION: 3 } }] }, 5)?.events[0].refs)
            .toEqual({ ROAD_FORT: 'fort-hulao', TO_NATION: 3 });
    });
});

describe('한 응답에 공개 · 비공개가 섞이면 공개만 남는다', () => {
    it('WORLD 공개 사건 둘 사이의 개인 · 조정 · 전장 사건은 빠진다', () => {
        const body = publicWorldEvents({ events: [
            owner({ CITY: 12, FROM_NATION: 2, TO_NATION: 1 }, { id: 10 }),
            { id: 11, kind: 'renown.event', section: 'PERSONAL', occurredAt: at, refs: { ACTOR: 7 }, facts: {} },
            { id: 12, kind: 'dispatch.received', section: 'COURT', occurredAt: at, refs: { ISSUER: 3 }, facts: {} },
            { id: 13, kind: 'battle.opened', section: 'BATTLE', occurredAt: at, refs: { CITY: 12 }, facts: {} },
            { id: 14, kind: 'yuedan.announced', section: 'WORLD', occurredAt: at, refs: {}, facts: {} },
        ] }, 5);
        expect(body?.events.map((event) => event.id)).toEqual([10, 14]);
    });
});

describe('값 경계', () => {
    it.each([
        ['year 1', { occurredAt: { ...at, year: 1 } }, true], ['year 9999', { occurredAt: { ...at, year: 9999 } }, true],
        ['year 0', { occurredAt: { ...at, year: 0 } }, false], ['year 10000', { occurredAt: { ...at, year: 10000 } }, false],
        ['month 1', { occurredAt: { ...at, month: 1 } }, true], ['month 12', { occurredAt: { ...at, month: 12 } }, true],
        ['month 0', { occurredAt: { ...at, month: 0 } }, false], ['month 13', { occurredAt: { ...at, month: 13 } }, false],
        ['phase 1', { occurredAt: { ...at, phase: 1 } }, true], ['phase 3', { occurredAt: { ...at, phase: 3 } }, true],
        ['phase 0', { occurredAt: { ...at, phase: 0 } }, false], ['phase 4', { occurredAt: { ...at, phase: 4 } }, false],
        ['ordinal 0', { occurredAt: { ...at, ordinal: 0 } }, true], ['ordinal -1', { occurredAt: { ...at, ordinal: -1 } }, false],
        ['year 소수', { occurredAt: { ...at, year: 200.5 } }, false],
        ['id 최대 안전 정수', { id: Number.MAX_SAFE_INTEGER }, true], ['id 안전 정수 초과', { id: Number.MAX_SAFE_INTEGER + 1 }, false],
        ['id 0', { id: 0 }, false],
    ] as const)('%s', (_label, over, expected) => {
        expect(kept(owner({ CITY: 12, FROM_NATION: 0, TO_NATION: 1 }, over as Record<string, unknown>))).toBe(expected);
    });

    it.each([
        ['CITY 1', { CITY: 1, FROM_NATION: 0, TO_NATION: 1 }, true], ['CITY 0', { CITY: 0, FROM_NATION: 0, TO_NATION: 1 }, false],
        ['세력 0 양쪽', { CITY: 1, FROM_NATION: 0, TO_NATION: 0 }, true], ['세력 -1', { CITY: 1, FROM_NATION: -1, TO_NATION: 1 }, false],
        ['세력 문자열', { CITY: 1, FROM_NATION: '2', TO_NATION: 1 }, false],
    ] as const)('%s', (_label, refs, expected) => {
        expect(kept(owner(refs))).toBe(expected);
    });

    it.each([
        ['보루 128자', 'a'.repeat(128), true], ['보루 129자', 'a'.repeat(129), false],
        ['보루 앞 글자 기호', '-fort', false], ['보루 허용 기호', 'fort_1.a:b-c', true], ['보루 빈 글자', '', false],
    ] as const)('%s', (_label, fort, expected) => {
        expect(kept({ id: 3, kind: 'roadFort.captured', section: 'WORLD', occurredAt: at, refs: { ROAD_FORT: fort, TO_NATION: 1 }, facts: {} })).toBe(expected);
    });
});

// 실제 보루 ID 는 RoadFort.siteId(edgeId, row, col) = "$edgeId@$row,$col"(logic/.../input/RoadFortState.kt:29, C0 07:55 「실제 보루 ID 계약 정정」).
// edge 부분만 안정 문법 · 128자 한도, 좌표는 음 아닌 정규 십진 · Kotlin Int 범위. 전체 길이에는 한도를 걸지 않는다.
describe('보루 site ID(edge@row,col)', () => {
    const fort = (id: unknown) => kept({ id: 3, kind: 'roadFort.captured', section: 'WORLD', occurredAt: at, refs: { ROAD_FORT: id, TO_NATION: 1 }, facts: {} });
    // han-land-roads-v1.json 의 실제 도로 edge(4,252개 모두 안정 문법 안)에서 가운데 것과 가장 긴 것(112자).
    const realEdge = 'land-boundary:5:419355:43696';
    const longestEdge = 'land-boundary:46:SUB-JURISDICTION-PARENT-0097-SEAT-bfcd2758df5246:SUB-JURISDICTION-PARENT-0097-SEAT-e4ac85458411';

    it('실측 최장 edge 는 112자다(표본이 바뀌면 이 시험이 알린다)', () => {
        expect(longestEdge).toHaveLength(112);
    });

    it.each([
        ['단순 site', 'edge@1,2'],
        ['좌표 0', 'edge@0,0'],
        ['실제 edge site', `${realEdge}@12,34`],
        ['최장 112자 edge + 좌표', `${longestEdge}@431,1187`],
        ['edge 128자 + Int 최대 좌표(전체 150자)', `${'a'.repeat(128)}@2147483647,2147483647`],
        ['@ 없는 안정 ID', 'fort-hulao'],
    ] as const)('남는다: %s', (_label, id) => {
        expect(fort(id)).toBe(true);
    });

    it.each([
        ['음수', 'a@-1,2'], ['앞자리 0', 'a@01,2'], ['부호 +', 'a@+1,2'], ['공백', 'a@1, 2'], ['끝 공백', 'a@1,2 '],
        ['@ 두 개', 'a@1,2@3'], ['col 없음', 'a@1'], ['좌표 셋', 'a@1,2,3'], ['빈 row', 'a@,2'], ['빈 col', 'a@1,'],
        ['row Int 초과', 'a@2147483648,0'], ['col Int 초과', 'a@0,2147483648'], ['아주 긴 수', 'a@99999999999999999999,0'],
        ['빈 edge', '@1,2'], ['edge 앞 글자 기호', '-a@1,2'], ['edge 129자', `${'a'.repeat(129)}@1,2`], ['edge 안 쉼표', 'a,b@1,2'],
        ['끝 줄바꿈', 'a@1,2\n'], ['숫자 값', 12],
    ] as const)('버린다: %s', (_label, id) => {
        expect(fort(id)).toBe(false);
    });
});
