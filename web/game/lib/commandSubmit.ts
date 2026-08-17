import {
    api,
    isIntakeDenied,
    isIntakeQueued,
    pollCommandResultResponse,
    type CommandResultResolved,
    type CommandResultResponse,
} from './api';
import { subscribeCommandSettled } from './commandResultEvents';
import type { IntakeOutcome } from './types';

/**
 * OPENSAM-45 (1-e) — 결과를 기다리는 두 경로를 경주시킨다.
 *
 * 깨움 신호에는 식별자가 없으므로(월드 전역 브로드캐스트 — `commandResultEvents.ts` 참고) 신호가
 * 올 때마다 **자기** requestId로 정본을 한 번 읽고, RESOLVED일 때만 그것이 결론이 된다. 남의
 * 명령이 만든 신호에는 헛읽기 한 번으로 끝나고 기다림은 이어진다.
 *
 * 신호가 없거나(SSE 끊김·프록시 버퍼링·Shell 밖 화면) 신호보다 정본 반영이 늦으면 **이미 돌고
 * 있던 폴링**이 그대로 결론을 낸다. 즉 신호는 지연을 줄일 뿐 결과의 유일한 근거가 아니다 —
 * 신호 유실이 곧 결과 유실이 되면 안 된다. 폴링은 늘 6초 안에 끝나므로 이 경주는 멈추지 않는다.
 *
 * 신호로 결론이 나면 폴링을 끊는다. 안 끊으면 이미 손에 든 결과를 두고 요청을 19번 더 쏜다.
 */
async function awaitCommandResult(requestId: string): Promise<CommandResultResponse | null> {
    const pollAbort = new AbortController();
    const polled = pollCommandResultResponse(requestId, pollAbort.signal).then(result => ({
        from: 'poll' as const,
        result,
    }));

    let unsubscribe: () => void = () => {};
    const woken = new Promise<{ from: 'wake'; result: CommandResultResponse }>(resolve => {
        unsubscribe = subscribeCommandSettled(() => {
            void api
                .commandResult(requestId)
                .then(result => {
                    if (result?.status === 'RESOLVED') resolve({ from: 'wake', result });
                })
                .catch(() => {});
        });
    });

    try {
        const first = await Promise.race([polled, woken]);
        if (first.from === 'wake') pollAbort.abort();
        return first.result;
    } finally {
        unsubscribe();
    }
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
