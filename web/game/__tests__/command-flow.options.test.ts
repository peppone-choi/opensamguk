// 대상 후보 어댑터 — 옵션 응답을 한 모양으로 펴고, 초안을 지금 폼과 같은 서버 인자로 되돌리는지.
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '@/lib/api';
import {
    amountMax, buildArgs, fetchCommandOptions, fromDeploy, fromDirect, fromEnlist, fromMilitary, fromPeople,
    fromPersonal, fromPolitical, fromRoadForts, fromScout, fromSieges, fromTransfer, fromTravel, type CommandOptions,
} from '@/lib/command-flow/options';

afterEach(() => vi.restoreAllMocks());

const readyOf = (o: CommandOptions) => {
    if (o.state !== 'READY') throw new Error(`not ready: ${o.state}`);
    return o;
};

describe('옵션 → 필드 · 후보', () => {
    it('병종 전환은 숫자 label 대신 실제 병종 이름을 표시하고 인자/불가 판정을 보존한다', () => {
        const payload = { inputId: 'action.convertProficiency' as const, available: true, choices: [
            { label: '3번 부곡 → 1100번 병종', arguments: { bugokId: 3, crewTypeId: 1100 }, available: true },
            { label: '3번 부곡 → 9999번 병종', arguments: { bugokId: 3, crewTypeId: 9999 }, available: false, reason: '편제할 수 없습니다' },
        ] };
        const o = fromDirect(payload, { cities: {}, units: { '1100': '창병' } });
        expect(o.fields[0].candidates[0].label).toBe('부곡 #3 — 창병으로 병종 바꿔 익히기');
        expect(o.fields[0].candidates[1]).toMatchObject({ available: false, reason: '편제할 수 없습니다' });
        expect(o.fields[0].candidates[1].label).not.toContain('9999');
        expect(buildArgs(o, { choice: '0' })).toEqual({ ok: true, args: { bugokId: 3, crewTypeId: 1100 } });
        expect(buildArgs(o, { choice: '1' }).ok).toBe(false);
        expect(payload.choices[0].label).toBe('3번 부곡 → 1100번 병종');
    });
    it('이동: 목적 구역 후보를 서버 가능 여부 · 사유 그대로 옮긴다', () => {
        const o = fromTravel({
            inputId: 'action.move', available: true,
            destinations: [
                { provinceId: 'P-1', name: '영천', available: true },
                { provinceId: 'P-2', name: '양적', available: false, reason: '길이 막혔습니다' },
            ],
        });
        expect(o.fields).toHaveLength(1);
        expect(o.fields[0]).toMatchObject({ key: 'destinationProvinceId', kind: 'province' });
        expect(o.fields[0].candidates[1]).toEqual({ value: 'P-2', label: '양적', available: false, reason: '길이 막혔습니다', detail: '주문 불가', rangeLabel: '주문 불가' });
        expect(buildArgs(o, { destinationProvinceId: 'P-1' })).toEqual({ ok: true, args: { destinationProvinceId: 'P-1' } });
        expect(buildArgs(o, { destinationProvinceId: 'P-2' })).toEqual({ ok: false, missing: ['destinationProvinceId'] });
    });

    it('귀환은 인자가 없다', () => {
        const o = fromTravel({ inputId: 'action.return', available: true, destinations: [] });
        expect(o.fields).toEqual([]);
        expect(buildArgs(o, {})).toEqual({ ok: true, args: {} });
    });

    it('군사: 서버가 준 수치만 미리보기에 — 없는 값은 줄을 만들지 않는다', () => {
        const o = fromMilitary({ inputId: 'action.train', available: true, countyName: '허현', training: 40, trainingAfter: 52 });
        expect(o.place).toBe('허현');
        expect(o.preview).toEqual([{ label: '훈련', now: 40, after: 52 }]);
    });

    it('출병: 부곡 여러 개 + 목적 구역, 부곡 번호는 정렬해서 보낸다', () => {
        const o = fromDeploy({
            available: true, maxReservedTurns: 12,
            bugoks: [{ id: 7, name: '청주병', troops: 900, available: true }, { id: 3, name: '단양병', troops: 400, available: true },
                { id: 9, name: '부상병', troops: 50, available: false, reason: '다쳤습니다' }],
            destinations: [{ provinceId: 'P-4', name: '진류', available: true }],
        });
        expect(buildArgs(o, { bugokIds: [7, 3], destinationProvinceId: 'P-4' }))
            .toEqual({ ok: true, args: { bugokIds: [3, 7], destinationProvinceId: 'P-4' } });
        expect(buildArgs(o, { bugokIds: [9], destinationProvinceId: 'P-4' })).toEqual({ ok: false, missing: ['bugokIds'] });
        expect(buildArgs(o, { bugokIds: [] })).toEqual({ ok: false, missing: ['bugokIds', 'destinationProvinceId'] });
    });

    it('단련 · 은퇴: 능력 이름 · 뒤를 이을 사람(숫자로 보낸다)', () => {
        const train = fromPersonal({ inputId: 'action.selfTrain', available: true, stats: [{ stat: 'intelligence', available: true }] });
        expect(train.fields[0].candidates[0].label).toBe('지력');
        expect(buildArgs(train, { stat: 'intelligence' })).toEqual({ ok: true, args: { stat: 'intelligence' } });
        const retire = fromPersonal({ inputId: 'action.retire', available: true, successors: [{ generalId: 12, name: '조비', available: true }] });
        expect(buildArgs(retire, { successorGeneralId: '12' })).toEqual({ ok: true, args: { successorGeneralId: 12 } });
    });

    it('등용: 대상 인물 — NPC도 서버 후보면 그대로 후보다', () => {
        const o = fromPeople({ inputId: 'action.employ', available: true, targets: [{ generalId: 501, name: '[NPC]', available: true }] });
        expect(buildArgs(o, { targetGeneralId: '501' })).toEqual({ ok: true, args: { targetGeneralId: 501 } });
        const search = fromPeople({ inputId: 'action.search', available: true, undiscoveredCount: 4, targets: [] });
        expect(search.preview).toEqual([{ label: '아직 못 찾은 인물', now: 4, after: null }]);
    });

    it('정치: 목록에서 제 행을 찾고, 행이 없으면 지어내지 않는다', () => {
        const list = [{ inputId: 'action.oath' as const, available: true, targets: [{ generalId: 2, name: '[군주]', available: true }] }];
        expect(buildArgs(readyOf(fromPolitical(list, 'action.oath')), { targetGeneralId: '2' })).toEqual({ ok: true, args: { targetGeneralId: 2 } });
        expect(fromPolitical(list, 'action.foundState')).toEqual({ state: 'UNREADABLE', status: 'NO_ROW' });
    });

    it('증여: 받을 사람 · 자원 · 수량 — 수량 상한은 고른 자원의 최대', () => {
        const o = fromTransfer({
            inputId: 'action.gift', available: true,
            resources: [{ resource: 'GRAIN', available: true, maxAmount: 300 }, { resource: 'MONEY', available: false, maxAmount: 0, reason: '금이 없습니다' }],
            targets: [{ generalId: 8, name: '[인물]', available: true }],
        });
        expect(o.fields.map(f => f.key)).toEqual(['targetGeneralId', 'resource', 'amount']);
        expect(o.fields[1].candidates[0].label).toBe('쌀');
        expect(amountMax(o, o.fields[2], { resource: 'GRAIN' })).toBe(300);
        expect(buildArgs(o, { targetGeneralId: '8', resource: 'GRAIN', amount: 120 }))
            .toEqual({ ok: true, args: { targetGeneralId: 8, resource: 'GRAIN', amount: 120 } });
        expect(buildArgs(o, { targetGeneralId: '8', resource: 'GRAIN', amount: 301 })).toEqual({ ok: false, missing: ['amount'] });
        expect(buildArgs(o, { targetGeneralId: '8', resource: 'GRAIN', amount: 0 })).toEqual({ ok: false, missing: ['amount'] });
    });

    it('선택지형: 서버 arguments를 그대로, 수송은 수량을 덧붙인다', () => {
        const trade = fromDirect({
            inputId: 'action.tradeGrain', available: true,
            choices: [{ label: '쌀 100 사기', arguments: { side: 'BUY', amount: 100 }, available: true }],
        });
        expect(buildArgs(trade, { choice: '0' })).toEqual({ ok: true, args: { side: 'BUY', amount: 100 } });
        const transport = fromDirect({
            inputId: 'action.transport', available: true,
            choices: [{ label: '허현으로', arguments: { destinationCountyId: 31, resource: 'GRAIN' }, available: true, maxAmount: 80 }],
        });
        expect(buildArgs(transport, { choice: '0', amount: 80 }))
            .toEqual({ ok: true, args: { destinationCountyId: 31, resource: 'GRAIN', amount: 80 } });
        expect(buildArgs(transport, { choice: '0', amount: 81 })).toEqual({ ok: false, missing: ['amount'] });
    });

    it('입관: 방식(+대상) — 막힌 방식은 사유와 함께 못 고른다', () => {
        const o = fromEnlist({
            result: true, inputId: 'action.enlist', maxReservedTurns: 12,
            options: [
                { mode: 'RANDOM', label: '아무 세력', availability: { status: 'AVAILABLE' } },
                { mode: 'NATION', targetId: 3, label: '[세력]', availability: { status: 'AVAILABLE' } },
                { mode: 'GENERAL', targetId: 44, label: '[인물]', availability: { status: 'BLOCKED', reason: '받아 주지 않습니다' } },
            ],
        });
        expect(buildArgs(o, { choice: 'RANDOM' })).toEqual({ ok: true, args: { mode: 'RANDOM' } });
        expect(buildArgs(o, { choice: 'NATION:3' })).toEqual({ ok: true, args: { mode: 'NATION', targetId: 3 } });
        expect(buildArgs(o, { choice: 'GENERAL:44' })).toEqual({ ok: false, missing: ['choice'] });
        expect(o.fields[0].candidates[2].reason).toBe('받아 주지 않습니다');
    });

    it('첩보: 읽기가 준비 안 됐으면 UNREADABLE — 후보를 지어내지 않는다', () => {
        expect(fromScout({ status: 'WRONG_RULE_PROFILE' })).toEqual({ state: 'UNREADABLE', status: 'WRONG_RULE_PROFILE' });
        const o = readyOf(fromScout({ status: 'READY', options: [{ no: 1, id: 'C-12', name: '영천군', tier: 'NONE' as never, available: true, ageTurns: 3 }] }));
        expect(o.fields[0].candidates[0].detail).toBe('3순 전에 봄');
        expect(buildArgs(o, { commanderyId: 'C-12' })).toEqual({ ok: true, args: { commanderyId: 'C-12' } });
    });

    it('강공은 서버가 허용한 실제 현 ID를 숫자로 보내고 항복 권고는 무인자다', () => {
        const siege = (generalId: number, canAct: boolean, canAssault = true) => ({
            countyId: 77, countyName: '밀현', status: 'ACTIVE', besieger: { generalId }, canAct,
            canAssault, assaultCode: canAssault ? null : 'ASSAULT_NOT_READY',
            assaultReason: canAssault ? null : '포위한 지 한 달(3순)이 지나야 강공할 수 있습니다.',
        }) as never;
        const none = readyOf(fromSieges({ status: 'READY', sieges: [siege(99, true)] }, 1, 'action.assault'));
        expect(none).toMatchObject({ available: false, reason: '에워싼 성이 없습니다' });
        const mine = readyOf(fromSieges({ status: 'READY', sieges: [siege(1, true)] }, 1, 'action.assault'));
        expect(mine).toMatchObject({ available: true, place: '밀현', reason: null });
        expect(mine.fields[0]).toMatchObject({ key: 'targetCountyId', kind: 'county', candidates: [{ value: '77', label: '밀현', available: true }] });
        expect(buildArgs(mine, {})).toEqual({ ok: false, missing: ['targetCountyId'] });
        expect(buildArgs(mine, { targetCountyId: '77' })).toEqual({ ok: true, args: { targetCountyId: 77 } });
        const early = readyOf(fromSieges({ status: 'READY', sieges: [siege(1, true, false)] }, 1, 'action.assault'));
        expect(early).toMatchObject({ available: false, code: 'ASSAULT_NOT_READY' });
        expect(buildArgs(early, { targetCountyId: '77' })).toEqual({ ok: false, missing: ['targetCountyId'] });
        const demand = readyOf(fromSieges({ status: 'READY', sieges: [siege(1, true)] }, 1, 'action.demandSurrender'));
        expect(demand).toMatchObject({ available: true, place: '밀현' });
        expect(buildArgs(demand, {})).toEqual({ ok: true, args: {} });
    });

    it('보루 포위: 에울 수 있는 보루만 후보', () => {
        const fort = (id: string, canBesiege: boolean) => ({ id, provinceId: 'P-5', wall: 30, garrison: 200, canBesiege }) as never;
        const o = readyOf(fromRoadForts({ status: 'READY', roadMode: true, forts: [fort('F-1', true), fort('F-2', false)], gates: [] }));
        expect(o.fields[0].candidates.map(c => c.value)).toEqual(['F-1']);
        expect(buildArgs(o, { fortId: 'F-1' })).toEqual({ ok: true, args: { fortId: 'F-1' } });
    });
});

