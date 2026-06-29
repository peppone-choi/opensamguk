import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MessagePanel from '@/components/game/MessagePanel';

const mocks = vi.hoisted(() => ({
    mailbox: vi.fn(),
}));

vi.mock('@/lib/api', () => ({
    api: {
        mailbox: mocks.mailbox,
        commands: {
            sendMessage: vi.fn(),
        },
    },
    isIntakeDenied: vi.fn(() => false),
    isIntakeQueued: vi.fn(() => true),
}));

vi.mock('@/components/game/MessagePlate', () => ({
    default: () => <article />,
}));

describe('MessagePanel', () => {
    it('defaults to the caller nation mailbox like legacy MessagePanel', async () => {
        mocks.mailbox.mockResolvedValueOnce([]);

        render(<MessagePanel generalId={10} nationId={1} onToast={vi.fn()} />);

        await waitFor(() => expect(mocks.mailbox).toHaveBeenCalledWith(9001));
        expect(screen.getByRole('combobox')).toHaveValue('9001');
    });
});
