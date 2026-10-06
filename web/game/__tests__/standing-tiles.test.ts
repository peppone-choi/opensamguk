import { describe, expect, it } from 'vitest';
import { allZero, standingTiles, type TileRead } from '../lib/standing-tiles';
import type { Policies, Posts, StratagemHand, Works } from '../lib/campaign-reads';
import type { DeployOptions, DispatchPendingResponse } from '../lib/types';

const ok = <T,>(data: T): TileRead<T> => ({ data, error: null });
const loading = <T,>(): TileRead<T> => ({ data: null, error: null });
const failed = <T,>(): TileRead<T> => ({ data: null, error: '서버가 잠시 응답하지 않습니다.' });

const deploy = (order: DeployOptions['order']): DeployOptions => ({ available: true, maxReservedTurns: 12, bugoks: [], destinations: [], order });
const card = (active: boolean, pending = false) => ({
    cardId: 1, generalId: null, name: '허저', relation: 'R', provinceId: null, placeable: true, blocked: null,
    active: active ? { post: 'p', postLabel: '현령', target: {}, state: 's' } : null, pending: pending ? { post: 'p', postLabel: '현령', target: {} } : null,
});
const posts = (cards: Posts['cards']): Posts => ({ status: 'READY', cards, posts: [] });
const policyRow = (active: boolean, pending = false) => ({
    countyId: 1, name: '양성현', commanderyName: null, active: active ? { policy: 'P', label: '권농' } : null,
    pending: pending ? { policy: 'Q', label: '휼민' } : null, effective: null, seat: null, settable: true, blocked: null,
});
const policies = (counties: unknown[], commanderies: unknown[] = []): Policies =>
    ({ status: 'READY', countyOptions: [], corpsOptions: [], defaultPolicy: null, counties, commanderies, corps: [] }) as unknown as Policies;
const workRow = (active: boolean, stopReason: string | null = null) => ({
    countyId: 1, provinceId: null, provinceIds: [], name: '양성현', commanderyName: null, warehouse: null,
    active: active ? { work: 'w', label: '성벽', percent: 10, remainingPhases: 3, remainingCost: {}, stopReasonText: stopReason ? '쌀 모자람' : null, startsAtNextBoundary: false, stopReason } : null,
});
const works = (counties: unknown[]): Works => ({ status: 'READY', counties }) as unknown as Works;
const hand = (n: number): StratagemHand => ({ status: 'READY', cards: Array.from({ length: n }, () => ({})) as never, handLimit: 5, canUse: true });
const dispatches = (rows: { status: string; targetId: number }[]): DispatchPendingResponse => ({ result: true, dispatches: rows as never });

const all = (over: Partial<Parameters<typeof standingTiles>[0]> = {}) => standingTiles({
    deploy: ok(deploy(null)), posts: ok(posts([])), policies: ok(policies([])), works: ok(works([])), hand: ok(hand(0)), dispatches: ok(dispatches([])), ...over,
}, 7);
const tile = (tiles: ReturnType<typeof all>, key: string) => tiles.find((t) => t.key === key)!.value;

describe('standingTiles — 맡겨 둔 일 6칸', () => {
    it('순서 · 목적지는 보드 그대로(출병 → 군단 · 배치 · 방침 · 공사 → 영지 칸 · 계책 · 발령 → 조정 발령)', () => {
        expect(all().map((t) => [t.label, t.slug])).toEqual([
            ['출병', 'corps'], ['배치', 'territory?view=placement'], ['방침', 'territory?view=policy'],
            ['공사', 'territory?view=work'], ['계책', 'stratagem'], ['발령', 'court?tab=orders'],
        ]);
    });

    it('읽는 중은 「—」(loading), 실패는 「?」(error) — 0 · 없음으로 그리지 않는다', () => {
        const tiles = all({ deploy: loading(), posts: failed(), policies: loading(), works: failed(), hand: loading(), dispatches: failed() });
        expect(tiles.map((t) => t.value.kind)).toEqual(['loading', 'error', 'loading', 'error', 'loading', 'error']);
        expect(allZero(tiles)).toBe(false);
    });

    it('READY 가 아니면(규칙 · 시야 밖) 수를 짓지 않는다', () => {
        const tiles = all({ posts: ok({ status: 'WRONG_RULE_PROFILE', cards: [], posts: [] } as unknown as Posts), hand: ok({ status: 'NOT_READY', cards: [], handLimit: 0, canUse: false }) });
        expect(tile(tiles, 'placement').kind).toBe('unavailable');
        expect(tile(tiles, 'stratagem').kind).toBe('unavailable');
    });

    it('출병 — 없으면 0, 있으면 「행군 중」, 멈춤 코드는 한글(멈춘 것만 rust), 모르는 코드는 원문 대신 「상태 확인 필요」', () => {
        expect(tile(all(), 'deploy')).toMatchObject({ value: '0', zero: true });
        expect(tile(all({ deploy: ok(deploy({ orderId: 'o', destinationProvinceId: 'A' })) }), 'deploy')).toMatchObject({ value: '행군 중', alert: false });
        expect(tile(all({ deploy: ok(deploy({ orderId: 'o', destinationProvinceId: 'A', stop: 'ENCOUNTER' })) }), 'deploy')).toMatchObject({ value: '조우 중단', alert: true });
        expect(tile(all({ deploy: ok(deploy({ orderId: 'o', destinationProvinceId: 'A', stop: 'XYZ' })) }), 'deploy')).toMatchObject({ value: '상태 확인 필요' });
    });

    it('배치 · 방침 — 걸린 수 + 바꿈 대기(군 방침도 센다), 공사 — 진행 수 + 멈춤(rust)', () => {
        const tiles = all({
            posts: ok(posts([card(true), card(true, true), card(false, true), card(false)])),
            policies: ok(policies([policyRow(true), policyRow(false, true)], [policyRow(true)])),
            works: ok(works([workRow(true), workRow(true, 'INSUFFICIENT_STOCK'), workRow(false)])),
        });
        expect(tile(tiles, 'placement')).toMatchObject({ value: '2', sub: '바꿈 대기 2' });
        expect(tile(tiles, 'policy')).toMatchObject({ value: '2', sub: '바꿈 대기 1' });
        expect(tile(tiles, 'work')).toMatchObject({ value: '2', sub: '1 멈춤', alert: true });
    });

    it('계책 — 손패 수, 발령 — 나에게 온 응답 필요는 rust 「응답 n」 + 내가 낸 대기', () => {
        const tiles = all({ hand: ok(hand(3)), dispatches: ok(dispatches([{ status: 'PENDING', targetId: 7 }, { status: 'PENDING', targetId: 9 }, { status: 'DONE', targetId: 7 }])) });
        expect(tile(tiles, 'stratagem')).toMatchObject({ value: '3', sub: '손패' });
        expect(tile(tiles, 'dispatch')).toMatchObject({ value: '응답 1', sub: '대기 1', alert: true });
        expect(tile(all({ dispatches: ok(dispatches([{ status: 'PENDING', targetId: 9 }])) }), 'dispatch')).toMatchObject({ value: '1', sub: '대기' });
    });

    it('칸마다 0 이면 allZero(안내를 붙인다), 바꿈 대기만 있어도 0 칸이 아니다', () => {
        expect(allZero(all())).toBe(true);
        expect(allZero(all({ posts: ok(posts([card(false, true)])) }))).toBe(false);
    });
});
