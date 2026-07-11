import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const replace = vi.fn();
const refresh = vi.fn().mockResolvedValue(null);
const logout = vi.fn().mockResolvedValue(undefined);

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace }) }));
vi.mock('@/components/AuthGate', () => ({ default: ({ children }: { children: React.ReactNode }) => children }));
vi.mock('@/lib/auth-context', () => ({
    useAuth: () => ({
        user: { id: 1, username: 'tester', email: null, nickname: null, role: 'USER', picture: 'old.png', imageServer: 1 },
        refresh,
        logout,
    }),
}));

import AccountPage from '@/app/account/page';

function response(status = 200, body = '{}'): Response {
    return new Response(body, { status, headers: { 'Content-Type': 'application/json' } });
}

describe('account settings interactions', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response()));
        vi.stubGlobal('confirm', vi.fn().mockReturnValue(true));
    });

    it('submits a password change with the current password', async () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        fireEvent.change(screen.getByLabelText('새 비밀번호'), { target: { value: 'newpass1' } });
        fireEvent.click(screen.getByRole('button', { name: '변경' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/password', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ currentPassword: 'oldpass', newPassword: 'newpass1' }),
        })));
        expect(await screen.findByRole('status')).toHaveTextContent('비밀번호를 변경했습니다.');
    });

    it('deletes the profile icon through the picture/imgsvr endpoint', async () => {
        render(<AccountPage />);
        fireEvent.click(screen.getByRole('button', { name: '삭제' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/profile-icon', expect.objectContaining({
            method: 'POST',
            body: JSON.stringify({ picture: null, imgsvr: 0 }),
        })));
        expect(await screen.findByRole('status')).toHaveTextContent('전콘을 삭제했습니다.');
    });

    it('confirms account deletion and redirects after logout', async () => {
        render(<AccountPage />);
        fireEvent.change(screen.getByLabelText('현재 비밀번호'), { target: { value: 'oldpass' } });
        fireEvent.click(screen.getByRole('button', { name: '계정 삭제' }));

        await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account', expect.objectContaining({
            method: 'DELETE',
            body: JSON.stringify({ currentPassword: 'oldpass' }),
        })));
        expect(logout).toHaveBeenCalled();
        expect(replace).toHaveBeenCalledWith('/');
    });
});
