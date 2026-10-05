// 새 장수 만들기(P-E02) 보기 모델 — 계약(K5-02) 규칙만으로 판정한다.
import { describe, expect, it } from 'vitest';
import { blockReason, bumpStat, commanderiesOf, countyCandidate, evenStats, filterCounties, nameProblem, provincesOf, statSum, type CreateDraft } from '@/lib/create-view';
import { OPTIONS } from '@/lib/creation-fixtures';

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