describe('서버에서 읽기', () => {
    it('병종 전환은 활성 서버 const의 registry 이름을 가져오고 이름 읽기 실패도 숫자 병종으로 돌아가지 않는다', async () => {
        vi.spyOn(api, 'legacyDirectOptions').mockResolvedValue({ inputId: 'action.convertProficiency', available: true, choices: [
            { label: '3번 부곡 → 1100번 병종', arguments: { bugokId: 3, crewTypeId: 1100 }, available: true },
        ] });
        const registry = vi.spyOn(api, 'gameConst').mockResolvedValue({ gameUnitConst: [{ id: 1100, name: '창병' }] } as never);
        const named = readyOf(await fetchCommandOptions('action.convertProficiency', 5));
        expect(named.fields[0].candidates[0].label).toContain('창병');
        registry.mockRejectedValue(new Error('503'));
        const unknown = readyOf(await fetchCommandOptions('action.convertProficiency', 5));
        expect(unknown.fields[0].candidates[0].label).toContain('병종 이름 확인 불가');
        expect(unknown.fields[0].candidates[0].label).not.toContain('1100');
    });
    it('준비 중(PLANNED) 명령은 서버를 부르지 않는다', async () => {
        const spy = vi.spyOn(api, 'personalOptions');
        await expect(fetchCommandOptions('action.retire', 1)).resolves.toEqual({ state: 'PLANNED' });
        await expect(fetchCommandOptions('없는.명령', 1)).resolves.toEqual({ state: 'PLANNED' });
        expect(spy).not.toHaveBeenCalled();
    });

    it('명령마다 제 옵션 읽기로 간다', async () => {
        const travel = vi.spyOn(api, 'travelOptions').mockResolvedValue({ inputId: 'action.forcedMarch', available: true, destinations: [] });
        const enlist = vi.spyOn(api, 'enlistmentOptions').mockResolvedValue({ result: true, inputId: 'action.enlist', maxReservedTurns: 12, options: [] });
        expect((await fetchCommandOptions('action.forcedMarch', 5)).state).toBe('READY');
        expect(travel).toHaveBeenCalledWith('action.forcedMarch', 5);
        expect(await fetchCommandOptions('action.enlist', 5)).toMatchObject({ state: 'READY', available: false });
        expect(enlist).toHaveBeenCalledWith(5);
    });
});
