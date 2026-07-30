import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CommandModal from '@/components/CommandModal';

const mocks = vi.hoisted(() => ({
    availableCommands: vi.fn(),
    command: vi.fn(),
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
        },
        pollCommandResult: mocks.pollCommandResult,
    };
});

describe('CommandModal ordered form specs', () => {
    beforeEach(() => {
        mocks.availableCommands.mockReset();
        mocks.command.mockReset();
        mocks.pollCommandResult.mockReset();
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
