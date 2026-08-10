import {
    isIntakeDenied,
    isIntakeQueued,
    pollCommandResultResponse,
    type CommandResultResolved,
} from './api';
import type { IntakeOutcome } from './types';

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

    const result = await pollCommandResultResponse(accepted.requestId);
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
