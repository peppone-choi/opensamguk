import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import MessagePlate from '@/components/game/MessagePlate';
import type { MailboxMessage } from '@/types/game';

const POLL_INTERVAL_MS = 300;

const mocks = vi.hoisted(() => ({
    messageAccept: vi.fn(),
    messageDecline: vi.fn(),
    commandResult: vi.fn(),
}));

vi.mock('@/lib/api', async importOriginal => {
    const actual = await importOriginal<typeof import('@/lib/api')>();
    Object.assign(actual.api, mocks);
    return actual;
});

const baseMessage: MailboxMessage = {
    id: 7,
    mailbox: 10,
    type: 'private',
    src: 20,
    dest: 10,
    time: '2026-06-22T06:00:00Z',
    validUntil: '9999-12-31T00:00:00Z',
    message: '{"text":"raw json should not render"}',
    text: '해석된 서신 본문',
    srcTarget: {
        id: 20,
        name: '조조',
        nationId: 1,
        nation: '위',
        color: '#003399',
        icon: null,
    },
    destTarget: {
        id: 10,
        name: '순욱',
        nationId: 1,
        nation: '위',
        color: '#003399',
        icon: null,
    },
    option: null,
};

const actionableMessage: MailboxMessage = {
    ...baseMessage,
    type: 'diplomacy',
    option: { action: 'no_aggression' },
};

beforeEach(() => {
    const realSetTimeout = globalThis.setTimeout;
    vi.spyOn(globalThis, 'setTimeout').mockImplementation((handler, timeout, ...args) =>
        realSetTimeout(handler, timeout === POLL_INTERVAL_MS ? 0 : timeout, ...args),
    );
    mocks.messageAccept.mockReset();
    mocks.messageDecline.mockReset();
    mocks.commandResult.mockReset();
});

afterEach(() => {
    vi.restoreAllMocks();
});

describe('MessagePlate', () => {
    it('renders decoded message target names and text instead of raw json', () => {
        render(<MessagePlate message={baseMessage} generalId={10} onToast={vi.fn()} />);

        expect(screen.getByText(/조조/)).toBeInTheDocument();
        expect(screen.getByText('해석된 서신 본문')).toBeInTheDocument();
        expect(screen.queryByText(/raw json/)).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '수락' })).not.toBeInTheDocument();
    });

    it('shows accept and decline only for actionable diplomacy messages', () => {
        render(<MessagePlate message={actionableMessage} generalId={10} onToast={vi.fn()} />);

        expect(screen.getByRole('button', { name: '수락' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    });

    it('reports accept success and reloads only after a resolved ok result', async () => {
        // Given
        window.confirm = vi.fn(() => true);
        mocks.messageAccept.mockResolvedValue({ status: 'AVAILABLE', requestId: 'accept-1' });
        mocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'accept-1',
            ok: true,
            type: 'acceptDiplomaticMessage',
            result: {},
        });
        const onToast = vi.fn();
        const onActed = vi.fn();
        render(<MessagePlate message={actionableMessage} generalId={10} onToast={onToast} onActed={onActed} />);

        // When
        fireEvent.click(screen.getByRole('button', { name: '수락' }));

        // Then
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledWith('accept-1'));
        expect(onToast).toHaveBeenCalledWith('수락했습니다.', 'success');
        expect(onActed).toHaveBeenCalledTimes(1);
    });

    it('surfaces the exact engine decline reason without reloading', async () => {
        // Given
        window.confirm = vi.fn(() => true);
        mocks.messageDecline.mockResolvedValue({ status: 'AVAILABLE', requestId: 'decline-1' });
        mocks.commandResult.mockResolvedValue({
            status: 'RESOLVED',
            requestId: 'decline-1',
            ok: false,
            type: 'declineDiplomaticMessage',
            reason: '이미 처리된 외교 서신입니다.',
            result: {},
        });
        const onToast = vi.fn();
        const onActed = vi.fn();
        render(<MessagePlate message={actionableMessage} generalId={10} onToast={onToast} onActed={onActed} />);

        // When
        fireEvent.click(screen.getByRole('button', { name: '거절' }));

        // Then
        await waitFor(() => expect(onToast).toHaveBeenCalledWith('이미 처리된 외교 서신입니다.', 'error'));
        expect(onActed).not.toHaveBeenCalled();
    });

    it('reports a neutral received request when every result remains pending', async () => {
        // Given
        window.confirm = vi.fn(() => true);
        mocks.messageAccept.mockResolvedValue({ status: 'AVAILABLE', requestId: 'accept-pending' });
        mocks.commandResult.mockResolvedValue({ status: 'PENDING', requestId: 'accept-pending' });
        const onToast = vi.fn();
        const onActed = vi.fn();
        render(<MessagePlate message={actionableMessage} generalId={10} onToast={onToast} onActed={onActed} />);

        // When
        fireEvent.click(screen.getByRole('button', { name: '수락' }));

        // Then
        await waitFor(() => expect(mocks.commandResult).toHaveBeenCalledTimes(20));
        expect(onToast).toHaveBeenCalledWith('수락 요청을 접수했습니다.', 'info');
        expect(onToast).not.toHaveBeenCalledWith('수락했습니다.', 'success');
        expect(onActed).not.toHaveBeenCalled();
    });
});
