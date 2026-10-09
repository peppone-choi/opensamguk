import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, isIntakeQueued } from '@/lib/api';
import { FLOW_COMMANDS, flowCommand } from '@/lib/command-flow/catalog';
import { buildArgs, fetchCommandOptions, fromPolitical, type CommandOptions } from '@/lib/command-flow/options';
import type { PoliticalOption } from '@/lib/types';

afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
});

function ready(result: CommandOptions) {
    if (result.state !== 'READY') throw new Error(`Expected ready options, got ${result.state}`);
    return result;
}

describe('political options argument contract', () => {
    it('preserves the existing oath verdict while the new display correction is scoped to abdication', () => {
        const result = ready(fromPolitical([{ inputId: 'action.oath', available: false,
            code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.',
            targets: [{ generalId: 8, name: '관우', available: false, code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.' }] }], 'action.oath'));
        expect(result).toMatchObject({ available: false, code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.' });
    });
    it('submits available founding with an empty body even when the server sends empty targets', async () => {
        expect(flowCommand('action.foundState')?.args).toEqual([]);
        const options = ready(fromPolitical([
            { inputId: 'action.foundState', available: true, targets: [] },
        ], 'action.foundState'));
        expect(options.fields).toEqual([]);
        const built = buildArgs(options, { targetGeneralId: '99' });
        expect(built).toEqual({ ok: true, args: {} });
        if (!built.ok) throw new Error('Founding must not require a person');
        const fetch = vi.fn().mockResolvedValue({
            ok: true, status: 202, statusText: 'Accepted',
            json: async () => ({ status: 'AVAILABLE', requestId: 'founding-request', turnIdx: 4 }),
        });
        vi.stubGlobal('fetch', fetch);
        expect(isIntakeQueued(await api.command('action.foundState', built.args, 7, 4))).toBe(true);
        const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
        expect(url).toBe('/api/game/api/command/action.foundState?generalId=7&turnIdx=4');
        expect(JSON.parse(init.body as string)).toEqual({});
    });

    it('keeps the server rejection and reason for unavailable founding', () => {
        const result = ready(fromPolitical([
            { inputId: 'action.foundState', available: false, code: 'NOT_LORD', reason: '주공만 건국할 수 있습니다.', targets: [] },
        ], 'action.foundState'));
        expect(result).toMatchObject({ available: false, code: 'NOT_LORD', reason: '주공만 건국할 수 있습니다.', fields: [] });
    });

    it('submits available resignation without a target and preserves unavailable reason', async () => {
        expect(flowCommand('action.resign')?.args).toEqual([]);
        const read = vi.spyOn(api, 'politicalOptions').mockResolvedValue([
            { inputId: 'action.resign', available: true },
        ]);
        const options = ready(await fetchCommandOptions('action.resign', 7));
        expect(read).toHaveBeenCalledWith(7);
        expect(options.fields).toEqual([]);
        const built = buildArgs(options, { targetGeneralId: '99' });
        expect(built).toEqual({ ok: true, args: {} });
        if (!built.ok) throw new Error('Resignation must not require a target');

        const fetch = vi.fn().mockResolvedValue({
            ok: true, status: 202, statusText: 'Accepted',
            json: async () => ({ status: 'AVAILABLE', requestId: 'resign-request', turnIdx: 4 }),
        });
        vi.stubGlobal('fetch', fetch);
        expect(isIntakeQueued(await api.command('action.resign', built.args, 7, 4))).toBe(true);
        const [url, init] = fetch.mock.calls[0] as [string, RequestInit];
        expect(url).toBe('/api/game/api/command/action.resign?generalId=7&turnIdx=4');
        expect(JSON.parse(init.body as string)).toEqual({});

        const denied = ready(fromPolitical([
            { inputId: 'action.resign', available: false, code: 'NOT_A_SUBJECT', reason: '소속 장수가 아닙니다.' },
        ], 'action.resign'));
        expect(denied).toMatchObject({ available: false, code: 'NOT_A_SUBJECT', reason: '소속 장수가 아닙니다.', fields: [] });
    });

    it.each(['action.abdicate', 'action.oath'] as const)('still requires a person for %s when targets are empty or absent', (inputId) => {
        for (const option of [{ inputId, available: true, targets: [] }, { inputId, available: true }]) {
            const result = ready(fromPolitical([option], inputId));
            expect(result.fields.map(field => field.key)).toEqual(['targetGeneralId']);
            expect(buildArgs(result, {})).toEqual({ ok: false, missing: ['targetGeneralId'] });
            expect(buildArgs(result, { targetGeneralId: '8' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
        }
    });

    it.each(['action.abdicate', 'action.oath'] as const)('accepts only an available numeric candidate for %s', (inputId) => {
        const option: PoliticalOption = { inputId, available: true, targets: [
            { generalId: 8, name: '대상', available: true },
            { generalId: 9, name: '불가 대상', available: false, reason: '같은 세력이어야 합니다.' },
        ] };
        const result = ready(fromPolitical([option], inputId));
        expect(buildArgs(result, { targetGeneralId: '8' })).toEqual({ ok: true, args: { targetGeneralId: 8 } });
        expect(buildArgs(result, { targetGeneralId: '9' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
        expect(buildArgs(result, { targetGeneralId: '10' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
    });

    it('does not create options for a missing server row', () => {
        expect(fromPolitical([], 'action.foundState')).toEqual({ state: 'UNREADABLE', status: 'NO_ROW' });
    });

    it('does not infer empty arguments for an input outside the command catalog', () => {
        const inputId = 'action.unknownPolitical' as PoliticalOption['inputId'];
        expect(fromPolitical([{ inputId, available: true, targets: [] }], inputId))
            .toEqual({ state: 'UNREADABLE', status: 'NO_ROW' });
    });

    it('reads delivered founding through the existing options endpoint without adding target fields', async () => {
        const read = vi.spyOn(api, 'politicalOptions').mockResolvedValue([
            { inputId: 'action.foundState', available: true, targets: [] },
        ]);
        const result = ready(await fetchCommandOptions('action.foundState', 7));
        expect(read).toHaveBeenCalledWith(7);
        expect(buildArgs(result, {})).toEqual({ ok: true, args: {} });
    });

    it('keeps planned political actions unavailable without calling the server', async () => {
        const read = vi.spyOn(api, 'politicalOptions');
        const planned = FLOW_COMMANDS.filter(command => command.category === '나라' && command.delivery === 'PLANNED');
        expect(planned.length).toBeGreaterThan(0);
        for (const command of planned) {
            const result = await fetchCommandOptions(command.inputId, 7);
            expect(result).toEqual({ state: 'PLANNED' });
            expect(buildArgs(result, {})).toEqual({ ok: false, missing: ['options'] });
        }
        expect(read).not.toHaveBeenCalled();
    });
});

describe('abdication candidate reasons', () => {
    const sameNation = { generalId: 8, name: '관우', available: false, code: 'SAME_NATION_REQUIRED', reason: '같은 세력의 장수를 선택해 주세요.' };
    const noConsent = { generalId: 9, name: '장비', available: false, code: 'CONSENT_REQUIRED', reason: '대상 장수의 수락이 필요합니다.' };
    const row = (targets: NonNullable<PoliticalOption['targets']>): PoliticalOption => ({
        inputId: 'action.abdicate', available: false, targets,
        code: targets[0]?.code ?? 'CONSENT_REQUIRED', reason: targets[0]?.reason ?? '대상 장수의 수락이 필요합니다.',
    });

    it('keeps each failure on its candidate and a neutral top-level reason regardless of order', () => {
        for (const targets of [[sameNation, noConsent], [noConsent, sameNation]]) {
            const result = ready(fromPolitical([row(targets)], 'action.abdicate'));
            expect(result).toMatchObject({ available: false, code: null, reason: '지금 선택할 수 있는 대상 장수가 없습니다.' });
            expect(result.fields[0].candidates).toEqual(expect.arrayContaining([
                expect.objectContaining({ value: '8', available: false, reason: '같은 세력의 장수를 선택해 주세요.' }),
                expect.objectContaining({ value: '9', available: false, reason: '대상 장수의 수락이 필요합니다.' }),
            ]));
            expect(buildArgs(result, { targetGeneralId: '9' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
        }
    });

    it('lets only the eligible heir through in a mixed list', () => {
        const result = ready(fromPolitical([{ inputId: 'action.abdicate', available: true,
            targets: [sameNation, { generalId: 10, name: '조운', available: true }, noConsent] }], 'action.abdicate'));
        expect(result).toMatchObject({ available: true, code: null, reason: null });
        expect(buildArgs(result, { targetGeneralId: '10' })).toEqual({ ok: true, args: { targetGeneralId: 10 } });
        expect(buildArgs(result, { targetGeneralId: '8' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
    });

    it('keeps the actor-wide reason when the lord status blocks every candidate', () => {
        const notLord = (generalId: number) => ({ generalId, name: `장수${generalId}`, available: false,
            code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.' });
        const result = ready(fromPolitical([row([notLord(8), notLord(9)])], 'action.abdicate'));
        expect(result).toMatchObject({ available: false, code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.' });
    });

    it('keeps the state-unavailable reason when the server could not read the world', () => {
        const result = ready(fromPolitical([{ inputId: 'action.abdicate', available: false, targets: [],
            code: 'STATE_UNAVAILABLE', reason: '현재 세력·명망 상태를 확인할 수 없습니다.' }], 'action.abdicate'));
        expect(result).toMatchObject({ available: false, code: 'STATE_UNAVAILABLE', reason: '현재 세력·명망 상태를 확인할 수 없습니다.' });
    });

    it('does not rewrite no-target political commands', () => {
        const result = ready(fromPolitical([
            { inputId: 'action.dissolve', available: false, code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.', targets: [] },
        ], 'action.dissolve'));
        expect(result).toMatchObject({ available: false, code: 'NOT_LORD', reason: '주공만 세력을 해산할 수 있습니다.', fields: [] });
    });

    it('rereads after a consent change instead of reusing the previous verdict', async () => {
        const read = vi.spyOn(api, 'politicalOptions')
            .mockResolvedValueOnce([row([sameNation, noConsent])])
            .mockResolvedValueOnce([{ inputId: 'action.abdicate', available: true,
                targets: [sameNation, { generalId: 9, name: '장비', available: true }] }]);
        const before = ready(await fetchCommandOptions('action.abdicate', 7));
        expect(buildArgs(before, { targetGeneralId: '9' })).toEqual({ ok: false, missing: ['targetGeneralId'] });
        const after = ready(await fetchCommandOptions('action.abdicate', 7));
        expect(after).toMatchObject({ available: true, code: null, reason: null });
        expect(buildArgs(after, { targetGeneralId: '9' })).toEqual({ ok: true, args: { targetGeneralId: 9 } });
        expect(read).toHaveBeenCalledTimes(2);
    });
});
