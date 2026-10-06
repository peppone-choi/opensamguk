// 군국 표 찾기. 군국 표는 bake 장소 표의 군국에서 온다(WarRoomMap · commanderyCells).

import type { CommanderyCell } from './campaign-map';

/** 城 id 가 속한 군국 — 초점 城 이 같거나, 이름이 같은 군국. */
export function commanderyOfCity(
    commanderies: readonly CommanderyCell[],
    commanderyName: string | undefined,
): CommanderyCell | undefined {
    if (!commanderyName) return undefined;
    return commanderies.find((c) => c.name === commanderyName);
}
