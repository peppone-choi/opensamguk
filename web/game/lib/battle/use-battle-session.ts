'use client';

// Actual JOINING snapshots use their own strict, read-only state. The phase-less
// protocol.ts command path below remains a draft; it is not a verified RUNNING contract.
// 전투 세션 접속 — join-ticket → WS → phase JOINING 조회 / 기존 SNAPSHOT·ACK·AUTHORITY 초안.
// - 서버가 꺼져 있거나 열리지 않으면(전투 세계 번호 없음 · 티켓 404/403/5xx · 접속 실패) 'unavailable' — 화면은 「전투가 열리지 않음」(가짜 전투 없음).
//   운영 기본은 BATTLE_JOIN_TICKET_ENABLED=false 라 티켓 경로가 없다(계약판 K6-12).
// - 옮기기 · 명령은 각각 한 번에 하나만 보낸다(마지막 SNAPSHOT/ACK/AUTHORITY 의 기대 값). 서버가 받아들인 것만 보기에 적용하고, 거절은 사유 코드로 보인다.
// - 배치 · 지휘권 · 회차가 서버에서 먼저 바뀐 거절(RESYNC_CAUSE — STALE_DEPLOYMENT · STALE_AUTHORITY · STALE_EPOCH)은 새 티켓 · 새 접속으로
//   SNAPSHOT 을 한 번 다시 받아 기대 값 · 자리를 맞춘다. 그동안 판은 그대로 두고 옮기기 · 명령은 막는다(옛 기대 값으로 또 거절당하지 않게).
//   계약에 SNAPSHOT 다시 달라는 프레임이 없어 접속을 새로 연다. 사람이 옮기거나 명령할 때만 일어나므로 저절로 되풀이되지 않는다.
// - 끊김 뒤 자동 재접속 · DELTA 재생은 아직 없다(C2 — lastSeenEventSeq 는 형식만). 끊기면 'closed' 로 보이고 다시 들어오기는 페이지를 다시 연다.
import { useCallback, useEffect, useRef, useState } from 'react';
import { fetchGame } from '../api';
import { applyAcceptedMove, toJoinView, type JoinView } from './join-view';
import { toLiveView, type LiveView } from './live-view';
import { decodeJoiningSnapshot, type JoiningSnapshot } from './joining-snapshot';
import { useBattleInputs } from './use-battle-inputs';
import {
    battleSocketUrl, battleSubprotocols, decodeServerFrame, joinTicketPath, sourceKeyId,
    RESYNC_CAUSE, type BattleOrder, type Cell, type CommandScope, type RallyPoint, type RejectCode, type Snapshot,
} from './protocol';

export type UnavailableReason = 'NO_WORLD' | 'TICKET' | 'SOCKET';

export interface PendingMove {
    readonly clientCommandId: string;
    readonly unitId: string;
    readonly cell: Cell;
}

/** 실시간 전투 명령 — 영수증을 기다리는 동안. */
export interface PendingCommand {
    readonly clientCommandId: string;
    readonly order: BattleOrder;
    readonly count: number;
}

export interface MoveNotice {
    /**
     * 서버 거절 사유(표 안 코드) · 표 밖 거절(null) · 화면이 미리 막은 사유(blocked) · 명령 받음(accepted) ·
     * 다시 맞추는 중(resyncing — 새 SNAPSHOT 대기, 옮기기 · 명령 막음) · 다시 맞춤(resynced — code 는 그 원인 거절).
     */
    readonly kind: 'rejected' | 'blocked' | 'accepted' | 'resyncing' | 'resynced';
    readonly code: RejectCode | null;
    readonly text: string | null;
}

export type BattleSession =
    | { readonly state: 'unavailable'; readonly reason: UnavailableReason }
    | { readonly state: 'connecting' }
    | { readonly state: 'protocol-error' }
    | { readonly state: 'joining'; readonly snapshot: JoiningSnapshot }
    | {
        readonly state: 'ready';
        readonly snapshot: Snapshot;
        readonly view: JoinView;
        /** 진행 중(배치 단계 아님)일 때의 보기. */
        readonly live: LiveView;
        readonly pending: PendingMove | null;
        readonly pendingCommand: PendingCommand | null;
        readonly notice: MoveNotice | null;
    }
    | { readonly state: 'closed' };

export interface UseBattleSession {
    readonly session: BattleSession;
    /** 읽을 수 없는 전투 자료는 사람이 다시 읽을 때만 새 티켓·접속으로 재시도한다. */
    readonly retry: () => void;
    /** 고른 부곡을 그 칸으로 — 화면 판단(구역 밖 등)이면 보내지 않고 notice 로 막는다. */
    readonly move: (unitId: string, cell: Cell) => void;
    /** 실시간 전투 명령 — 고른 부곡(또는 내 부곡 전부)에 6명령 하나와 집결점. */
    readonly command: (scope: CommandScope, count: number, order: BattleOrder, rally: RallyPoint) => void;
}

