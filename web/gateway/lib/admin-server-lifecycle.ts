export type ServerLifecycleOperationStatus =
    | 'pending'
    | 'running'
    | 'recovery_required'
    | 'missing'
    | 'succeeded'
    | 'failed'
    | 'cancelled';

export interface ServerLifecycleResponse {
    ok: boolean;
    id?: string;
    name?: string;
    project?: string;
    subjectId?: string;
    kind?: string;
    operationId: string;
    status?: string;
    operationStatus: ServerLifecycleOperationStatus;
    httpStatus?: number;
    completed: boolean;
    retryable: boolean;
    resubmitRequired: boolean;
    publicMessage: string;
    message?: string | null;
    error?: string | null;
    detail?: string | null;
}

export interface RunServerLifecycleOperationInput {
    url: string;
    method: 'POST' | 'DELETE';
    body?: Record<string, unknown>;
    signal?: AbortSignal;
    onProgress?: (response: ServerLifecycleResponse) => void;
}

const OPERATION_ID_PATTERN = /^[a-f0-9]{32}$/;
const POLL_INTERVAL_MS = 1_000;
const OPERATION_DEADLINE_MS = 10 * 60 * 1_000;
const OPERATION_STATUSES = new Set<ServerLifecycleOperationStatus>([
    'pending',
    'running',
    'recovery_required',
    'missing',
    'succeeded',
    'failed',
    'cancelled',
]);

export class ServerLifecycleOperationError extends Error {
    readonly response?: ServerLifecycleResponse;

    constructor(message: string, response?: ServerLifecycleResponse) {
        super(message);
        this.name = 'ServerLifecycleOperationError';
        this.response = response;
    }
}

export class ServerLifecycleOperationTimeoutError extends Error {
    readonly operationId: string;

    constructor(operationId: string) {
        super(`서버 작업이 아직 진행 중입니다. 작업 ID: ${operationId}`);
        this.name = 'ServerLifecycleOperationTimeoutError';
        this.operationId = operationId;
    }
}

function abortError(): DOMException {
    return new DOMException('서버 작업 확인이 취소되었습니다.', 'AbortError');
}

function waitForPoll(ms: number, signal?: AbortSignal): Promise<void> {
    if (signal?.aborted) return Promise.reject(abortError());

    return new Promise((resolve, reject) => {
        const timer = window.setTimeout(() => {
            signal?.removeEventListener('abort', onAbort);
            resolve();
        }, ms);
        const onAbort = () => {
            window.clearTimeout(timer);
            signal?.removeEventListener('abort', onAbort);
            reject(abortError());
        };
        signal?.addEventListener('abort', onAbort, { once: true });
    });
}

async function runRequestWithinDeadline<T>(
    request: (signal: AbortSignal) => Promise<T>,
    deadline: number,
    operationId: string,
    callerSignal?: AbortSignal,
): Promise<T> {
    const remaining = deadline - Date.now();
    if (remaining <= 0) throw new ServerLifecycleOperationTimeoutError(operationId);
    if (callerSignal?.aborted) throw callerSignal.reason ?? abortError();

    const requestController = new AbortController();
    let timeout: number | undefined;
    let onCallerAbort: (() => void) | undefined;
    const interruption = new Promise<never>((_resolve, reject) => {
        timeout = window.setTimeout(() => {
            const error = new ServerLifecycleOperationTimeoutError(operationId);
            reject(error);
            requestController.abort(error);
        }, remaining);
        if (callerSignal) {
            onCallerAbort = () => {
                const error = callerSignal.reason ?? abortError();
                reject(error);
                requestController.abort(error);
            };
            callerSignal.addEventListener('abort', onCallerAbort, { once: true });
        }
    });

    try {
        return await Promise.race([request(requestController.signal), interruption]);
    } finally {
        if (timeout !== undefined) window.clearTimeout(timeout);
        if (callerSignal && onCallerAbort) callerSignal.removeEventListener('abort', onCallerAbort);
    }
}

