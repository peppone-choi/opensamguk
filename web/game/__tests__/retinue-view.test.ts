import { expect, test } from 'vitest';
import type { PersonCard, PlacementCard, Posts, Retinue, UnitCard, Yuedan } from '../lib/campaign-reads';
import { buName, filterRetinue, loyaltyTone, renownBand, retinueRows, sortRetinue, unitRows } from '../lib/retinue-view';

const person = (id: number, over: Partial<PersonCard> = {}): PersonCard => ({
    retainerId: id, generalId: 100 + id, name: `인물${id}`, picture: null, imageServer: 0, loyalty: 60,
    roleLabel: '없음', taskLabel: '없음', stats: null, cost: null, aptitudes: null, bonds: [], departureOrder: null, locationCityId: null, ...over,
});
const unit = (id: number, commander: number | null, troops: number): UnitCard => ({
    id, name: `부곡${id}`, troops, crewTypeId: 1, crewTypeName: '보병', training: 50, morale: 50, fatigue: 0, provisions: 0, provisionMonths: 2,
    commanderRetainerId: commander,
});
const retinue = (people: PersonCard[], units: UnitCard[] = [], over: Partial<Retinue> = {}): Retinue =>
    ({ status: 'READY', renown: 30, costSum: 24, overCapacity: false, people, units, ...over });
const card = (cardId: number, over: Partial<PlacementCard> = {}): PlacementCard => ({
    cardId, generalId: 100 + cardId, name: '', relation: 'LIEUTENANT', provinceId: null, placeable: true, blocked: null, active: null, pending: null, ...over,
});
const posts = (cards: PlacementCard[]): Posts => ({ status: 'READY', cards, posts: [] });

test('부 이름 — 서버 값이 없으면 「이름의 막부」', () => {
    expect(buName('하후돈')).toBe('하후돈의 막부');
    expect(buName('하후돈', '하후돈의 군부')).toBe('하후돈의 군부');
});

test('자리는 배치 원장에서 — retinue 의 역할 라벨(roleLabel)은 쓰지 않는다', () => {
    const rows = retinueRows(retinue([person(1, { roleLabel: '참모' }), person(2)]), posts([
        card(1, { active: { post: 'MAGISTRATE', postLabel: '현령', target: { label: '장사현' }, state: 'ARRIVED' } }),
        card(2, { pending: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: null } }, placeable: false,
            blocked: { code: 'CARD_DEPLOYED', reason: '출전 중인 카드입니다.' } }),
    ]));
    expect(rows[0].post).toEqual({ active: '현령 · 장사현', pending: null, placeable: true, blocked: null });
    expect(rows[1].post.active).toBeNull();
    expect(rows[1].post.pending).toBe('군단장');
    expect(rows[1].post.blocked?.code).toBe('CARD_DEPLOYED');
});

test('배치 조회를 못 받았으면 배치 가능 여부를 짓지 않는다(null)', () => {
    const rows = retinueRows(retinue([person(1)]), null);
    expect(rows[0].post.placeable).toBeNull();
    const down = retinueRows(retinue([person(1)]), { status: 'UNAVAILABLE', cards: [], posts: [] });
    expect(down[0].post.placeable).toBeNull();
});

test('지휘하는 부곡 병력을 합치고, 지휘 없는 부대는 commander null', () => {
    const r = retinue([person(1), person(2)], [unit(10, 1, 300), unit(11, 1, 200), unit(12, null, 100)]);
    expect(retinueRows(r, null).map((x) => x.troops)).toEqual([500, 0]);
    expect(unitRows(r).map((u) => u.commander)).toEqual(['인물1', '인물1', null]);
});

test('정렬 — 값이 없는 줄은 뒤로, 같으면 등록순, 원본은 그대로', () => {
    const rows = retinueRows(retinue([
        person(3, { cost: 2, loyalty: 40, departureOrder: 1 }),
        person(1, { cost: null, loyalty: 90, departureOrder: null }),
        person(2, { cost: 5, loyalty: 40, departureOrder: 2 }),
    ]), null);
    const ids = (s: Parameters<typeof sortRetinue>[1]) => sortRetinue(rows, s).map((r) => r.retainerId);
    expect(ids('registered')).toEqual([1, 2, 3]);
    expect(ids('cost')).toEqual([2, 3, 1]);
    expect(ids('loyalty')).toEqual([2, 3, 1]);
    expect(ids('departure')).toEqual([3, 2, 1]);
    expect(rows.map((r) => r.retainerId)).toEqual([3, 1, 2]);
});

test('충성 색 문턱은 지금 명부와 같다(80 · 50)', () => {
    expect([80, 79, 50, 49].map(loyaltyTone)).toEqual(['moss', 'neutral', 'neutral', 'rust']);
});

test('명망 띠 — 부 조회 값 먼저, 없으면 월단평 본인 값, 막대는 1 에서 자른다', () => {
    const yuedan = { status: 'READY', stamp: null, ranking: [], self: { generalId: 1, renown: 30, retinueCost: 36, overCapacity: true } } as Yuedan;
    expect(renownBand(retinue([], [], { renown: null, costSum: null, overCapacity: false }), yuedan))
        .toEqual({ renown: 30, costSum: 36, overCapacity: false, ratio: 1 });
    expect(renownBand(null, yuedan)).toEqual({ renown: 30, costSum: 36, overCapacity: true, ratio: 1 });
    expect(renownBand(null, null)).toEqual({ renown: null, costSum: null, overCapacity: false, ratio: null });
});

test('거르기 — 미배치는 지금 · 다음 자리가 모두 없는 줄, 찾기는 넘겨준 맞춤 함수로', () => {
    const rows = retinueRows(retinue([
        person(1, { name: '허저', bonds: [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true }] }),
        person(2, { name: '이전' }),
        person(3, { name: '무명 공조', departureOrder: 1 }),
    ]), posts([
        card(1, { active: { post: 'MAGISTRATE', postLabel: '현령', target: { label: '장사현' }, state: 'ARRIVED' } }),
        card(2, { pending: { post: 'CORPS_COMMANDER', postLabel: '군단장', target: { label: null } } }),
        card(3),
    ]));
    const any = () => true;
    const ids = (f: Parameters<typeof filterRetinue>[1], q = '', m: (n: string, q: string) => boolean = any) =>
        filterRetinue(rows, f, q, m).map((r) => r.retainerId);
    expect(ids('all')).toEqual([1, 2, 3]);
    expect(ids('unplaced')).toEqual([3]);
    expect(ids('bonded')).toEqual([1]);
    expect(ids('risk')).toEqual([3]);
    expect(ids('all', '허', (n, q) => n.includes(q))).toEqual([1]);
});

test('사람 장수 표지(K4-18) — 부 조회 값 먼저, 없으면 배치 조회 값, 둘 다 없으면 undefined(짓지 않는다)', () => {
    const rows = retinueRows(retinue([person(1, { isHuman: true }), person(2), person(3, { isHuman: null })]),
        posts([card(1, { isHuman: false }), card(2, { isHuman: false }), card(3)]));
    expect(rows.map((r) => r.isHuman)).toEqual([true, false, undefined]);
    expect(retinueRows(retinue([person(4)]), null)[0].isHuman).toBeUndefined();
});
