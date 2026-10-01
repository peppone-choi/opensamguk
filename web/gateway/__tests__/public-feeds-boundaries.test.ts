// @vitest-environment node
// C7 #1098 검토 권장(2026-10-01): 선택 역할 FROM_NATION · 한 응답에 공개/비공개가 섞인 경우 · 값 경계.
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import { publicWorldEvents } from '@/lib/publicFeeds';

const at = { year: 200, month: 3, phase: 2, ordinal: 0 };
const owner = (refs: Record<string, unknown>, over: Record<string, unknown> = {}) =>
    ({ id: 1, kind: 'county.ownerChanged', section: 'WORLD', occurredAt: at, refs, facts: {}, ...over });
// C8 보루 참조 계약 51건(meta reports/opensamguk/evidence/2026-10-01-c8-roadfort-ref-contract/contract-fixtures.json 그대로).
const roadFortContract = JSON.parse(readFileSync(join(__dirname, 'fixtures', 'c8-roadfort-ref-contract.json'), 'utf8')) as { value: unknown; accepted: boolean }[];
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

// 보루 참조 계약(C8 2026-10-01, meta reports/opensamguk/tasks/2026-10-01-c8-roadfort-ref-contract.md · C0 08:06 고정).
// 서버 보루 ID 는 RoadFort.siteId(edgeId, row, col) = "$edgeId@$row,$col", legacy 안정 ID 도 받는다.
// edge · legacy 128자, row · col 은 0 또는 [1-9][0-9]{0,9} 이고 Int 이하, site 전체 150자 이하. 원문은 고치지 않는다.
describe('보루 참조(ROAD_FORT) 계약', () => {
    const captured = (refs: Record<string, unknown>, over: Record<string, unknown> = {}) =>
        ({ id: 3, kind: 'roadFort.captured', section: 'WORLD', occurredAt: at, refs, facts: {}, ...over });
    const fort = (id: unknown) => kept(captured({ ROAD_FORT: id, TO_NATION: 1 }));

    // han-land-roads-v1.json edges[].id 4,241개 중 최장(112자)과, 그 edge 의 fortCells 로 만든 최장 site(121자) · 가장 큰 col(2985) site.
    const longestEdge = 'land-boundary:46:SUB-JURISDICTION-PARENT-0097-SEAT-bfcd2758df5246:SUB-JURISDICTION-PARENT-0097-SEAT-e4ac85458411';
    const longestSite = `${longestEdge}@204,2126`;
    const widestSite = 'land-boundary:21:SUB-X039-47461cb1eca521:SUB-X039-b3708f5c90ac@88,2985';
    const nativeSite = 'land-boundary:5:9512612:gc-g0079-001@1195,1837'; // C3 RoadFortEventRefTest 의 정상 site
    const longestCell = `${'e'.repeat(128)}@2147483647,2147483647`;

    it('실측 표본 길이(C8 전수: edge 최장 112 · site 최장 121)', () => {
        expect(longestEdge).toHaveLength(112);
        expect(longestSite).toHaveLength(121);
        expect(longestCell).toHaveLength(150);
    });

    describe('C8 51 fixture(계약 문서와 같은 파일 — sha256 56b5e07d…)', () => {
        it('51건 · 받는 것 6건', () => {
            expect(roadFortContract).toHaveLength(51);
            expect(roadFortContract.filter((fixture) => fixture.accepted)).toHaveLength(6);
        });
        it.each(roadFortContract.map((fixture) => [JSON.stringify(fixture.value), fixture.value, fixture.accepted] as const))('%s', (_label, value, accepted) => {
            expect(fort(value)).toBe(accepted);
        });
    });

    describe('C3 native 시험과 같은 문자열', () => {
        it.each([
            'road-piece@-1,0', 'road-piece@-0,0', 'road-piece@+1,0', 'road-piece@01,0', 'road-piece@0,00', 'road-piece@١,0',
            'road-piece@2147483648,0', 'road-piece@0,2147483648', 'road-piece@1.0,0', 'road-piece@1,', 'road-piece@,1',
            'road-piece@@1,0', 'road-piece@1,0,2', 'road-piece@1,0@', 'road piece@1,0', 'road-piece @1,0', 'road-piece%20@1,0',
            'road-piece@1, 0', 'road-piece@1,0\n', "road-piece@1,0' OR 1=1", `${'e'.repeat(129)}@0,0`, 'e'.repeat(129),
        ])('버린다: %j', (id) => {
            expect(fort(id)).toBe(false);
        });
        it.each([nativeSite, 'e'.repeat(128), longestCell, 'road-piece@1,2'])('남는다: %s', (id) => {
            expect(fort(id)).toBe(true);
        });
    });

    describe('원문 보존 · 선택 역할', () => {
        it.each([
            ['legacy', 'fort-hulao'], ['최장 edge site', longestSite], ['최장 칸 site', longestCell],
            ['실제 site', widestSite], ['C3 site', nativeSite],
        ] as const)('%s: FROM_NATION 없음 · 0 모두 원문 그대로 남는다', (_label, id) => {
            const out = (refs: Record<string, unknown>) => publicWorldEvents({ events: [captured(refs)] }, 5)?.events[0]?.refs;
            expect(out({ ROAD_FORT: id, TO_NATION: 3 })).toEqual({ ROAD_FORT: id, TO_NATION: 3 });
            expect(out({ ROAD_FORT: id, FROM_NATION: 0, TO_NATION: 3 })).toEqual({ ROAD_FORT: id, FROM_NATION: 0, TO_NATION: 3 });
        });
    });

    describe('정상 site 라도 사건 모양이 틀리면 버린다', () => {
        it.each([
            ['TO_NATION 없음', captured({ ROAD_FORT: longestSite, FROM_NATION: 2 })],
            ['모르는 ACTOR ref', captured({ ROAD_FORT: longestSite, TO_NATION: 3, ACTOR: 8 })],
            ['facts 가 비어 있지 않음', captured({ ROAD_FORT: longestSite, TO_NATION: 3 }, { facts: { TROOPS_BAND: 2 } })],
            ['비공개 종류 roadFort.siege', captured({ ROAD_FORT: longestSite, TO_NATION: 3 }, { kind: 'roadFort.siege', section: 'BATTLE' })],
            ['전장 칸', captured({ ROAD_FORT: longestSite, TO_NATION: 3 }, { section: 'BATTLE' })],
        ] as const)('%s', (_label, event) => {
            expect(kept(event)).toBe(false);
        });
    });

    it('정상 · 잘못된 사건이 섞이면 잘못된 것만 빠지고 순서는 그대로다', () => {
        const events = [
            captured({ ROAD_FORT: longestSite, TO_NATION: 3 }, { id: 11 }),
            captured({ ROAD_FORT: 'e@01,0', TO_NATION: 3 }, { id: 12 }),
            captured({ ROAD_FORT: nativeSite, FROM_NATION: 0, TO_NATION: 4 }, { id: 13 }),
            captured({ ROAD_FORT: widestSite, TO_NATION: 3, ACTOR: 8 }, { id: 14 }),
            captured({ ROAD_FORT: 'fort-hulao', TO_NATION: 5 }, { id: 15 }),
        ];
        expect(publicWorldEvents({ events }, 5)?.events.map((event) => [event.id, event.refs.ROAD_FORT])).toEqual([
            [11, longestSite], [13, nativeSite], [15, 'fort-hulao'],
        ]);
    });
});
