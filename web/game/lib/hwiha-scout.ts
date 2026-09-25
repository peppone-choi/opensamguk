import { api, isIntakeDenied, isIntakeQueued } from './api';
import type { ScoutOption } from './hwiha-reads';

export async function reserveHwihaScout(generalId: number, option: ScoutOption): Promise<{
    ok: boolean; message: string;
}> {
    try {
        const reserved = await api.reservedCommands(generalId);
        const used = new Set(reserved.slots.map((slot) => slot.turnIdx));
        const turnIdx = Array.from({ length: 12 }, (_, index) => index).find((index) => !used.has(index));
        if (turnIdx === undefined) return { ok: false, message: '명령 목록 12순이 모두 찼습니다.' };
        const result = await api.command('action.scout', { commanderyId: option.id }, generalId, turnIdx);
        if (isIntakeQueued(result)) return { ok: true, message: `${option.name}에 첩보를 ${turnIdx + 1}순에 예약했습니다.` };
        if (isIntakeDenied(result)) return { ok: false, message: result.reason ?? '첩보를 예약할 수 없습니다.' };
        return { ok: false, message: '첩보를 예약하지 못했습니다.' };
    } catch (error) {
        return { ok: false, message: error instanceof Error ? error.message : '첩보를 예약하지 못했습니다.' };
    }
}
