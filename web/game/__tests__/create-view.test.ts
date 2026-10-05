// 새 장수 만들기(P-E02) 보기 모델 — 계약(K5-02) 규칙만으로 판정한다.
import { describe, expect, it } from 'vitest';
import {
    initialRole, roleCards, seatsLine,
    blockReason, bumpStat, commanderiesOf, countiesCentre, countyCandidate, countyCell, evenStats, filterCounties, nameProblem, provincesOf, statSum, type CreateDraft,
} from '@/lib/create-view';
import { countyCellOf } from '@/lib/creation-api';
import type { CreationCountyWire } from '@/lib/creation-contract';
import { OPTIONS } from './fixtures/creation';

const rule = OPTIONS.statRule;
const base: CreateDraft = { role: 'RETAINER', countyId: 11, name: '하후연', stats: evenStats(rule), ideologyId: 'kingly', traitId: 'discipline' };
const block = (d: Partial<CreateDraft>) => blockReason({ ...base, ...d }, { stat: rule, name: OPTIONS.nameRule }, OPTIONS.nativeCounties);

describe('능력', () => {
    it('고르게 — 합이 정확히 total, 각 값은 min–max', () => {
        const s = evenStats(rule);
        expect(statSum(s)).toBe(300);
        expect(Object.values(s).every((v) => v >= 20 && v <= 85)).toBe(true);
        expect(statSum(evenStats({ min: 20, max: 85, total: 302 }))).toBe(302);
    });
    it('한 능력 바꾸기는 min–max 로 자른다', () => {
        const s = evenStats(rule);
        expect(bumpStat(s, 'strength', 100, rule).strength).toBe(85);
        expect(bumpStat(s, 'strength', 3, rule).strength).toBe(20);
        expect(bumpStat(s, 'strength', Number.NaN, rule).strength).toBe(s.strength);
    });
});

describe('이름 규칙(nameRule)', () => {
    it.each([['하후연', null], ['  제갈 량 ', null], ['Lu·Bu', null], ['', '이름을 쓰세요.'], ['가'.repeat(13), '이름은 1–12글자입니다.'],
        ['하후연1', '이름은 한글 · 한자 · 라틴 글자와, 글자 사이의 한 칸 또는 가운뎃점만 쓸 수 있습니다.'],
        ['하후  연', '이름은 한글 · 한자 · 라틴 글자와, 글자 사이의 한 칸 또는 가운뎃점만 쓸 수 있습니다.']])('%j → %j', (name, problem) => {
        expect(nameProblem(name, OPTIONS.nameRule)).toBe(problem);
    });
    it('모르는 글자 규칙이면 길이만 본다(서버가 다시 검사)', () => {
        expect(nameProblem('하후연1', { ...OPTIONS.nameRule, allowedCharacters: 'SOMETHING_NEW' })).toBeNull();
    });
});

describe('본관 현', () => {
    it('지도 칸 받기 — 중첩 cell(정본) 먼저, 없으면 납작한 cellCol · cellRow(#1137 초안), 빠지거나 정수가 아니면 null', () => {
        const base = { cityId: 1, name: '허현', commanderyId: null, commanderyName: null, provinceName: null, available: true, reason: null };
        const wire = (extra: Partial<CreationCountyWire>): CreationCountyWire => ({ ...base, ...extra });
        expect(countyCellOf(wire({ cell: { col: 1233, row: 812 } }))).toEqual({ col: 1233, row: 812 });
        expect(countyCellOf(wire({ cell: null, cellCol: 5, cellRow: 6 }))).toBeNull(); // 정본이 null 이라 말했으면 옛 칸을 보지 않는다
        expect(countyCellOf(wire({ cellCol: 120, cellRow: 80 }))).toEqual({ col: 120, row: 80 });
        expect(countyCellOf(wire({ cellCol: null, cellRow: null }))).toBeNull();
        // 칸이 아예 없으면(새 모양을 모르는 옛 판 · 빠진 칸) — undefined 로 NaN 이 나지 않게 null
        expect(countyCellOf(wire({}))).toBeNull();
        expect(countyCellOf(wire({ cellCol: 120 }))).toBeNull();
        expect(countyCellOf(wire({ cell: { col: 1.5, row: 2 } }))).toBeNull();
    });
    it('후보 — 지도 칸이 있으면 같이, 불가는 서버 문장과 같은 사유', () => {
        const ok = countyCandidate(OPTIONS.nativeCounties[0], true);
        expect(ok).toMatchObject({ targetKind: 'place', targetId: '11', cell: { col: 120, row: 80 }, available: true, name: '허현', sub: '영천군 · 예주 · 고름' });
        const no = countyCandidate(OPTIONS.nativeCounties[3]);
        expect(no).toMatchObject({ available: false, reasonCode: 'INVALID_NATIVE_COUNTY', reason: '시작할 수 없는 본관입니다. 다른 현을 선택해 주세요.' });
        expect(no).not.toHaveProperty('cell');
    });
    it('주 · 군 · 현 찾기 거르기', () => {
        expect(provincesOf(OPTIONS.nativeCounties)).toEqual(['예주', '기주']);
        expect(commanderiesOf(OPTIONS.nativeCounties, '예주')).toEqual(['영천군']);
        expect(filterCounties(OPTIONS.nativeCounties, { province: '기주', commandery: null, q: '' }).map((c) => c.name)).toEqual(['업현']);
        expect(filterCounties(OPTIONS.nativeCounties, { province: null, commandery: null, q: '장사' }).map((c) => c.name)).toEqual(['장사현']);
    });
    it('지도 칸 — 칸 가운데(+0.5), 칸 없는 현은 null · 가운데 셈에서 빠진다', () => {
        expect(countyCell(OPTIONS.nativeCounties[0])).toEqual({ col: 120.5, row: 80.5 });
        expect(countyCell(OPTIONS.nativeCounties[3])).toBeNull();
        expect(countyCell(undefined)).toBeNull();
        // 영천군: 허현(120,80) · 장사현(118,76) · 마피영(칸 없음) → 두 칸의 평균
        const yingchuan = filterCounties(OPTIONS.nativeCounties, { province: null, commandery: '영천군', q: '' });
        expect(countiesCentre(yingchuan)).toEqual({ col: 119.5, row: 78.5 });
        expect(countiesCentre([OPTIONS.nativeCounties[3]])).toBeNull();
    });
});

