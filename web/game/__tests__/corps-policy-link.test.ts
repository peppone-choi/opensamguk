// 군단 → 영지 방침 주소 문맥 — 순수 해석 · 주소 만들기 · 탭 서버 · 대상 판정(최신 READY 줄만, 대신 고르지 않음).
import { describe, expect, it } from 'vitest';
import type { Policies } from '../lib/campaign-reads';
import { CORPS_ORDER_ID_MAX, corpsPolicyHref, parseTerritoryPolicyQuery, tabServerId, territoryBaseFor } from '../lib/territory/corps-policy-link';
import { CORPS_CONTEXT_TEXT, corpsPolicyContext, latestSettablePolicyRow } from '../lib/territory/corps-policy-view';

const q = (s: string) => new URLSearchParams(s);

describe('영지 방침 주소 해석', () => {
    it('문맥 없음 — 쿼리 없음 · 다른 보기 · scope 없는 policy 보기는 기본 동작', () => {
        expect(parseTerritoryPolicyQuery(null)).toEqual({ kind: 'none' });
        expect(parseTerritoryPolicyQuery(q(''))).toEqual({ kind: 'none' });
        expect(parseTerritoryPolicyQuery(q('view=policy'))).toEqual({ kind: 'none' });
        expect(parseTerritoryPolicyQuery(q('view=placement&scope=CORPS&orderId=O-1'))).toEqual({ kind: 'none' });
        expect(parseTerritoryPolicyQuery(q('view=work&countyId=3'))).toEqual({ kind: 'none' });
    });

    it('scope=CORPS — orderId 없으면 목록, 있으면 그 값 그대로(불투명, 숫자로 바꾸지 않음)', () => {
        expect(parseTerritoryPolicyQuery(q('view=policy&scope=CORPS'))).toEqual({ kind: 'corpsList' });
        expect(parseTerritoryPolicyQuery(q('view=policy&scope=CORPS&orderId=0012'))).toEqual({ kind: 'corps', orderId: '0012' });
        expect(parseTerritoryPolicyQuery(q(`view=policy&scope=CORPS&orderId=${encodeURIComponent('a/b c&d=é')}`)))
            .toEqual({ kind: 'corps', orderId: 'a/b c&d=é' });
    });

    it('읽을 수 없는 문맥은 invalid — 다른 scope · 빈 값 · scope 없는 orderId · 제어 문자 · 너무 긴 값', () => {
        for (const s of [
            'view=policy&scope=COUNTY&orderId=1', 'view=policy&scope=corps', 'view=policy&scope=CORPS&orderId=',
            'view=policy&scope=CORPS&orderId=%20%20', 'view=policy&orderId=O-1', 'view=policy&scope=CORPS&orderId=a%0Ab',
            `view=policy&scope=CORPS&orderId=${'x'.repeat(CORPS_ORDER_ID_MAX + 1)}`,
        ]) expect(parseTerritoryPolicyQuery(q(s)), s).toEqual({ kind: 'invalid' });
        expect(parseTerritoryPolicyQuery(q(`view=policy&scope=CORPS&orderId=${'x'.repeat(CORPS_ORDER_ID_MAX)}`)).kind).toBe('corps');
    });
});

describe('주소 만들기 · 탭 서버', () => {
    it('실제 & 구분자 · orderId 는 인코딩만 하고, 해석하면 같은 값으로 돌아온다', () => {
        const href = corpsPolicyHref('/game/pep/territory', 'a/b c&d=é?#');
        expect(href.startsWith('/game/pep/territory?view=policy&scope=CORPS&orderId=')).toBe(true);
        expect(href).not.toContain('&amp;');
        expect(parseTerritoryPolicyQuery(new URL(href, 'http://x').searchParams)).toEqual({ kind: 'corps', orderId: 'a/b c&d=é?#' });
        expect(corpsPolicyHref('/game/territory', null)).toBe('/game/territory?view=policy&scope=CORPS');
        expect(corpsPolicyHref('/game/territory?server=pep', 'O-1')).toBe('/game/territory?server=pep&view=policy&scope=CORPS&orderId=O-1');
    });

    it('지금 탭 주소의 명시 서버가 쿠키 기반 주소보다 먼저다 · 예약어 경로는 서버가 아니다', () => {
        expect(tabServerId('/game/pep/corps')).toBe('pep');
        expect(tabServerId('/game/corps')).toBeNull();
        expect(tabServerId('/game')).toBeNull();
        expect(tabServerId('/other/pep/corps')).toBeNull();
        expect(tabServerId(null)).toBeNull();
        expect(territoryBaseFor('/game/pep/corps', '/game/other/territory')).toBe('/game/pep/territory');
        expect(territoryBaseFor('/game/corps', '/game/other/territory')).toBe('/game/other/territory');
    });
});

