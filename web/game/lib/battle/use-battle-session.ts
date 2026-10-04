'use client';

// 전투 세션 접속(P-C03 참가 · 배치) — join-ticket(K6-12) → WS(K6-13) → SNAPSHOT/ACK(K6-14/15, protocol.ts 초안).
// - 서버가 꺼져 있거나 열리지 않으면(전투 세계 번호 없음 · 티켓 404/403/5xx · 접속 실패) 'unavailable' — 화면은 「전투가 열리지 않음」(가짜 전투 없음).
//   운영 기본은 BATTLE_JOIN_TICKET_ENABLED=false 라 티켓 경로가 없다(계약판 K6-12).
// - 옮기기는 한 번에 하나만 보낸다(마지막 SNAPSHOT/ACK 의 기대 값 셋). 서버가 받아들인 것만 보기에 적용하고, 거절은 사유 코드로 보인다.
// - 재접속 · DELTA 재생은 아직 없다(C2 — lastSeenEventSeq 는 형식만). 끊기면 'closed' 로 보이고 다시 들어오기는 페이지를 다시 연다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchGame } from '../api';
import { applyAcceptedMove, moveTarget, toJoinView, type JoinView } from './join-view';
import {
    battleSocketUrl, battleSubprotocols, decodeServerFrame, deploymentMove, joinTicketPath, newClientCommandId, sourceKeyId,
    type Cell, type RejectCode, type Snapshot,
} from './protocol';

export type UnavailableReason = 'NO_WORLD' | 'TICKET' | 'SOCKET';

export interface PendingMove {
    readonly clientCommandId: string;
    readonly unitId: string;
    readonly cell: Cell;
}

export interface MoveNotice {
    /** 서버 거절 사유(표 안 코드) · 표 밖 거절(null) · 화면이 미리 막은 사유(blocked). */
    readonly kind: 'rejected' | 'blocked';
    readonly code: RejectCode | null;
    readonly text: string | null;
}

export type BattleSession =
    | { readonly state: 'unavailable'; readonly reason: UnavailableReason }
    | { readonly state: 'connecting' }
    | {
        readonly state: 'ready';
        readonly snapshot: Snapshot;
        readonly view: JoinView;
        readonly pending: PendingMove | null;
        readonly notice: MoveNotice | null;
    }
    | { readonly state: 'closed' };

export interface UseBattleSession {
    readonly session: BattleSession;
    /** 고른 부곡을 그 칸으로 — 화면 판단(구역 밖 등)이면 보내지 않고 notice 로 막는다. */
    readonly move: (unitId: string, cell: Cell) => void;
}

export function useBattleSession(serverId: string | null, worldId: number | null, battleId: string): UseBattleSession {
    const [session, setSession] = useState<BattleSession>(() => (worldId == null || !serverId ? { state: 'unavailable', reason: 'NO_WORLD' } : { state: 'connecting' }));
    const socketRef = useRef<WebSocket | null>(null);
    const latest = useRef<{ epoch: string; authority: string } | null>(null);
    // 보내기는 상태 갱신 함수 밖에서 한다(StrictMode 가 갱신 함수를 두 번 불러도 한 번만 보내게) — 지금 상태는 이 ref 로 읽는다.
    const sessionRef = useRef(session);
    sessionRef.current = session;

    useEffect(() => {
        if (worldId == null || !serverId) {
            setSession({ state: 'unavailable', reason: 'NO_WORLD' });
            return;
        }
        let cancelled = false;
        let socket: WebSocket | null = null;
        let opened = false;
        setSession({ state: 'connecting' });
        (async () => {
            let ticket: string | null = null;
            try {
                const res = await fetchGame(joinTicketPath(worldId, battleId), { method: 'POST', cache: 'no-store' });
                if (res.ok) {
                    const body = (await res.json()) as { joinTicket?: unknown };
                    if (typeof body.joinTicket === 'string' && body.joinTicket.length > 0) ticket = body.joinTicket;
                }
            } catch {
                ticket = null;
            }
            if (cancelled) return;
            if (!ticket) {
                setSession({ state: 'unavailable', reason: 'TICKET' });
                return;
            }
            try {
                socket = new WebSocket(battleSocketUrl(window.location.origin, serverId, worldId, battleId, '0'), battleSubprotocols(ticket));
            } catch {
                setSession({ state: 'unavailable', reason: 'SOCKET' });
                return;
            }
            socketRef.current = socket;
            socket.onopen = () => {
                opened = true;
            };
            socket.onmessage = (event: MessageEvent) => {
                if (typeof event.data !== 'string') return;
                const decoded = decodeServerFrame(event.data);
                if (!decoded.ok) return;
                const frame = decoded.frame;
                if (frame.t === 'SNAPSHOT') {
                    latest.current = { epoch: frame.sessionEpoch, authority: frame.authorityRevision };
                    setSession({ state: 'ready', snapshot: frame, view: toJoinView(frame, Date.now()), pending: null, notice: null });
                    return;
                }
                if (frame.t === 'ACK') {
                    setSession((s) => {
                        if (s.state !== 'ready' || !s.pending || s.pending.clientCommandId !== frame.clientCommandId) return s;
                        if (frame.verdict === 'ACCEPTED') {
                            return { ...s, view: applyAcceptedMove(s.view, s.pending.unitId, s.pending.cell, frame.deploymentRevisionAfter), pending: null, notice: null };
                        }
                        return { ...s, pending: null, notice: { kind: 'rejected', code: frame.reasonCode, text: null } };
                    });
                }
            };
            socket.onclose = () => {
                if (cancelled) return;
                setSession((s) => (s.state === 'ready' ? { state: 'closed' } : opened ? { state: 'closed' } : { state: 'unavailable', reason: 'SOCKET' }));
            };
        })();
        return () => {
            cancelled = true;
            socketRef.current = null;
            socket?.close();
        };
    }, [serverId, worldId, battleId]);

    const move = useCallback((unitId: string, cell: Cell) => {
        const s = sessionRef.current;
        if (s.state !== 'ready' || s.pending) return;
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
    }, []);

    return { session, move };
}
