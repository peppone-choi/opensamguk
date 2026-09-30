// 명령 흐름 상태 규칙(설계서 §2.1) — 명령별 초안 · 같은 종류 이어받기 · 예약 뒤 다음 빈 순 · URL · 순 띠.
import { describe, expect, it } from 'vitest';
import {
    afterReserved, currentDraft, dropInvalid, firstEmptySlot, initialFlow, selectCommand, selectSlot, setArg,
} from '@/lib/command-flow/flow-state';
import { parseFlowQuery, parseTarget, withFlowQuery } from '@/lib/command-flow/url';
import { buildStrip, filledSet, slotLabel } from '@/lib/command-flow/slots';

describe('명령별 초안', () => {
    it('명령을 바꿔도 적던 값이 명령마다 남는다', () => {
        let s = initialFlow(3, 'action.selfTrain');
        s = setArg(s, 'stat', 'strength');
        s = selectCommand(s, 'action.farm');
        s = selectCommand(s, 'action.selfTrain');
        expect(currentDraft(s)).toEqual({ stat: 'strength' });
        expect(s.slot).toBe(3);
    });

    it('같은 종류 인자(목적 구역)는 이어받는다 — 이동 → 강행 → 출병', () => {
        let s = initialFlow(0, 'action.move');
        s = setArg(s, 'destinationProvinceId', 'P-1203');
        s = selectCommand(s, 'action.forcedMarch');
        expect(currentDraft(s).destinationProvinceId).toBe('P-1203');
        expect(s.carried).toEqual(['destinationProvinceId']);
        s = selectCommand(s, 'action.deploy');
        expect(currentDraft(s).destinationProvinceId).toBe('P-1203');
    });

    it('이미 그 명령에 적은 값은 이어받기로 덮지 않는다', () => {
        let s = initialFlow(0, 'action.deploy');
        s = setArg(s, 'destinationProvinceId', 'A');
        s = selectCommand(s, 'action.move');
        s = setArg(s, 'destinationProvinceId', 'B');
        s = selectCommand(s, 'action.deploy');
        expect(currentDraft(s).destinationProvinceId).toBe('A');
    });

    it('이어받은 값이 새 명령의 후보에 없으면 비우고 알린다', () => {
        let s = initialFlow(0, 'action.move');
        s = setArg(s, 'destinationProvinceId', 'X');
        s = selectCommand(s, 'action.deploy');
        const r = dropInvalid(s, 'destinationProvinceId', v => v === 'Y');
        expect(r.dropped).toBe(true);
        expect(currentDraft(r.state).destinationProvinceId).toBeUndefined();
        expect(dropInvalid(r.state, 'destinationProvinceId', () => false).dropped).toBe(false);
    });

    it('명령보다 먼저 받은 대상(여기로 명령)은 처음 고른 명령이 이어받는다', () => {
        let s = initialFlow(2, null, { destinationProvinceId: 'P-7' });
        expect(currentDraft(s)).toEqual({});
        s = selectCommand(s, 'action.move');
        expect(currentDraft(s).destinationProvinceId).toBe('P-7');
        expect(s.carried).toEqual(['destinationProvinceId']);
    });

    it('순을 바꿔도 초안은 남는다', () => {
        let s = initialFlow(0, 'action.move');
        s = setArg(s, 'destinationProvinceId', 'Z');
        s = selectSlot(s, 7);
        expect(s.slot).toBe(7);
        expect(currentDraft(s).destinationProvinceId).toBe('Z');
        expect(selectSlot(s, 99).slot).toBe(0);
    });
});

describe('예약 뒤 다음 빈 순', () => {
    it('지금 순 뒤에서 먼저 찾고, 없으면 앞에서 찾는다', () => {
        const s = initialFlow(3, 'action.farm');
        expect(afterReserved(s, new Set([0, 1, 2, 3, 4])).slot).toBe(5);
        expect(afterReserved({ ...s, slot: 10 }, new Set([4, 10, 11])).slot).toBe(0);
    });

    it('12순이 다 차면 full — 닫지 않는다', () => {
        const all = new Set(Array.from({ length: 12 }, (_, i) => i));
        const r = afterReserved(initialFlow(5, 'action.farm'), all);
        expect(r.full).toBe(true);
        expect(r.slot).toBe(5);
        expect(firstEmptySlot(all)).toEqual({ slot: 0, full: true });
        expect(firstEmptySlot(new Set([0, 1]))).toEqual({ slot: 2, full: false });
    });
});

describe('URL', () => {
    it('?do · slot · target 을 읽고 모르는 값은 버린다', () => {
        const q = parseFlowQuery(new URLSearchParams('do=action.deploy&slot=4&target=province:P-1203'));
        expect(q).toEqual({ open: true, inputId: 'action.deploy', slot: 3, target: { kind: 'province', id: 'P-1203' } });
        const bad = parseFlowQuery(new URLSearchParams('do=che_징병&slot=13&target=city:1'));
        expect(bad).toEqual({ open: true, inputId: null, slot: null, target: null });
        expect(parseFlowQuery(new URLSearchParams('x=1')).open).toBe(false);
        expect(parseTarget('general:<script>')).toBeNull();
    });

    it('다른 쿼리는 두고 흐름 키만 바꾸고, 닫으면 지운다', () => {
        const base = new URLSearchParams('layer=vision&do=action.farm');
        const opened = withFlowQuery(base, { inputId: 'action.move', slot: 0, target: { kind: 'province', id: 'P-9' } });
        expect(opened.toString()).toBe('layer=vision&do=action.move&slot=1&target=province%3AP-9');
        expect(withFlowQuery(opened, null).toString()).toBe('layer=vision');
    });
});

describe('순 띠', () => {
    it('예약 링을 12칸으로 편다 — 표에 있는 명령은 표 이름, 모르는 코드는 서버 요약', () => {
        const strip = buildStrip({
            result: true, generalId: 1,
            slots: [
                { turnIdx: 0, action: 'action.tradeGrain', brief: '', arg: {} },
                { turnIdx: 2, action: 'someOldCode', brief: '옛 명령', arg: {} },
                { turnIdx: 30, action: 'action.farm', brief: '', arg: {} },
            ],
        });
        expect(strip).toHaveLength(12);
        expect(strip[0]).toMatchObject({ state: 'reserved', inputId: 'action.tradeGrain', name: '쌀 사고팔기' });
        expect(strip[1]).toMatchObject({ state: 'empty', name: null });
        expect(strip[2]).toMatchObject({ state: 'reserved', inputId: null, name: '옛 명령' });
        expect([...filledSet(strip)]).toEqual([0, 2]);
        expect(slotLabel(strip[1])).toBe('02순 — 빈 순');
    });

    it('읽기가 없으면 12칸 모두 빈 순(지어 채우지 않는다)', () => {
        expect(buildStrip(null).every(s => s.state === 'empty')).toBe(true);
    });
});
