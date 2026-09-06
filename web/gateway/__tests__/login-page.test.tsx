import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import LoginPage from '@/app/login/page';
import { AUTH_LABELS, FOOTER_LINKS } from '@/lib/constants';

const mocks = vi.hoisted(() => ({ push: vi.fn(), refresh: vi.fn(), login: vi.fn(), next: vi.fn() }));
vi.mock('next/navigation', () => ({
    useRouter: () => ({ push: mocks.push, refresh: mocks.refresh }),
    useSearchParams: () => ({ get: mocks.next }),
}));
vi.mock('@/lib/client', () => ({ login: mocks.login }));
vi.mock('@/components/ServerBoard', () => ({ default: () => <div data-testid="server-board" /> }));

describe('01 로그인 (ADR-LITE-049)', () => {
    beforeEach(() => {
        mocks.login.mockReset();
        mocks.next.mockReturnValue(null);
        vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
            const url = String(input);
            if (url.includes('/api/notices')) {
                return new Response(JSON.stringify({ notices: [{ id: 1, title: '공개 알파 안내', body: '월드는 초기화될 수 있습니다.\n계정은 보존됩니다.', pinned: true, publishedAt: '2026-09-05T00:00:00Z', deleted: false }] }), { status: 200, headers: { 'Content-Type': 'application/json' } });
            }
            throw new Error(`unexpected request: ${url}`);
        });
    });

    it('keeps the verbatim form labels, the join link, footer links and shows notices', async () => {
        render(<LoginPage />);
        expect(screen.getByRole('heading', { level: 1, name: AUTH_LABELS.loginTitle })).toBeInTheDocument();
        expect(screen.getByLabelText(AUTH_LABELS.username)).toBeInTheDocument();
        expect(screen.getByLabelText(AUTH_LABELS.password)).toHaveAttribute('type', 'password');
        expect(screen.getByRole('link', { name: AUTH_LABELS.toJoin })).toHaveAttribute('href', '/join');
        for (const label of FOOTER_LINKS) expect(screen.getByText(label)).toBeInTheDocument();
        expect(screen.getByTestId('server-board')).toBeInTheDocument();
        expect(await screen.findByText('공개 알파 안내')).toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', { name: /공개 알파 안내/ }));
        expect(screen.getByText(/계정은 보존됩니다/)).toBeInTheDocument();
    });

    it('toggles password visibility and surfaces the verbatim empty-field errors', () => {
        render(<LoginPage />);
        fireEvent.click(screen.getByRole('button', { name: '표시' }));
        expect(screen.getByLabelText(AUTH_LABELS.password)).toHaveAttribute('type', 'text');
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        expect(screen.getByRole('alert')).toHaveTextContent(AUTH_LABELS.emptyUsername);
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.username), { target: { value: 'tester' } });
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        expect(screen.getByRole('alert')).toHaveTextContent(AUTH_LABELS.emptyPassword);
        expect(mocks.login).not.toHaveBeenCalled();
    });

    it('shows the server rejection reason verbatim and stays on the page', async () => {
        mocks.login.mockRejectedValueOnce(new Error(AUTH_LABELS.loginFail));
        render(<LoginPage />);
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.username), { target: { value: 'tester' } });
        fireEvent.change(screen.getByLabelText(AUTH_LABELS.password), { target: { value: 'secret' } });
        fireEvent.click(screen.getByRole('button', { name: AUTH_LABELS.loginBtn }));
        await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(AUTH_LABELS.loginFail));
        expect(mocks.push).not.toHaveBeenCalled();
    });

    it('marks the notice panel as unavailable (not empty) when the feed fails', async () => {
        vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response('down', { status: 502 }));
        render(<LoginPage />);
        expect(await screen.findByText('공지를 불러올 수 없습니다.')).toBeInTheDocument();
    });
});
