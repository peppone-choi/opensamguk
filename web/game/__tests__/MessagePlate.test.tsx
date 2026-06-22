import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import MessagePlate from '@/components/game/MessagePlate';
import type { MailboxMessage } from '@/types/game';

vi.mock('@/lib/api', () => ({
    api: {
        messageAccept: vi.fn(),
        messageDecline: vi.fn(),
    },
}));

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

describe('MessagePlate', () => {
    it('renders decoded message target names and text instead of raw json', () => {
        render(<MessagePlate message={baseMessage} generalId={10} onToast={vi.fn()} />);

        expect(screen.getByText(/조조/)).toBeInTheDocument();
        expect(screen.getByText('해석된 서신 본문')).toBeInTheDocument();
        expect(screen.queryByText(/raw json/)).not.toBeInTheDocument();
        expect(screen.queryByRole('button', { name: '수락' })).not.toBeInTheDocument();
    });

    it('shows accept and decline only for actionable diplomacy messages', () => {
        render(<MessagePlate message={{ ...baseMessage, type: 'diplomacy', option: { action: 'no_aggression' } }} generalId={10} onToast={vi.fn()} />);

        expect(screen.getByRole('button', { name: '수락' })).toBeInTheDocument();
        expect(screen.getByRole('button', { name: '거절' })).toBeInTheDocument();
    });
});
