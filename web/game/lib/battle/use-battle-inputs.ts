'use client';

import { useCallback, type Dispatch, type RefObject, type SetStateAction } from 'react';
import { moveTarget } from './join-view';
import {
    battleCommand, deploymentMove, newClientCommandId, sourceKeyId,
    type BattleOrder, type Cell, type CommandScope, type RallyPoint,
} from './protocol';
import type { BattleSession, UseBattleSession } from './use-battle-session';

interface BattleInputRefs {
    readonly sessionRef: RefObject<BattleSession>;
    readonly socketRef: RefObject<WebSocket | null>;
    readonly latest: RefObject<{ epoch: string; authority: string } | null>;
    readonly setSession: Dispatch<SetStateAction<BattleSession>>;
}

/** Draft input guards stay separate from ticket/socket lifecycle and strict JOINING reads. */
export function useBattleInputs({ sessionRef, socketRef, latest, setSession }: BattleInputRefs): Pick<UseBattleSession, 'move' | 'command'> {
    const move = useCallback((unitId: string, cell: Cell) => {
        const s = sessionRef.current;
        if (s.state !== 'ready' || s.pending || s.notice?.kind === 'resyncing') return;
        const target = moveTarget(s.view, unitId, cell);
        if (target.kind === 'blocked') {
            setSession((cur) => (cur.state === 'ready' ? { ...cur, notice: { kind: 'blocked', code: null, text: target.reason } } : cur));
            return;
        }
        const unit = s.view.units.find((u) => u.id === unitId);
        const socket = socketRef.current;
        const head = latest.current;
        if (!unit || !socket || socket.readyState !== WebSocket.OPEN || !head || s.view.revision == null) return;
        const clientCommandId = newClientCommandId();
        socket.send(deploymentMove({
            clientCommandId, sourceKey: unit.sourceKey, targetCell: cell,
            expectedEpoch: head.epoch, expectedAuthorityRevision: head.authority, expectedDeploymentRevision: s.view.revision,
        }));
        const pending = { clientCommandId, unitId: sourceKeyId(unit.sourceKey), cell };
        sessionRef.current = { ...s, pending, notice: null };
        setSession((cur) => (cur.state === 'ready' ? { ...cur, pending, notice: null } : cur));
    }, [sessionRef, socketRef, latest, setSession]);

    const command = useCallback((scope: CommandScope, count: number, order: BattleOrder, rally: RallyPoint) => {
        const s = sessionRef.current;
        if (s.state !== 'ready' || s.pendingCommand || s.notice?.kind === 'resyncing') return;
        const socket = socketRef.current;
        const head = latest.current;
        if (!socket || socket.readyState !== WebSocket.OPEN || !head) return;
        const clientCommandId = newClientCommandId();
        socket.send(battleCommand({
            clientCommandId, expectedEpoch: head.epoch, expectedAuthorityRevision: head.authority, issuedTick: s.snapshot.tick, scope, order, rally,
        }));
        const pendingCommand = { clientCommandId, order, count };
        sessionRef.current = { ...s, pendingCommand, notice: null };
        setSession((cur) => (cur.state === 'ready' ? { ...cur, pendingCommand, notice: null } : cur));
    }, [sessionRef, socketRef, latest, setSession]);

    return { move, command };
}
