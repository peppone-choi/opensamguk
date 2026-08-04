import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CommandModal from '@/components/CommandModal';

const mocks = vi.hoisted(() => ({
    availableCommands: vi.fn(),
    command: vi.fn(),
    nationBulk: vi.fn(),
    mapPreview: vi.fn(),
    pollCommandResult: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    return {
        ...actual,
        api: {
            ...actual.api,
            availableCommands: mocks.availableCommands,
            command: mocks.command,
            commandQueue: {
                ...actual.api.commandQueue,
                nationBulk: mocks.nationBulk,
            },
            mapPreview: mocks.mapPreview,
        },
        pollCommandResult: mocks.pollCommandResult,
    };
});

describe('CommandModal ordered form specs', () => {
    beforeEach(() => {
        mocks.availableCommands.mockReset();
        mocks.command.mockReset();
        mocks.nationBulk.mockReset();
        mocks.mapPreview.mockReset();
        mocks.pollCommandResult.mockReset();
    });

    it('submits every field from a pinned non-aggression proposal form', async () => {
        mocks.availableCommands.mockResolvedValueOnce({
            result: true,
            commandTable: [{
                category: '외교',
                values: [{
                    value: 'che_불가침제의',
                    simpleName: '불가침 제의',
                    title: '불가침 제의',
                    compensation: 0,
                    possible: true,
                    reqArg: true,
                    form: {
                        fields: [
                            {
                                name: 'destNationID',
                                valueType: 'int',
                                control: 'select',
                                optionSource: 'nations',
                                required: true,
                            },
                            {
                                name: 'year',
                                valueType: 'int',
                                control: 'number',
                                required: true,
                            },
                            {
                                name: 'month',
                                valueType: 'int',
                                control: 'number',
                                required: true,
                                min: 1,
                                max: 12,
                            },
                        ],
                    },
                }],
            }],
        });
        mocks.mapPreview.mockResolvedValueOnce({
            result: true,
            nations: [
                { id: 1, name: '촉' },
                { id: 2, name: '위' },
            ],
        });
        mocks.nationBulk.mockResolvedValueOnce({
            status: 'AVAILABLE',
            requestId: 'non-aggression-applied',
        });
        mocks.pollCommandResult.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'non-aggression-applied',
            ok: true,
            type: 'che_불가침제의',
            result: {},
        });

        render(
            <CommandModal
                generalId={7}
                nationId={1}
                pinnedCommand="che_불가침제의"
                pinnedLabel="불가침 제의"
                pinnedArgType="nation"
                resolvePinnedFromCatalog
                isNationCommand
                onClose={vi.fn()}
                onToast={vi.fn()}
            />,
        );

        const [year, month] = await screen.findAllByRole('spinbutton');
        fireEvent.click(await screen.findByRole('option', { name: '위' }));
        fireEvent.change(year, { target: { value: '200' } });
        fireEvent.change(month, { target: { value: '12' } });
        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        await waitFor(() => expect(mocks.nationBulk).toHaveBeenCalledWith(7, [{
            action: 'che_불가침제의',
            turnList: [0],
            arg: {
                destNationID: 2,
                year: 200,
                month: 12,
            },
        }]));
        expect(mocks.availableCommands).toHaveBeenCalledWith(7);
    });

    it('fails closed when the catalog cannot resolve a pinned compound command', async () => {
        mocks.availableCommands.mockRejectedValueOnce(new Error('unavailable'));

        render(
            <CommandModal
                generalId={7}
                nationId={1}
                pinnedCommand="che_불가침제의"
                pinnedLabel="불가침 제의"
                pinnedArgType="nation"
                resolvePinnedFromCatalog
                isNationCommand
                onClose={vi.fn()}
                onToast={vi.fn()}
            />,
        );

        expect(await screen.findByText('명령 정보를 불러오지 못했습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '예약' })).not.toBeInTheDocument();
        expect(mocks.nationBulk).not.toHaveBeenCalled();
    });

    it('fails closed when the catalog omits a pinned compound command', async () => {
        mocks.availableCommands.mockResolvedValueOnce({
            result: true,
            commandTable: [{
                category: '외교',
                values: [],
            }],
        });

        render(
            <CommandModal
                generalId={7}
                nationId={1}
                pinnedCommand="che_불가침제의"
                pinnedLabel="불가침 제의"
                pinnedArgType="nation"
                resolvePinnedFromCatalog
                isNationCommand
                onClose={vi.fn()}
                onToast={vi.fn()}
            />,
        );

        expect(await screen.findByText('명령 정보를 불러오지 못했습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '예약' })).not.toBeInTheDocument();
        expect(mocks.nationBulk).not.toHaveBeenCalled();
    });

    it('fails closed when the pinned compound command row omits its form', async () => {
        mocks.availableCommands.mockResolvedValueOnce({
            result: true,
            commandTable: [{
                category: '외교',
                values: [{
                    value: 'che_불가침제의',
                    simpleName: '불가침 제의',
                    title: '불가침 제의',
                    compensation: 0,
                    possible: true,
                    reqArg: true,
                }],
            }],
        });

        render(
            <CommandModal
                generalId={7}
                nationId={1}
                pinnedCommand="che_불가침제의"
                pinnedLabel="불가침 제의"
                pinnedArgType="nation"
                resolvePinnedFromCatalog
                isNationCommand
                onClose={vi.fn()}
                onToast={vi.fn()}
            />,
        );

        expect(await screen.findByText('명령 정보를 불러오지 못했습니다.')).toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '예약' })).not.toBeInTheDocument();
        expect(mocks.nationBulk).not.toHaveBeenCalled();
    });

    it('submits every field from a compound resource form in server order', async () => {
        mocks.availableCommands.mockResolvedValueOnce({
            result: true,
            commandTable: [{
                category: '국가',
                values: [{
                    value: 'che_헌납',
                    simpleName: '헌납',
                    title: '헌납',
                    compensation: 0,
                    possible: true,
                    reqArg: true,
                    form: {
                        fields: [
                            {
                                name: 'isGold',
                                valueType: 'bool',
                                control: 'toggle',
                                optionSource: 'resourceKinds',
                                required: true,
                            },
                            {
                                name: 'amount',
                                valueType: 'int',
                                control: 'amount',
                                required: true,
                                min: 100,
                                max: 1000000,
                            },
                        ],
                    },
                }],
            }],
        });
        mocks.command.mockResolvedValueOnce({ status: 'AVAILABLE', requestId: 'compound-applied' });
        mocks.pollCommandResult.mockResolvedValueOnce({
            status: 'RESOLVED',
            requestId: 'compound-applied',
            ok: true,
            type: 'che_헌납',
            result: {},
        });

        render(<CommandModal generalId={7} turnIdx={3} onClose={vi.fn()} onToast={vi.fn()} />);

        fireEvent.click(await screen.findByRole('button', { name: '헌납' }));
        fireEvent.click(screen.getByRole('checkbox', { name: '금 사용' }));
        fireEvent.change(screen.getByRole('spinbutton'), { target: { value: '500' } });
        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        await waitFor(() => expect(mocks.command).toHaveBeenCalledWith(
            'che_헌납',
            { isGold: false, amount: 500 },
            7,
            3,
        ));
        expect(mocks.pollCommandResult).toHaveBeenCalledWith('compound-applied');
    });

    it('keeps a compound form open until all required select values are present', async () => {
        mocks.availableCommands.mockResolvedValueOnce({
            result: true,
            commandTable: [{
                category: '개인',
                values: [{
                    value: 'che_숙련전환',
                    simpleName: '숙련전환',
                    title: '숙련전환',
                    compensation: 0,
                    possible: true,
                    reqArg: true,
                    form: {
                        fields: [
                            {
                                name: 'srcArmType',
                                valueType: 'int',
                                control: 'select',
                                optionSource: 'armTypes',
                                required: true,
                            },
                            {
                                name: 'destArmType',
                                valueType: 'int',
                                control: 'select',
                                optionSource: 'armTypes',
                                required: true,
                            },
                        ],
                    },
                }],
            }],
        });

        render(<CommandModal generalId={7} onClose={vi.fn()} onToast={vi.fn()} />);

        fireEvent.click(await screen.findByRole('button', { name: '숙련전환' }));
        fireEvent.click(screen.getByRole('button', { name: '예약' }));

        expect(await screen.findByText('필수 값을 입력해 주세요.')).toBeInTheDocument();
        expect(mocks.command).not.toHaveBeenCalled();
    });
});
