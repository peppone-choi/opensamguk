import {
    isIntakeDenied,
    isIntakeQueued,
    pollCommandResult,
    type CommandResultResolved,
} from './api';
import type { IntakeOutcome } from './types';

export type CommandSubmitResult =
    | { status: 'applied'; result: CommandResultResolved }
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

    const result = await pollCommandResult(accepted.requestId);
    if (result == null) {
        return { status: 'pending', reason: '처리 지연' };
    }
    if (!result.ok) {
        return { status: 'rejected', reason: result.reason, result };
    }
    return { status: 'applied', result };
}
