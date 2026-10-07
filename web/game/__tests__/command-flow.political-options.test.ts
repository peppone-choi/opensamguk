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
