import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import React from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/AuthGate', () => ({
    default: ({ children }: { children: React.ReactNode }) => children,
}));
vi.mock('@/components/Topbar', () => ({ default: () => <div>topbar</div> }));
vi.mock('@/components/admin/MemberControl', () => ({ default: () => <div>members</div> }));

import AdminPage from '@/app/admin/page';

const SERVICE = {
    reachable: true,
    version: 'test',
    imageTag: 'test',
    buildTime: null,
};
const OPERATION_ID = '0123456789abcdef0123456789abcdef';
const SERVER = {
    id: 'pep',
    name: '통일 서버',
    generation: 1,
    scenarioCode: 'scenario_1010',
    gameApi: SERVICE,
    gameEngine: SERVICE,
    skew: false,
};

function response(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function lifecycleResponse(
    operationStatus: string,
    publicMessage: string,
    overrides: Record<string, unknown> = {},
): Record<string, unknown> {
    const completed = ['succeeded', 'failed', 'cancelled'].includes(operationStatus);
    return {
        ok: operationStatus === 'succeeded',
        id: 'pep',
        subjectId: 'pep',
        operationId: OPERATION_ID,
        kind: 'reset',
        status: operationStatus,
        operationStatus,
        httpStatus: completed ? 200 : 202,
        completed,
        retryable: !completed,
        resubmitRequired: false,
        publicMessage,
        message: publicMessage,
        ...overrides,
    };
}

function serverFetch(
    lifecycle: (path: string, init?: RequestInit) => Promise<Response>,
): ReturnType<typeof vi.fn> {
    return vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
        const path = String(input);
        if (path === '/api/proxy/admin/version') {
            return Promise.resolve(response({ gateway: SERVICE, servers: [SERVER], skew: false }));
        }
        if (path === '/api/proxy/admin/scenarios') {
            return Promise.resolve(response({ scenarios: [{ code: 'scenario_1010', title: '테스트 시나리오' }] }));
        }
        if (path === '/api/proxy/admin/deploy/status?serverId=pep') {
            return Promise.resolve(response({
                configured: false,
                serverId: 'pep',
                currentTag: null,
                availableTags: [],
            }));
        }
        return lifecycle(path, init);
    });
}

async function openServerControl(): Promise<void> {
    render(<AdminPage />);
    fireEvent.click(screen.getByRole('button', { name: '서버 제어' }));
    await screen.findByText('새 서버 생성');
    await screen.findByRole('button', { name: '리셋' });
}

async function confirmReset(): Promise<void> {
    fireEvent.click(screen.getByRole('button', { name: '리셋' }));
    fireEvent.click(screen.getByRole('button', { name: '리셋 실행' }));
    await act(async () => {
        await Promise.resolve();
    });
}

