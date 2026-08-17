import {
    api,
    isIntakeDenied,
    isIntakeQueued,
    pollCommandResultResponse,
    type CommandResultResolved,
    type CommandResultResponse,
} from './api';
import { waitForCommandSettled } from './commandResultEvents';
import type { IntakeOutcome } from './types';

// 폴링 루프(`api.ts`의 300ms × 20)와 같은 창. 그 상수는 모듈 밖으로 내보내지 않으므로 여기서
// 파생하지 않는다 — export하면 `@/lib/api`를 좁게 mock한 기존 테스트 4종이 전부 깨진다.
// 어긋나도 안전한 방향이다: 창이 짧으면 신호가 늦게 와서 안 쓰일 뿐, 판정은 언제나 폴링이 맡는다.
const EVENT_WAIT_MS = 6_000;

/**
 * OPENSAM-45 (1-e) — 결과를 기다리는 두 경로를 경주시킨다.
 *
 * push 신호(SSE)가 이기면 즉시 한 번 정본을 읽고 끝낸다. 신호가 없거나(SSE 끊김·프록시 버퍼링·
 * Shell 밖 화면), 신호보다 정본 반영이 늦으면 **이미 돌고 있던 폴링**이 그대로 결론을 낸다.
 * 즉 신호는 지연을 줄일 뿐 결과의 유일한 근거가 아니다 — 신호 유실이 곧 결과 유실이 되면 안 된다.
 *
 * 신호로 결론이 나면 폴링을 끊는다. 안 끊으면 이미 손에 든 결과를 두고 요청을 19번 더 쏜다.
 */
async function awaitCommandResult(requestId: string): Promise<CommandResultResponse | null> {
    const pollAbort = new AbortController();
    const polled = pollCommandResultResponse(requestId, pollAbort.signal);
    const woken = waitForCommandSettled(requestId, EVENT_WAIT_MS).then(hit => (hit ? 'wake' : null));

    const first = await Promise.race([polled, woken]);
    if (first === 'wake') {
        const immediate = await api.commandResult(requestId).catch(() => null);
        if (immediate?.status === 'RESOLVED') {
            pollAbort.abort();
            return immediate;
        }
    } else if (first != null) {
        return first;
    }
    return polled;
}

export type CommandSubmitResult =
    | { status: 'applied'; result: CommandResultResolved }
    | { status: 'reserved'; reason: string; result?: CommandResultResolved; phase?: 'reservationAccepted' }
    | { status: 'rejected'; reason?: string; result?: CommandResultResolved }
    | { status: 'pending'; reason: '처리 지연' };

export async function submitCommandAndAwaitResult(
    submit: () => Promise<IntakeOutcome>,
): Promise<CommandSubmitResult> {
    const accepted = await submit();
    if (isIntakeDenied(accepted)) {
        return { status: 'rejected', reason: accepted.reason };
    }
    if (!isIntakeQueued(accepted) || accepted.requestId == null) {
        return { status: 'pending', reason: '처리 지연' };
    }

    const result = await awaitCommandResult(accepted.requestId);
    if (result == null) {
        return { status: 'pending', reason: '처리 지연' };
    }
    if (result.status === 'PENDING') {
        return result.phase === 'reservationAccepted'
            ? { status: 'reserved', reason: '명령이 예약되었습니다.', phase: result.phase }
            : { status: 'pending', reason: '처리 지연' };
    }
    const commandKind = result.result.commandKind;
    if (
        result.type === 'reservationAccepted' ||
        (commandKind === 'RESERVED_TURN' && result.type !== 'executionApplied' && result.type !== 'executionRejected')
    ) {
        return { status: 'reserved', reason: '명령이 예약되었습니다.', result };
    }
    if (!result.ok) {
        return { status: 'rejected', reason: result.reason, result };
    }
    if (result.type === 'queueMutation' || commandKind === 'QUEUE_MUTATION') {
        return { status: 'reserved', reason: '명령이 예약되었습니다.', result };
    }
    return { status: 'applied', result };
}
