import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({
    default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/ConfirmModal', () => ({ default: () => null }));
vi.mock('@/components/admin/MemberControl', () => ({ default: () => <div>members</div> }));

import AdminPage from '@/app/admin/page';

const SERVICE = {
    reachable: true,
    version: 'test',
    imageTag: 'test',
    buildTime: null,
};

function response(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
    });
}

describe('admin server ID validation', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal('React', React);
        vi.stubGlobal('fetch', vi.fn((input: RequestInfo | URL) => {
            const path = String(input);
            if (path === '/api/proxy/admin/version') {
                return Promise.resolve(response({ gateway: SERVICE, servers: [], skew: false }));
            }
            if (path === '/api/proxy/admin/scenarios') {
                return Promise.resolve(response({ scenarios: [{ code: 'scenario_1010', title: '테스트 시나리오' }] }));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        }));
    });

    it('allows only public alphanumeric IDs before server creation', async () => {
        render(<AdminPage />);
        fireEvent.click(screen.getByRole('button', { name: '서버 제어' }));

        await screen.findByText('새 서버 생성');
        const id = screen.getByRole('textbox', { name: /서버 ID/ });
        const create = screen.getByRole('button', { name: '서버 생성' });
        await waitFor(() => expect(create).toBeEnabled());

        expect(id).toHaveAttribute('placeholder', 'pep');
        expect(id).toHaveAttribute('pattern', '[A-Za-z0-9]+');
        expect(screen.getByText('영문과 숫자만 사용할 수 있습니다. 예: pep, A1, s1. 대문자는 소문자로 저장됩니다.')).toBeInTheDocument();

        for (const invalidId of ['', 'pep-1', 'pep_1', 'pep/1', '한글']) {
            fireEvent.change(id, { target: { value: invalidId } });
            expect(create).toBeDisabled();
        }

        for (const validId of ['pep', 'A1', 's1']) {
            fireEvent.change(id, { target: { value: validId } });
            expect(create).toBeEnabled();
        }
    });
});
