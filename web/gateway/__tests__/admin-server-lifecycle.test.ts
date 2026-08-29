import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';

import { runServerLifecycleOperation } from '@/lib/admin-server-lifecycle';

const OPERATION_ID = '0123456789abcdef0123456789abcdef';

function jsonResponse(status: number, body: Record<string, unknown>): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'Content-Type': 'application/json' },
    });
}

function lifecycleResponse(operationStatus: string, overrides: Record<string, unknown> = {}): Record<string, unknown> {
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
        publicMessage: '',
        message: '',
        ...overrides,
    };
}

describe('admin server lifecycle client', () => {
    beforeEach(() => {
        vi.useFakeTimers();
        vi.spyOn(globalThis.crypto, 'randomUUID').mockReturnValue('01234567-89ab-cdef-0123-456789abcdef');
    });

    afterEach(() => {
        vi.useRealTimers();
        vi.restoreAllMocks();
        vi.unstubAllGlobals();
    });

    test('pending then succeeded resolves only after terminal status', async () => {
        const replies = [
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(202, lifecycleResponse('running')),
            jsonResponse(200, lifecycleResponse('succeeded', { publicMessage: '서버 리셋이 완료되었습니다.' })),
        ];
        vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(replies.shift()!)));
        const progress: string[] = [];

        const operation = runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        });

        await vi.advanceTimersByTimeAsync(1_000);
        expect(progress).toEqual(['pending', 'running']);
        let settled = false;
        void operation.finally(() => {
            settled = true;
        });
        await Promise.resolve();
        expect(settled).toBe(false);

        await vi.advanceTimersByTimeAsync(1_000);
        await expect(operation).resolves.toMatchObject({ operationId: OPERATION_ID, operationStatus: 'succeeded' });
        expect(progress).toEqual(['pending', 'running', 'succeeded']);
    });

    test('exact missing resubmits the same mutation once with the same operation id', async () => {
        const replies = [
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(202, lifecycleResponse('missing', { resubmitRequired: true })),
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(200, lifecycleResponse('succeeded')),
        ];
        const fetchFake = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => Promise.resolve(replies.shift()!));
        vi.stubGlobal('fetch', fetchFake);

        const operation = runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep', generation: '2' },
        });
        await vi.advanceTimersByTimeAsync(2_000);
        await expect(operation).resolves.toMatchObject({ operationStatus: 'succeeded' });

        const mutationCalls = fetchFake.mock.calls.filter(([url]) => String(url).endsWith('/servers/pep/reset'));
        expect(mutationCalls).toHaveLength(2);
        const bodies = mutationCalls.map(([, init]) => JSON.parse(String(init?.body)) as Record<string, unknown>);
        expect(bodies).toEqual([
            { confirm: 'RESET pep', generation: '2', operationId: OPERATION_ID },
            { confirm: 'RESET pep', generation: '2', operationId: OPERATION_ID },
        ]);
        expect(String(bodies[0].operationId)).toMatch(/^[a-f0-9]{32}$/);
    });

    test('a second exact missing rejects instead of resubmitting the mutation again', async () => {
        const replies = [
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(202, lifecycleResponse('missing', {
                resubmitRequired: true,
                publicMessage: '작업을 다시 찾지 못했습니다.',
            })),
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(202, lifecycleResponse('missing', {
                resubmitRequired: true,
                publicMessage: '작업을 다시 찾지 못했습니다.',
            })),
        ];
        const fetchFake = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => Promise.resolve(replies.shift()!));
        vi.stubGlobal('fetch', fetchFake);

        const operation = runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
        });
        const rejection = expect(operation).rejects.toThrow('작업을 다시 찾지 못했습니다.');
        await vi.advanceTimersByTimeAsync(2_000);

        await rejection;
        const mutationCalls = fetchFake.mock.calls.filter(([url]) => String(url).endsWith('/servers/pep/reset'));
        expect(mutationCalls).toHaveLength(2);
    });

    test('failed operations reject with their public message', async () => {
        const replies = [
            jsonResponse(202, lifecycleResponse('pending')),
            jsonResponse(409, lifecycleResponse('failed', {
                httpStatus: 409,
                publicMessage: '서버 리셋 검증에 실패했습니다.',
                message: '서버 리셋 검증에 실패했습니다.',
            })),
        ];
        vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(replies.shift()!)));
        const progress: string[] = [];

        const operation = runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        });
        const rejection = expect(operation).rejects.toThrow('서버 리셋 검증에 실패했습니다.');
        await vi.advanceTimersByTimeAsync(1_000);

        await rejection;
        expect(progress).not.toContain('succeeded');
    });

    test('times out at exactly ten minutes with the operation id and no success progress', async () => {
        vi.stubGlobal('fetch', vi.fn((url: RequestInfo | URL) => {
            const status = String(url).includes('/operations/') ? 'running' : 'pending';
            return Promise.resolve(jsonResponse(202, lifecycleResponse(status)));
        }));
        const progress: string[] = [];

        const operation = runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        });
        const rejection = expect(operation).rejects.toThrow(OPERATION_ID);
        await vi.advanceTimersByTimeAsync(10 * 60 * 1_000);

        await rejection;
        expect(progress).not.toContain('succeeded');
    });

    test('times out and aborts a never-settling initial mutation at the absolute deadline', async () => {
        let requestSignal: AbortSignal | undefined;
        vi.stubGlobal('fetch', vi.fn((_url: RequestInfo | URL, init?: RequestInit) => {
            requestSignal = init?.signal as AbortSignal | undefined;
            return new Promise<Response>(() => undefined);
        }));
        const progress: string[] = [];
        let rejected: unknown;

        void runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        }).catch((error: unknown) => {
            rejected = error;
        });
        await vi.advanceTimersByTimeAsync(10 * 60 * 1_000);

        expect(rejected).toMatchObject({
            name: 'ServerLifecycleOperationTimeoutError',
            operationId: OPERATION_ID,
            message: `서버 작업이 아직 진행 중입니다. 작업 ID: ${OPERATION_ID}`,
        });
        expect(requestSignal?.aborted).toBe(true);
        expect(progress).not.toContain('succeeded');
    });

    test('times out and aborts a never-settling status request at the absolute deadline', async () => {
        let statusSignal: AbortSignal | undefined;
        vi.stubGlobal('fetch', vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
            if (String(url).includes('/operations/')) {
                statusSignal = init?.signal as AbortSignal | undefined;
                return new Promise<Response>(() => undefined);
            }
            return Promise.resolve(jsonResponse(202, lifecycleResponse('pending')));
        }));
        const progress: string[] = [];
        let rejected: unknown;

        void runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        }).catch((error: unknown) => {
            rejected = error;
        });
        await vi.advanceTimersByTimeAsync(10 * 60 * 1_000);

        expect(rejected).toMatchObject({
            name: 'ServerLifecycleOperationTimeoutError',
            operationId: OPERATION_ID,
            message: `서버 작업이 아직 진행 중입니다. 작업 ID: ${OPERATION_ID}`,
        });
        expect(statusSignal?.aborted).toBe(true);
        expect(progress).toEqual(['pending']);
    });

    test('times out and aborts a never-settling bounded resubmission at the absolute deadline', async () => {
        let mutationCount = 0;
        let resubmitSignal: AbortSignal | undefined;
        vi.stubGlobal('fetch', vi.fn((url: RequestInfo | URL, init?: RequestInit) => {
            if (!String(url).includes('/operations/')) {
                mutationCount += 1;
                if (mutationCount === 2) {
                    resubmitSignal = init?.signal as AbortSignal | undefined;
                    return new Promise<Response>(() => undefined);
                }
                return Promise.resolve(jsonResponse(202, lifecycleResponse('pending')));
            }
            return Promise.resolve(jsonResponse(202, lifecycleResponse('missing', { resubmitRequired: true })));
        }));
        const progress: string[] = [];
        let rejected: unknown;

        void runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            onProgress: (result) => progress.push(result.operationStatus),
        }).catch((error: unknown) => {
            rejected = error;
        });
        await vi.advanceTimersByTimeAsync(10 * 60 * 1_000);

        expect(rejected).toMatchObject({
            name: 'ServerLifecycleOperationTimeoutError',
            operationId: OPERATION_ID,
            message: `서버 작업이 아직 진행 중입니다. 작업 ID: ${OPERATION_ID}`,
        });
        expect(resubmitSignal?.aborted).toBe(true);
        expect(progress).toEqual(['pending', 'missing']);
    });

    test('aborts an in-flight request and propagates the caller signal through the network boundary', async () => {
        const fetchFake = vi.fn((_input: RequestInfo | URL, _init?: RequestInit) => (
            new Promise<Response>(() => undefined)
        ));
        vi.stubGlobal('fetch', fetchFake);
        const controller = new AbortController();
        let rejected: unknown;

        void runServerLifecycleOperation({
            url: '/api/proxy/admin/servers/pep/reset',
            method: 'POST',
            body: { confirm: 'RESET pep' },
            signal: controller.signal,
        }).catch((error: unknown) => {
            rejected = error;
        });
        await Promise.resolve();
        const requestSignal = fetchFake.mock.calls[0]?.[1]?.signal as AbortSignal | undefined;
        expect(requestSignal?.aborted).toBe(false);
        controller.abort();
        await vi.advanceTimersByTimeAsync(0);

        expect(rejected).toMatchObject({ name: 'AbortError' });
        expect(requestSignal?.aborted).toBe(true);
    });
});
