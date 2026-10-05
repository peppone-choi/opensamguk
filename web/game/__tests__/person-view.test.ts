import { describe, expect, it } from 'vitest';
import { personDetailPath, usableDetail, type PersonDetailRead } from '../lib/person-detail';
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

// ── 인물 상세 읽기(K4-13, D124 미리 짓기) — C10 accepted-fields 모양 ──
const detail = (over: Partial<PersonDetailRead> = {}): PersonDetailRead => ({
    status: 'READY', relation: 'OTHER', generalId: 55, name: '안량', portrait: { picture: 'a.png', imageServer: 2 },
    affiliation: { nationId: 2, name: '원소', color: '#9c4a3f' },
    stats: { leadership: 80, strength: 92, intel: 30, politics: 25, charm: 40 }, aptitudes: { command: 70, administration: 10, strategy: 15, envoy: 20 },
    role: null, lordGeneralId: null, location: null, bonds: null, injured: null, retinue: null,
    unavailableReasons: { '/location': 'NOT_AUTHORIZED', '/bonds': 'NOT_AUTHORIZED', '/injured': 'NOT_AUTHORIZED' }, ...over,
});

describe('인물 상세(K4-13) — 경로 · 받을 수 있는 응답', () => {
    it('대상은 경로, 보는 사람은 generalId 쿼리', () => {
        expect(personDetailPath(7, 55)).toBe('/api/people/55?generalId=7');
    });
    it('READY · PARTIAL 이고 대상 id · 이름 · 관계를 알 때만 쓴다', () => {
        expect(usableDetail(detail(), 55)).not.toBeNull();
        expect(usableDetail(detail({ status: 'PARTIAL' }), 55)).not.toBeNull();
        for (const bad of [detail({ status: 'UNAVAILABLE' }), detail({ generalId: 56 }), detail({ relation: 'UNKNOWN' }), detail({ relation: null }),
            detail({ relation: 'ENEMY' as never }), detail({ name: ' ' }), null]) expect(usableDetail(bad, 55)).toBeNull();
    });
});

describe('personView + 상세 — 관계별 칸', () => {
    it('다른 세력 — 공개 칸(이름 · 초상 · 소속 · 5능력 · 적성)만, 사적인 칸은 null, 적으로 바꾸지 않는다', () => {
        expect(personView(55, me, [row()], detail())).toMatchObject({
            relation: 'OTHER', name: '안량', picture: 'a.png', imageServer: 2, affiliation: '원소 소속', nationColor: '#9c4a3f',
            stats: { strength: 92 }, aptitudes: { command: 70 }, bonds: null, injured: null, retinue: null, locationName: null,
        });
    });
    it('같은 세력 — 같은 공개 칸, 소속이 없으면 재야(권한 밖 이유가 있으면 모름)', () => {
        expect(personView(55, me, null, detail({ relation: 'SAME_NATION', affiliation: { nationId: 1, name: '조조', color: '#4f7fbf' } })))
            .toMatchObject({ relation: 'SAME_NATION', affiliation: '조조 소속' });
        expect(personView(55, me, null, detail({ affiliation: null })).affiliation).toBe('재야');
        expect(personView(55, me, null, detail({ affiliation: null, unavailableReasons: { '/affiliation': 'NOT_AUTHORIZED' } })).affiliation).toBeNull();
    });
    it('나 — front-info 가 먼저, 상세(SELF)는 적성 · 결속 · 위치를 더한다', () => {
        const bonds = [{ kind: 'HYANGDANG', label: '향당', nativeCountyName: '패국 초현', sameAsLord: true }];
        const v = personView(7, me, null, detail({ relation: 'SELF', generalId: 7, name: '하후돈', bonds, location: { cityId: 3, name: '양적현' } }));
        expect(v).toMatchObject({ relation: 'SELF', name: '하후돈', picture: 'p.png', aptitudes: { command: 70 }, bonds, locationName: '양적현', injured: false });
        // 서버가 SELF 로 판정하지 않은 상세의 사적인 칸은 쓰지 않는다.
        expect(personView(7, me, null, detail({ generalId: 7, bonds, location: { cityId: 3, name: '양적현' } }))).toMatchObject({ bonds: null, locationName: null });
    });
    it('내 부 — 부 줄이 먼저, 상세(RETINUE)는 부상 · 위치를 더한다', () => {
        const v = personView(101, me, [row()], detail({ relation: 'RETINUE', generalId: 101, name: '허저', injured: true, location: { cityId: 9, name: '허현' } }));
        expect(v).toMatchObject({ relation: 'RETINUE', name: '허저', injured: true, locationName: '허현', aptitudes: { command: 60 } });
        expect(v.retinue?.loyalty).toBe(85);
    });
    it('상세를 못 쓰면 지금처럼 UNKNOWN', () => {
        expect(personView(55, me, null, detail({ status: 'UNAVAILABLE' })).relation).toBe('UNKNOWN');
        expect(personView(55, me, null, null).relation).toBe('UNKNOWN');
    });
    it('다른 세력의 자리 · 상태 — 위치 · 자리 · 부상 · 녹봉 · 충성은 「내 부 인물만」, 나머지는 서버 대기', () => {
        const cells = stateCells(personView(55, me, null, detail()), null);
        expect(Object.fromEntries(cells.map((c) => [c.label, c.kind]))).toEqual({
            위치: 'hidden', 자리: 'hidden', 부상: 'hidden', 녹봉: 'hidden', '보물 칸': 'wait', 경험: 'wait', 충성: 'hidden', 생몰: 'wait',
        });
    });
});