describe('막는 사유(첫 하나)', () => {
    it('다 채우면 null', () => expect(block({})).toBeNull());
    it.each<[Partial<CreateDraft>, RegExp]>([
        [{ role: 'PRE_LORD' }, /예비 주공으로 시작하기는 서버가 아직 받지 않습니다/],
        [{ countyId: null }, /본관 현을 고르세요/],
        [{ countyId: 99 }, /시작할 수 없는 본관입니다/],
        [{ name: '' }, /이름을 쓰세요/],
        [{ stats: { ...evenStats(rule), charm: 40 } }, /20점이 남았습니다. 다섯 능력의 합이 300이어야 합니다/],
        [{ stats: { ...evenStats(rule), charm: 70 } }, /10점이 넘칩니다/],
        [{ ideologyId: null }, /주의를 고르세요/],
        [{ traitId: null }, /개성을 고르세요/],
    ])('%j', (d, re) => expect(block(d)).toMatch(re));
});

describe('역할 칸(D121 A안) — cap:null = 인원 제한 없음, used:null = 원천 없음', () => {
    const row = (role: string, extra: Partial<{ allowed: boolean; reason: string | null; used: number | null; cap: number | null; path: string }> = {}) => ({
        path: 'CUSTOM', role, allowed: true, reason: null, used: null, cap: null, ...extra,
    });
    it('roles 가 없으면(옛 서버) null — 화면은 지금 그대로', () => {
        expect(roleCards(undefined)).toBeNull();
        expect(initialRole(null)).toBe('RETAINER');
    });
    it('열린 역할 · cap:null → 「인원 제한 없음」, used:null → 숫자 없음 / 닫힌 역할 → 쉬운 말 사유 · 칩 없음', () => {
        const cards = roleCards([row('RETAINER'), row('PRE_LORD', { allowed: false, reason: 'ROLE_UNAVAILABLE' })])!;
        expect(cards[0]).toMatchObject({ role: 'RETAINER', allowed: true, seatChip: '인원 제한 없음', usedText: null, reason: null });
        expect(cards[1]).toMatchObject({ role: 'PRE_LORD', allowed: false, seatChip: null, reason: '예비 주공으로 시작하기는 서버가 아직 받지 않습니다.' });
    });
    it('숫자가 오면(나중) 그대로 — used/cap · 최대 cap · used 만', () => {
        expect(roleCards([row('RETAINER', { used: 3, cap: 10 }), row('PRE_LORD', { cap: 5 })])!.map((c) => c.seatChip)).toEqual(['3 / 10', '최대 5명']);
        expect(roleCards([row('RETAINER', { used: 7 }), row('PRE_LORD')])![0].usedText).toBe('7명이 이 역할로 시작');
    });
    it('서버가 주지 않은 역할 · 다른 길(HISTORICAL) 행은 닫는다(지어내지 않는다)', () => {
        const cards = roleCards([row('RETAINER'), row('PRE_LORD', { path: 'HISTORICAL' })])!;
        expect(cards[1]).toMatchObject({ role: 'PRE_LORD', allowed: false, reason: '지금 고를 수 없습니다.' });
    });
    it('처음 역할은 열린 첫 역할', () => {
        expect(initialRole(roleCards([row('RETAINER', { allowed: false, reason: null }), row('PRE_LORD')]))).toBe('PRE_LORD');
    });
    it('자리 줄 · 다 차면 막는다 · 닫힌 역할을 고르면 막는다', () => {
        expect(seatsLine({ used: 38, max: 50 })).toBe('사람 장수 자리 12/50 남음');
        expect(seatsLine(undefined)).toBeNull();
        expect(blockReason(base, { stat: rule, name: OPTIONS.nameRule }, OPTIONS.nativeCounties, { playerCap: { used: 50, max: 50 } })).toBe('사람 장수 자리가 다 찼습니다.');
        const cards = roleCards([row('RETAINER'), row('PRE_LORD', { allowed: false, reason: 'ROLE_UNAVAILABLE' })]);
        expect(blockReason({ ...base, role: 'PRE_LORD' }, { stat: rule, name: OPTIONS.nameRule }, OPTIONS.nativeCounties, { roles: cards }))
            .toBe('예비 주공으로 시작하기는 서버가 아직 받지 않습니다. 다른 역할을 고르세요.');
        expect(blockReason(base, { stat: rule, name: OPTIONS.nameRule }, OPTIONS.nativeCounties, { roles: cards, playerCap: { used: 1, max: 50 } })).toBeNull();
    });
});
