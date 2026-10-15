// 작전실 읽기 범위 — 열린 명령 흐름 · 목적지 지도가 어느 서버 · 장수 · 세대 · 순의 선택지를 보이는지.
// 값은 서버가 준 것만 쓴다(front-info global.year · month · turnPhase · generation, 없으면 null). 로컬 읽기 번호를 세대로 삼지 않는다.
import { useState } from 'react';
import { normalizeServerGeneration } from './command-flow/server-generation';
import type { FrontInfoResponse } from './types';

/** 서버 · 장수 · 세대 · 년 · 월 · 순의 비교 열쇠. 같은 값이면 같은 글이라 같은 범위의 다시 그리기는 다시 읽지 않는다. */
export function warRoomReadScopeKey(serverId: string | undefined, generalId: number | null, frontInfo: FrontInfoResponse | null): string {
    const g = frontInfo?.global;
    return JSON.stringify([
        serverId ?? null, generalId,
        normalizeServerGeneration(g?.generation), g?.year ?? null, g?.month ?? null, g?.turnPhase ?? null,
    ]);
}

/**
 * 범위 열쇠가 바뀔 때마다 1 올라가는 로컬 번호(되돌아온 범위 A→B→A 도 새 번호). 같은 렌더에서 올리므로
 * 바뀐 범위를 처음 그리는 커밋부터 새 번호를 받는다 — effect 를 기다리는 사이 옛 후보가 남지 않는다.
 */
export function useReadRevision(scopeKey: string): number {
    const [state, setState] = useState({ key: scopeKey, revision: 0 });
    if (state.key === scopeKey) return state.revision;
    const next = { key: scopeKey, revision: state.revision + 1 };
    setState(next);
    return next.revision;
}
