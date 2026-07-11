import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Admin2Page from '@/app/game/admin2/page';

const apiMocks = vi.hoisted(() => ({
    generalModeration: vi.fn(),
    generalModerationAction: vi.fn(),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/components/GameCard', () => ({
    default: ({ children }: { children: ReactNode }) => <section>{children}</section>,
}));

vi.mock('@/lib/api', () => ({
    api: {
        admin: {
            generalModeration: apiMocks.generalModeration,
            generalModerationAction: apiMocks.generalModerationAction,
        },
    },
}));

const disabledAction = { label: '-', reason: '', enabled: false };
const moderation = {
    generals: [
        { no: 1, name: '유비', npc: 0, block: 0, killturn: 6, nationId: 1, turnTime: null, command0: null, command1: null },
        { no: 2, name: '관우', npc: 0, block: 0, killturn: 6, nationId: 1, turnTime: null, command0: null, command1: null },
    ],
    bulkActions: [
        { label: '전체 접속허용', reason: '', code: 'allowAccessAll', enabled: true },
        { label: '전체 접속제한', reason: '', code: 'denyAccessAll', enabled: true },
    ],
    selectedActions: Array.from({ length: 16 }, () => disabledAction),
};

describe('Admin2Page access controls', () => {
    beforeEach(() => {
        apiMocks.generalModeration.mockReset().mockResolvedValue(moderation);
        apiMocks.generalModerationAction.mockReset().mockResolvedValue({
            result: true,
            action: 'denyAccessAll',
            affected: 2,
        });
    });

    it('submits every general for bulk access restriction without a selection', async () => {
        render(<Admin2Page />);

        fireEvent.click(await screen.findByRole('button', { name: '전체 접속제한' }));

        await waitFor(() => {
            expect(apiMocks.generalModerationAction).toHaveBeenCalledWith({
                action: 'denyAccessAll',
                generalIds: [1, 2],
            });
        });
        expect(apiMocks.generalModeration).toHaveBeenCalledTimes(2);
    });
});
