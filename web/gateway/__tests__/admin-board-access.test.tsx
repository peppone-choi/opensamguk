import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
const auth = vi.hoisted(() => ({
    state: {
        user: null as {
            id: number;
            username: string;
            email: string | null;
            nickname: string | null;
            role: string;
            picture: string | null;
            imageServer: number;
        } | null,
        loading: false,
    },
}));

vi.mock('next/navigation', () => ({ useRouter: () => navigation }));
vi.mock('@/lib/auth-context', () => ({
    AuthProvider: ({ children }: { children: React.ReactNode }) => children,
    useAuth: () => auth.state,
    useAuthOptional: () => auth.state,
}));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/admin/MemberControl', () => ({ default: () => <div>members</div> }));
vi.mock('@/components/admin/BoardControl', () => ({ default: () => <div>board controls</div> }));

import AdminPage from '@/app/admin/page';

function user(role: 'ADMIN' | 'USER') {
    return {
        id: 1,
        username: 'tester',
        email: null,
        nickname: null,
        role,
        picture: null,
        imageServer: 0,
    };
}

describe('admin board access', () => {
    beforeEach(() => {
        navigation.replace.mockReset();
        vi.stubGlobal('React', React);
        auth.state.loading = false;
        auth.state.user = null;
    });

    it('redirects a non-admin before the board control can render', async () => {
        auth.state.user = user('USER');

        render(<AdminPage />);

        await waitFor(() => expect(navigation.replace).toHaveBeenCalledWith('/lobby'));
        expect(screen.queryByRole('button', { name: '게시판 관리' })).toBeNull();
        expect(screen.queryByText('board controls')).toBeNull();
    });

    it('keeps the board control inside the existing admin-only gate', () => {
        auth.state.user = user('ADMIN');

        render(<AdminPage />);

        fireEvent.click(screen.getByRole('button', { name: '게시판 관리' }));
        expect(screen.getByText('board controls')).toBeInTheDocument();
    });
});