const policies = (corps: Policies['corps'], status: Policies['status'] = 'READY'): Policies => ({
    status, countyOptions: [{ code: 'FARM', label: '농업' }], corpsOptions: [{ code: 'DRILL', label: '조련' }], defaultPolicy: null, counties: [
        { countyId: 129, name: '양성현', commanderyName: '영천군', active: null, pending: null, effective: null, seat: null, settable: true, blocked: null },
    ] as never, corps,
});
const corpsRow = (orderId: string, settable = true, reason: string | null = null) => ({
    orderId, commanderName: '하후돈', active: null, pending: null, settable, blocked: settable ? null : { code: 'NOT_OWNER', reason: reason ?? '' },
});
const ready = (data: Policies) => ({ loading: false, error: null, data });

describe('대상 판정', () => {
    const target = { kind: 'corps', orderId: 'O-1' } as const;

    it('문맥 없음이면 아무것도 바꾸지 않는다(기본 현 탭)', () => {
        expect(corpsPolicyContext({ kind: 'none' }, ready(policies([corpsRow('O-1')])))).toEqual({ tab: null, targetId: null, tone: 'info', text: null });
    });

    it('최신 READY 줄에 있으면 표시 · 바꿀 수 없으면 서버 사유', () => {
        expect(corpsPolicyContext(target, ready(policies([corpsRow('O-1')])))).toMatchObject({ tab: 'CORPS', targetId: 'O-1', tone: 'info', text: '하후돈 군단 — 「바꾸기」를 눌러 방침을 고르세요.' });
        expect(corpsPolicyContext(target, ready(policies([corpsRow('O-1', false, '군단 주인만 정합니다')]))))
            .toMatchObject({ targetId: 'O-1', tone: 'warn', text: '하후돈 군단 — 방침을 바꿀 수 없습니다: 군단 주인만 정합니다' });
        expect(corpsPolicyContext(target, ready(policies([corpsRow('O-1', false, '  ')]))).text).toContain('사유를 받지 못했습니다');
    });

    it('없거나 못 읽었으면 사유만 — 다른 군단으로 대신하지 않는다', () => {
        const other = ready(policies([corpsRow('O-2')]));
        expect(corpsPolicyContext(target, other)).toEqual({ tab: 'CORPS', targetId: null, tone: 'warn', text: CORPS_CONTEXT_TEXT.missing });
        expect(corpsPolicyContext(target, { loading: true, error: null, data: null })).toMatchObject({ targetId: null, text: CORPS_CONTEXT_TEXT.loading });
        expect(corpsPolicyContext(target, { loading: false, error: '불러오지 못했습니다.', data: null })).toMatchObject({ targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.failed });
        expect(corpsPolicyContext(target, ready(policies([corpsRow('O-1')], 'NOT_READY' as never)))).toMatchObject({ targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.notReady });
        expect(corpsPolicyContext({ kind: 'invalid' }, other)).toMatchObject({ tab: 'CORPS', targetId: null, tone: 'error', text: CORPS_CONTEXT_TEXT.invalid });
        expect(corpsPolicyContext({ kind: 'corpsList' }, other)).toMatchObject({ tab: 'CORPS', targetId: null, text: CORPS_CONTEXT_TEXT.list });
    });

    it('편집 · 제출 직전 최신 줄 — 같은 범위 · 같은 id 이고 지금 바꿀 수 있을 때만', () => {
        const data = policies([corpsRow('O-1'), corpsRow('O-2', false, '안 됨')]);
        expect(latestSettablePolicyRow(data, { scope: 'CORPS', targetId: 'O-1' })?.targetId).toBe('O-1');
        expect(latestSettablePolicyRow(data, { scope: 'CORPS', targetId: 'O-2' })).toBeNull();
        expect(latestSettablePolicyRow(data, { scope: 'CORPS', targetId: 'O-9' })).toBeNull();
        expect(latestSettablePolicyRow(data, { scope: 'CORPS', targetId: '129' })).toBeNull();
        expect(latestSettablePolicyRow(data, { scope: 'COUNTY', targetId: '129' })?.name).toBe('양성현');
        expect(latestSettablePolicyRow(null, { scope: 'CORPS', targetId: 'O-1' })).toBeNull();
        expect(latestSettablePolicyRow({ ...data, status: 'NOT_READY' as never }, { scope: 'CORPS', targetId: 'O-1' })).toBeNull();
    });
});
