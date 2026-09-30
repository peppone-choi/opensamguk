// 입력 가능 여부 어댑터 · 원장 스냅숏 대조 — 스냅숏이 조용히 낡으면 여기서 깨진다.
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { availabilityOf, deliveryOf } from '@/lib/input-availability';
import { INPUT_DELIVERY } from '@/lib/input-delivery.generated';

const catalog: { inputId: string; deliveryState: string }[] =
    JSON.parse(readFileSync(resolve(__dirname, '../../../data/commands/input-catalog.json'), 'utf-8')).inputs;

describe('원장 스냅숏(input-delivery.generated.ts)', () => {
    it('원장과 행 · 전달 상태가 같다 — 다르면 python3 tools/web/gen_input_delivery.py 를 한 번 돌린다', () => {
        const fromCatalog = Object.fromEntries(catalog.map((r) => [r.inputId, r.deliveryState]));
        expect(INPUT_DELIVERY, '원장이 바뀌었습니다 — python3 tools/web/gen_input_delivery.py 를 돌리세요').toEqual(fromCatalog);
    });
});

describe('availabilityOf', () => {
    it('원장에 없는 입력은 null — 그리지 않는다', () => {
        expect(availabilityOf('court.allianceRequest')).toBeNull();
        expect(availabilityOf('toString')).toBeNull();
        expect(deliveryOf('constructor')).toBeNull();
    });

    it('PLANNED는 옵션이 가능이라 해도 「준비 중」', () => {
        expect(availabilityOf('action.retire', { options: { available: true } })).toEqual({ inputId: 'action.retire', status: 'NOT_DELIVERED' });
    });

    it('옵션 판정을 BLOCKED로 합친다 — 사유가 없으면 지어내지 않는다', () => {
        expect(availabilityOf('action.move', { options: { available: false, code: 'ENCOUNTER', reason: '조우 중입니다' } }))
            .toEqual({ inputId: 'action.move', status: 'BLOCKED', code: 'ENCOUNTER', reason: '조우 중입니다' });
        expect(availabilityOf('action.move', { options: { available: false, reason: null } })).toEqual({ inputId: 'action.move', status: 'BLOCKED' });
        expect(availabilityOf('action.move', { options: { available: true } })).toEqual({ inputId: 'action.move', status: 'AVAILABLE' });
        expect(availabilityOf('action.move')).toEqual({ inputId: 'action.move', status: 'AVAILABLE' });
    });

    it('서버 K6-01 행이 있으면 그것이 정본이다', () => {
        const server = { inputId: 'action.retire', status: 'AVAILABLE' as const };
        expect(availabilityOf('action.retire', { server })).toBe(server);
        // 다른 입력의 행은 쓰지 않는다.
        expect(availabilityOf('action.retire', { server: { inputId: 'action.move', status: 'AVAILABLE' } })?.status).toBe('NOT_DELIVERED');
    });
});