function createOperationId(): string {
    const operationId = globalThis.crypto.randomUUID().replaceAll('-', '').toLowerCase();
    if (!OPERATION_ID_PATTERN.test(operationId)) {
        throw new ServerLifecycleOperationError('서버 작업 ID를 생성하지 못했습니다.');
    }
    return operationId;
}

function errorMessage(payload: unknown, response: Response): string {
    if (payload && typeof payload === 'object') {
        const body = payload as Record<string, unknown>;
        for (const key of ['publicMessage', 'message', 'error', 'detail']) {
            if (typeof body[key] === 'string' && body[key]) return body[key];
        }
    }
    return `서버 작업 요청에 실패했습니다. (${response.status})`;
}

async function readLifecycleResponse(response: Response, operationId: string): Promise<ServerLifecycleResponse> {
    let payload: unknown;
    try {
        payload = await response.json();
    } catch {
        throw new ServerLifecycleOperationError(`서버 작업 응답을 확인하지 못했습니다. (${response.status})`);
    }

    if (!payload || typeof payload !== 'object') {
        throw new ServerLifecycleOperationError(errorMessage(payload, response));
    }
    const body = payload as Record<string, unknown>;
    const operationStatus = body.operationStatus;
    if (
        body.operationId !== operationId ||
        typeof operationStatus !== 'string' ||
        !OPERATION_STATUSES.has(operationStatus as ServerLifecycleOperationStatus) ||
        typeof body.completed !== 'boolean' ||
        typeof body.retryable !== 'boolean' ||
        typeof body.resubmitRequired !== 'boolean' ||
        typeof body.publicMessage !== 'string'
    ) {
        throw new ServerLifecycleOperationError(errorMessage(payload, response));
    }

    return body as unknown as ServerLifecycleResponse;
}

export async function runServerLifecycleOperation(
    input: RunServerLifecycleOperationInput,
): Promise<ServerLifecycleResponse> {
    const operationId = createOperationId();
    const deadline = Date.now() + OPERATION_DEADLINE_MS;
    const mutationBody = JSON.stringify({ ...(input.body ?? {}), operationId });
    let resubmitted = false;

    const requestMutation = async (signal: AbortSignal) => {
        const response = await fetch(input.url, {
            method: input.method,
            headers: { 'Content-Type': 'application/json' },
            body: mutationBody,
            signal,
        });
        return readLifecycleResponse(response, operationId);
    };
    const requestStatus = async (signal: AbortSignal) => {
        const response = await fetch(`/api/proxy/admin/servers/operations/${operationId}`, {
            cache: 'no-store',
            signal,
        });
        return readLifecycleResponse(response, operationId);
    };

    let current = await runRequestWithinDeadline(requestMutation, deadline, operationId, input.signal);
    while (true) {
        input.onProgress?.(current);

        if (current.operationStatus === 'succeeded') return current;
        if (current.operationStatus === 'failed' || current.operationStatus === 'cancelled') {
            throw new ServerLifecycleOperationError(
                current.publicMessage || '서버 작업을 완료하지 못했습니다.',
                current,
            );
        }

        if (current.operationStatus === 'missing') {
            if (!current.resubmitRequired || resubmitted) {
                throw new ServerLifecycleOperationError(
                    current.publicMessage || `deployer에서 작업을 찾지 못했습니다. 작업 ID: ${operationId}`,
                    current,
                );
            }
            resubmitted = true;
            current = await runRequestWithinDeadline(requestMutation, deadline, operationId, input.signal);
            continue;
        }

        if (current.resubmitRequired) {
            throw new ServerLifecycleOperationError(current.publicMessage || '서버 작업 응답이 올바르지 않습니다.', current);
        }

        const remaining = deadline - Date.now();
        if (remaining <= 0) throw new ServerLifecycleOperationTimeoutError(operationId);
        await waitForPoll(Math.min(POLL_INTERVAL_MS, remaining), input.signal);
        if (Date.now() >= deadline) throw new ServerLifecycleOperationTimeoutError(operationId);
        current = await runRequestWithinDeadline(requestStatus, deadline, operationId, input.signal);
    }
}
