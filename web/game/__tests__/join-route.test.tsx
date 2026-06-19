import { render, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import JoinPage from '@/app/game/join/page';

const replaceMock = vi.hoisted(() => vi.fn());
const pushMock = vi.hoisted(() => vi.fn());
const refreshMock = vi.hoisted(() => vi.fn());

vi.mock('next/navigation', () => ({
    useRouter: () => ({
        push: pushMock,
        replace: replaceMock,
        refresh: refreshMock,
        prefetch: vi.fn(),
        back: vi.fn(),
    }),
}));

vi.mock('@/components/Shell', () => ({
    default: ({ children }: { children: ReactNode }) => <div>{children}</div>,
}));

vi.mock('@/hooks/useFrontInfo', () => ({
    useFrontInfo: () => ({
        frontInfo: {
            result: true,
            global: { serverId: 's1' },
            general: { hasGeneral: true, name: '코덱스', generalId: 77 },
        },
        constData: null,
        menu: [],
        loading: false,
        error: null,
        refreshKey: 0,
        refresh: vi.fn(),
    }),
}));

vi.mock('@/lib/api', () => ({
    api: {
        mapPreview: vi.fn().mockResolvedValue({ nations: [] }),
    },
}));

describe('JoinPage route guard', () => {
    beforeEach(() => {
        replaceMock.mockReset();
        pushMock.mockReset();
        refreshMock.mockReset();
    });

    it('이미 등록된 장수가 있으면 장수 등록 폼에서 현재 서버 게임으로 바로 입장한다', async () => {
        render(<JoinPage />);

        await waitFor(() => {
            expect(replaceMock).toHaveBeenCalledWith('/game/s1');
        });
        expect(pushMock).not.toHaveBeenCalledWith('/lobby');
    });
});