describe('admin server ID validation', () => {
    beforeEach(() => {
        vi.clearAllMocks();
        vi.stubGlobal('React', React);
        vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('01234567-89ab-cdef-0123-456789abcdef');
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

    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
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
        expect(id).toHaveAttribute('maxLength', '48');
        expect(screen.getByText('영문과 숫자 48자 이내로 사용할 수 있습니다. 예: pep, A1, s1. 대문자는 소문자로 저장되며 all과 게임 경로 예약어는 사용할 수 없습니다.')).toBeInTheDocument();

        const tooLongId = `${'Ab'.repeat(24)}a`;

        for (const invalidId of ['', 'pep-1', 'pep_1', 'pep/1', '한글', tooLongId]) {
            fireEvent.change(id, { target: { value: invalidId } });
            expect(create).toBeDisabled();
        }

        const reservedPublicIds = [
            'all',
            'main',
            'admin1',
            'admin2',
            'admin5',
            'admin7',
            'admin8',
            'auction',
            'battle-center',
            'betting',
            'board',
            'chief-center',
            'city',
            'coming-soon',
            'diplomacy',
            'generals',
            'global-diplomacy',
            'history',
            'inherit',
            'join',
            'mailbox',
            'map',
            'my',
            'my-boss',
            'my-cities',
            'my-generals',
            'my-nation',
            'nation',
            'nation-betting',
            'nation-finance',
            'npc-control',
            'rankings',
            'register',
            'select-pool',
            'simulator',
            'tournament',
            'tournament-admin',
            'troop',
            'vote',
            'world-log',
        ];

        for (const reservedId of reservedPublicIds) {
            for (const rawId of [reservedId, reservedId.toUpperCase()]) {
                fireEvent.change(id, { target: { value: rawId } });
                expect(create).toBeDisabled();
            }
        }

        for (const validId of ['pep', 'A1', 's1', 'current', 'Ab'.repeat(24)]) {
            fireEvent.change(id, { target: { value: validId } });
            expect(create).toBeEnabled();
        }
    });

    it('sends only the public JWT key when creating a server', async () => {
        let createRequestBody: string | undefined;
        vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
            const path = String(input);
            if (path === '/api/proxy/admin/version') {
                return Promise.resolve(response({ gateway: SERVICE, servers: [], skew: false }));
            }
            if (path === '/api/proxy/admin/scenarios') {
                return Promise.resolve(response({ scenarios: [{ code: 'scenario_1010', title: '테스트 시나리오' }] }));
            }
            if (path === '/api/proxy/admin/servers' && init?.method === 'POST') {
                if (typeof init.body === 'string') createRequestBody = init.body;
                return Promise.resolve(response({ ok: true, id: 'pep', name: '통일 서버' }));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        });
        render(<AdminPage />);
        fireEvent.click(screen.getByRole('button', { name: '서버 제어' }));

        const publicKey = await screen.findByRole('textbox', { name: /JWT 공개키/ });
        fireEvent.change(publicKey, { target: { value: 'public-key-material' } });
        fireEvent.click(screen.getByRole('button', { name: '서버 생성' }));

        await waitFor(() => expect(createRequestBody).toContain('"jwtPublicKey":"public-key-material"'));
        expect(createRequestBody).not.toContain('jwtSecret');
        expect(screen.queryByText('JWT_SECRET')).not.toBeInTheDocument();
    });

    it('renders a pending reset as processing and never as completed', async () => {
        vi.stubGlobal('fetch', serverFetch((path) => {
            if (path === '/api/proxy/admin/servers/pep/reset') {
                return Promise.resolve(response(lifecycleResponse('pending', '요청이 접수되었습니다.'), 202));
            }
            if (path === `/api/proxy/admin/servers/operations/${OPERATION_ID}`) {
                return new Promise(() => undefined);
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        }));
        await openServerControl();
        vi.useFakeTimers();

        await confirmReset();

        expect(screen.getByText('처리 중')).toBeInTheDocument();
        expect(screen.queryByText(/처리 완료/)).not.toBeInTheDocument();
    });

    it('renders a recovery-required reset as awaiting recovery confirmation', async () => {
        vi.stubGlobal('fetch', serverFetch((path) => {
            if (path === '/api/proxy/admin/servers/pep/reset') {
                return Promise.resolve(response(lifecycleResponse(
                    'recovery_required',
                    '서버 복구 확인이 필요합니다.',
                ), 202));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        }));
        await openServerControl();
        vi.useFakeTimers();

        await confirmReset();

        expect(screen.getByText('복구 확인 중')).toBeInTheDocument();
        expect(screen.queryByText(/처리 완료/)).not.toBeInTheDocument();
    });

    it('renders reset completion and reloads the server version exactly once after succeeded', async () => {
        const fetchFake = serverFetch((path) => {
            if (path === '/api/proxy/admin/servers/pep/reset') {
                return Promise.resolve(response(lifecycleResponse('pending', '요청이 접수되었습니다.'), 202));
            }
            if (path === `/api/proxy/admin/servers/operations/${OPERATION_ID}`) {
                return Promise.resolve(response(lifecycleResponse('succeeded', '서버 리셋이 완료되었습니다.')));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        });
        vi.stubGlobal('fetch', fetchFake);
        await openServerControl();
        vi.useFakeTimers();

        await confirmReset();
        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        expect(screen.getByText('처리 중')).toBeInTheDocument();
        expect(fetchFake.mock.calls.filter(([path]) => String(path) === '/api/proxy/admin/version')).toHaveLength(1);
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1_000);
        });

        expect(screen.getByText('서버 리셋이 완료되었습니다.')).toBeInTheDocument();
        expect(fetchFake.mock.calls.filter(([path]) => String(path) === '/api/proxy/admin/version')).toHaveLength(2);
    });

    it('renders a failed create public message without reloading server membership', async () => {
        const fetchFake = serverFetch((path) => {
            if (path === '/api/proxy/admin/servers') {
                return Promise.resolve(response(lifecycleResponse('pending', '요청이 접수되었습니다.', { kind: 'create' }), 202));
            }
            if (path === `/api/proxy/admin/servers/operations/${OPERATION_ID}`) {
                return Promise.resolve(response(lifecycleResponse('failed', '서버 생성 검증에 실패했습니다.', {
                    kind: 'create',
                    httpStatus: 409,
                }), 409));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        });
        vi.stubGlobal('fetch', fetchFake);
        await openServerControl();
        vi.useFakeTimers();

        fireEvent.click(screen.getByRole('button', { name: '서버 생성' }));
        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        expect(screen.getByText('처리 중')).toBeInTheDocument();
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1_000);
        });

        expect(screen.getByText('서버 생성 검증에 실패했습니다.')).toBeInTheDocument();
        expect(fetchFake.mock.calls.filter(([path]) => String(path) === '/api/proxy/admin/version')).toHaveLength(1);
    });

    it('renders a failed delete public message without reloading server membership', async () => {
        const fetchFake = serverFetch((path) => {
            if (path === '/api/proxy/admin/servers/pep') {
                return Promise.resolve(response(lifecycleResponse('pending', '요청이 접수되었습니다.', { kind: 'close' }), 202));
            }
            if (path === `/api/proxy/admin/servers/operations/${OPERATION_ID}`) {
                return Promise.resolve(response(lifecycleResponse('failed', '서버 삭제 검증에 실패했습니다.', {
                    kind: 'close',
                    httpStatus: 409,
                }), 409));
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        });
        vi.stubGlobal('fetch', fetchFake);
        await openServerControl();
        vi.useFakeTimers();

        fireEvent.click(screen.getByRole('button', { name: '삭제' }));
        fireEvent.click(screen.getByRole('button', { name: '삭제 실행' }));
        await act(async () => {
            await vi.advanceTimersByTimeAsync(0);
        });
        expect(screen.getByText('처리 중')).toBeInTheDocument();
        await act(async () => {
            await vi.advanceTimersByTimeAsync(1_000);
        });

        expect(screen.getByText('서버 삭제 검증에 실패했습니다.')).toBeInTheDocument();
        expect(fetchFake.mock.calls.filter(([path]) => String(path) === '/api/proxy/admin/version')).toHaveLength(1);
    });

    it('keeps reset and delete controls disabled while lifecycle polling is active', async () => {
        vi.stubGlobal('fetch', serverFetch((path) => {
            if (path === '/api/proxy/admin/servers/pep/reset') {
                return Promise.resolve(response(lifecycleResponse('pending', '요청이 접수되었습니다.'), 202));
            }
            if (path === `/api/proxy/admin/servers/operations/${OPERATION_ID}`) {
                return new Promise(() => undefined);
            }
            return Promise.resolve(new Response(null, { status: 404 }));
        }));
        await openServerControl();
        vi.useFakeTimers();

        await confirmReset();

        expect(screen.getByRole('button', { name: '리셋', hidden: true })).toBeDisabled();
        expect(screen.getByRole('button', { name: '삭제', hidden: true })).toBeDisabled();
    });
});