export function useBattleSession(serverId: string | null, worldId: number | null, battleId: string): UseBattleSession {
    const [session, setSession] = useState<BattleSession>(() => (worldId == null || !serverId ? { state: 'unavailable', reason: 'NO_WORLD' } : { state: 'connecting' }));
    const socketRef = useRef<WebSocket | null>(null);
    const latest = useRef<{ epoch: string; authority: string } | null>(null);
    const controllers = useRef(new Map<string, 'HUMAN' | 'AI'>());
    // 보내기는 상태 갱신 함수 밖에서 한다(StrictMode 가 갱신 함수를 두 번 불러도 한 번만 보내게) — 지금 상태는 이 ref 로 읽는다.
    const sessionRef = useRef(session);
    sessionRef.current = session;
    // 다시 맞추기 — 접속을 새로 연다(generation). 그 접속은 판을 지우지 않는다(resyncRef).
    const [generation, setGeneration] = useState(0);
    const resyncRef = useRef(false);

    useEffect(() => {
        if (worldId == null || !serverId) {
            setSession({ state: 'unavailable', reason: 'NO_WORLD' });
            return;
        }
        let cancelled = false;
        let socket: WebSocket | null = null;
        let opened = false;
        let failed = false;
        const commit = (next: BattleSession) => {
            sessionRef.current = next;
            setSession(next);
        };
        const resync = resyncRef.current;
        resyncRef.current = false;
        if (!resync) setSession({ state: 'connecting' });
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
                if (cancelled || failed || typeof event.data !== 'string') return;
                const protocolError = () => {
                    failed = true;
                    latest.current = null;
                    controllers.current.clear();
                    socketRef.current = null;
                    commit({ state: 'protocol-error' });
                    socket?.close();
                };
                let value: unknown;
                try { value = JSON.parse(event.data); } catch { protocolError(); return; }
                if (value != null && typeof value === 'object' && 'phase' in value && value.phase === 'JOINING') {
                    const joining = decodeJoiningSnapshot(value);
                    if (!joining || joining.worldId !== worldId || joining.battleId !== battleId) {
                        protocolError();
                        return;
                    }
                    latest.current = null;
                    controllers.current.clear();
                    commit({ state: 'joining', snapshot: joining });
                    return;
                }
                // A projected JOINING session cannot fall into the unrelated draft command path.
                if (sessionRef.current.state === 'joining') { protocolError(); return; }
                const decoded = decodeServerFrame(event.data);
                if (!decoded.ok) {
                    protocolError();
                    return;
                }
                const frame = decoded.frame;
                if (frame.t === 'SNAPSHOT') {
                    latest.current = { epoch: frame.sessionEpoch, authority: frame.authorityRevision };
                    const prev = sessionRef.current;
                    const cause = prev.state === 'ready' && prev.notice?.kind === 'resyncing' ? prev.notice.code : null;
                    commit({
                        state: 'ready', snapshot: frame, view: toJoinView(frame, Date.now()), live: toLiveView(frame, controllers.current),
                        pending: null, pendingCommand: null, notice: cause ? { kind: 'resynced', code: cause, text: null } : null,
                    });
                    return;
                }
                if (frame.t === 'AUTHORITY') {
                    controllers.current.set(sourceKeyId(frame.sourceKey), frame.controller);
                    if (latest.current) latest.current = { ...latest.current, authority: frame.authorityRevision };
                    const s = sessionRef.current;
                    if (s.state === 'ready') commit({ ...s, live: toLiveView(s.snapshot, controllers.current) });
                    return;
                }
                if (frame.t === 'ACK') {
                    const s = sessionRef.current;
                    if (s.state !== 'ready') return;
                    const stale = frame.verdict === 'REJECTED' && frame.reasonCode != null && RESYNC_CAUSE[frame.reasonCode] != null;
                    const rejected: MoveNotice = { kind: stale ? 'resyncing' : 'rejected', code: frame.reasonCode, text: null };
                    if (s.pendingCommand && s.pendingCommand.clientCommandId === frame.clientCommandId) {
                        const c = s.pendingCommand;
                        commit(frame.verdict === 'ACCEPTED'
                            ? { ...s, pendingCommand: null, notice: { kind: 'accepted', code: null, text: `명령 받음 — 고른 부곡 ${c.count}개` } }
                            : { ...s, pendingCommand: null, notice: rejected });
                    } else if (s.pending && s.pending.clientCommandId === frame.clientCommandId) {
                        commit(frame.verdict === 'ACCEPTED'
                            ? { ...s, view: applyAcceptedMove(s.view, s.pending.unitId, s.pending.cell, frame.deploymentRevisionAfter), pending: null, notice: null }
                            : { ...s, pending: null, notice: rejected });
                    } else {
                        return;
                    }
                    if (stale) {
                        resyncRef.current = true;
                        setGeneration((g) => g + 1);
                    }
                }
            };
            socket.onclose = () => {
                if (cancelled || failed) return;
                setSession((s) => (s.state === 'ready' || s.state === 'joining' ? { state: 'closed' } : opened ? { state: 'closed' } : { state: 'unavailable', reason: 'SOCKET' }));
            };
        })();
        return () => {
            cancelled = true;
            socketRef.current = null;
            socket?.close();
        };
    }, [serverId, worldId, battleId, generation]);

    const { move, command } = useBattleInputs({ sessionRef, socketRef, latest, setSession });

    const retry = useCallback(() => {
        if (sessionRef.current.state !== 'protocol-error') return;
        resyncRef.current = false;
        sessionRef.current = { state: 'connecting' };
        setSession(sessionRef.current);
        setGeneration(g => g + 1);
    }, []);

    return { session, move, command, retry };
}
