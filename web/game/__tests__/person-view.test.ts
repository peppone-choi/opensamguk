import { describe, expect, it } from 'vitest';
import { parseGeneralId, personView, selfStats, stateCells } from '../lib/person-view';
import type { RetinueRow } from '../lib/retinue-view';

const row = (over: Partial<RetinueRow> = {}): RetinueRow => ({
    retainerId: 1, generalId: 101, name: '허저', picture: null, imageServer: 0, loyalty: 85, loyaltyTone: 'moss', cost: 12,
    departureOrder: null, bonds: [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true }],
    stats: { leadership: 70, strength: 90, intel: 30, politics: 20, charm: 40 }, aptitudes: { command: 60, administration: 20, strategy: 10, envoy: 15 },
    post: { active: '현령 · 장사현', pending: null, placeable: true, blocked: null }, troops: 0, isHuman: false, ...over,
});
const general = { generalId: 7, name: '하후돈', leadership: 80, strength: 85, intel: 50, politics: 40, charm: 60, injury: 0, picture: 'p.png', imageServer: 1 };
const me = { general, nation: { id: 1, name: '조조', color: '#4f7fbf' } } as never;

describe('parseGeneralId — 경로 조각', () => {
    it('양의 정수만, 아니면 null', () => {
        expect(parseGeneralId('101')).toBe(101);
        expect(parseGeneralId(['7'])).toBe(7);
        for (const bad of ['0', '-3', 'abc', '1.5', '', undefined, null, '1234567890']) expect(parseGeneralId(bad)).toBeNull();
    });
});

describe('personView — 관계 판정', () => {
    it('내 장수 id 면 SELF — front-info 초상 · 5능력 · 부상 · 소속, 적성 · 결속은 모름(서버 대기)', () => {
        expect(personView(7, me, null)).toMatchObject({
            relation: 'SELF', name: '하후돈', picture: 'p.png', affiliation: '조조 소속', nationColor: '#4f7fbf',
            stats: { leadership: 80, charm: 60 }, aptitudes: null, bonds: null, injured: false, retinue: null,
        });
    });
    it('내 부 줄에 있으면 RETINUE — 부 읽기의 능력 · 적성 · 결속 · 충성 줄, 소속은 주공과 같다', () => {
        const v = personView(101, me, [row()]);
        expect(v).toMatchObject({ relation: 'RETINUE', name: '허저', affiliation: '조조 소속', stats: { strength: 90 }, aptitudes: { command: 60 } });
        expect(v.retinue?.loyalty).toBe(85);
    });
    it('그 밖은 UNKNOWN — 이름 · 초상도 짓지 않는다', () => {
        expect(personView(55, me, [row()])).toMatchObject({ relation: 'UNKNOWN', name: '', stats: null, affiliation: null });
        expect(personView(55, null, null).relation).toBe('UNKNOWN');
    });
    it('재야(세력 0)면 「재야」, 색 없음', () => {
        expect(personView(7, { general, nation: { id: 0, name: '', color: '' } } as never, null)).toMatchObject({ affiliation: '재야', nationColor: null });
    });
});

describe('selfStats — front-info 5능력', () => {
    it('하나라도 빠지면 「undefined」로 그리지 않고 모름(null)', () => {
        expect(selfStats({ leadership: 1, strength: 2, intel: 3, politics: 4 })).toBeNull();
        expect(selfStats(null)).toBeNull();
        expect(selfStats(general)).toEqual({ leadership: 80, strength: 85, intel: 50, politics: 40, charm: 60 });
    });
});

describe('stateCells — 자리 · 상태 8칸(보드 state8)', () => {
    it('내 부 인물: 자리 · 충성은 값, 녹봉 · 보물 · 경험 · 생몰은 서버 대기', () => {
        const cells = stateCells(personView(101, me, [row()]), null);
        expect(cells.map((c) => c.label)).toEqual(['위치', '자리', '부상', '녹봉', '보물 칸', '경험', '충성', '생몰']);
        expect(cells.find((c) => c.label === '자리')).toMatchObject({ value: '현령 · 장사현', kind: 'value' });
        expect(cells.find((c) => c.label === '충성')).toMatchObject({ value: '85', kind: 'value' });
        expect(cells.filter((c) => c.kind === 'wait').map((c) => c.label)).toEqual(['위치', '부상', '녹봉', '보물 칸', '경험', '생몰']);
        expect(stateCells(personView(101, me, [row({ post: { active: null, pending: null, placeable: true, blocked: null } })]), null)
            .find((c) => c.label === '자리')?.value).toBe('미배치');
    });
    it('나: 위치는 내 城, 부상은 front-info, 충성 · 자리는 서버 대기(관계 밖 「내 부 인물만」이 아니다)', () => {
        const cells = stateCells(personView(7, me, null), '양적현');
        expect(cells.find((c) => c.label === '위치')).toMatchObject({ value: '양적현', kind: 'value' });
        expect(cells.find((c) => c.label === '부상')).toMatchObject({ value: '없음', kind: 'value' });
        expect(cells.find((c) => c.label === '충성')?.kind).toBe('wait');
        expect(cells.some((c) => c.kind === 'hidden')).toBe(false);
    });
});
