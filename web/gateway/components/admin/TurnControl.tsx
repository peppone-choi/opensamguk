'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Button, Chip, ConfirmDialog, Panel, SectionHeader, Seg } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import TurnCatchUpControl, { type AdminCatchUpInfo } from './TurnCatchUpControl';

/** 턴 · 따라잡기 · 서버 탭이 같이 쓰는 게임 서버(설계서 §3.4 T1). */
export interface AdminServer {
    readonly id: string;
    readonly name: string;
    readonly generation?: number | null;
}

/** game-engine StatusController DTO 미러 — GET /admin/turn-daemon/status. */
export interface TurnDaemonStatus {
    readonly paused: boolean;
    readonly running?: boolean;
    readonly statusLabel?: string;
    readonly catchUp?: AdminCatchUpInfo | null;
}

export function serverLabel(server: AdminServer): string {
    return server.generation != null ? `${server.name} ${server.generation}기` : server.name;
}

/** 게임 서버 고르기 — 서버가 하나면 이름만, 여럿이면 나눔 선택(44). */
export function AdminServerPicker({ servers, value, onChange }: {
    readonly servers: readonly AdminServer[] | null;
    readonly value: string;
    readonly onChange: (serverId: string) => void;
}) {
    if (servers === null) return <StateLine kind="loading" title="게임 서버 목록을 불러오는 중" />;
    if (servers.length === 0) return <StateLine kind="empty" title="등록된 게임 서버가 없습니다." />;
    return (
        <div className="admin31-picker">
            <span className="gw31-field__label">게임 서버</span>
            <Seg label="게임 서버" options={servers.map((s) => ({ value: s.id, label: serverLabel(s) }))} value={value} onChange={onChange} scroll />
        </div>
    );
}

async function daemonStatus(serverId: string): Promise<TurnDaemonStatus> {
    const res = await fetch(`/api/proxy/admin/turn-daemon/status?serverId=${encodeURIComponent(serverId)}`, { cache: 'no-store' });
    if (!res.ok) throw new Error(`요청 실패 (${res.status})`);
    return (await res.json()) as TurnDaemonStatus;
}

/** 고른 서버의 턴 데몬 상태 — 서버를 바꾸면 늦게 온 옛 응답은 버린다. */
function useDaemonStatus(serverId: string) {
    const [state, setState] = useState<{ serverId: string; status: TurnDaemonStatus | null; error: boolean } | null>(null);
    const current = useRef(serverId);
    current.current = serverId;
    const reload = useCallback(async () => {
        const id = current.current;
        if (!id) return;
        try {
            const status = await daemonStatus(id);
            if (current.current === id) setState({ serverId: id, status, error: false });
        } catch {
            if (current.current === id) setState({ serverId: id, status: null, error: true });
        }
    }, []);
    useEffect(() => { void reload(); }, [serverId, reload]);
    const mine = state?.serverId === serverId ? state : null;
    return { status: mine?.status ?? null, error: mine?.error ?? false, loading: !!serverId && !mine, reload };
}

/** 턴(설계서 §3.4 T1–T6) — 옛 「락 풀 기 · 락걸기 · 락풀기 · 동결중 · 가동중」을 쉬운 말로. 멈출 때는 확인을 받는다. */
export function TurnControl({ servers, serverId, onSelect }: {
    readonly servers: readonly AdminServer[] | null;
    readonly serverId: string;
    readonly onSelect: (serverId: string) => void;
}) {
    const { status, error, loading, reload } = useDaemonStatus(serverId);
    const [busy, setBusy] = useState(false);
    const [result, setResult] = useState<string | null>(null);
    const [confirming, setConfirming] = useState(false);
    const server = servers?.find((s) => s.id === serverId);

    const act = async (action: 'pause' | 'resume') => {
        setBusy(true);
        setResult(null);
        try {
            const res = await fetch(`/api/proxy/admin/turn-daemon/${action}?serverId=${encodeURIComponent(serverId)}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
            });
            if (!res.ok) setResult(action === 'pause' ? '턴을 멈추지 못했습니다.' : '턴을 다시 돌리지 못했습니다.');
        } catch {
            setResult(action === 'pause' ? '턴을 멈추지 못했습니다.' : '턴을 다시 돌리지 못했습니다.');
        } finally {
            setConfirming(false);
            await reload();
            setBusy(false);
        }
    };

    const stopBlock = busy ? '처리 중입니다' : status?.paused ? '이미 멈춰 있습니다' : null;
    const resumeBlock = busy ? '처리 중입니다' : status && !status.paused ? '이미 돌고 있습니다' : null;
    return (
        <Panel className="admin31-panel" aria-label="턴 멈추기 · 다시 돌리기">
            <SectionHeader as="h2" title="턴 멈추기 · 다시 돌리기" />
            <div className="admin31-body">
                <AdminServerPicker servers={servers} value={serverId} onChange={onSelect} />
                {serverId && loading && <StateLine kind="loading" title="턴 상태를 확인하는 중" />}
                {serverId && error && <StateLine kind="error" title="턴 상태를 불러오지 못했습니다" onRetry={() => void reload()} />}
                {serverId && status && (
                    <>
                        <div className="admin31-row">
                            <span>지금</span>
                            {status.paused ? <Chip tone="rust">턴 멈춤</Chip> : <Chip tone="moss">턴 도는 중</Chip>}
                            <span className="gw31-card__line gw31-card__line--muted">마지막 턴 시각은 서버 대기(K10-01)</span>
                        </div>
                        <div className="gw31-account__actions">
                            {stopBlock ? <Button variant="danger" disabled reason={stopBlock}>턴 멈추기</Button> : <Button variant="danger" onClick={() => setConfirming(true)}>턴 멈추기</Button>}
                            {resumeBlock ? <Button variant="primary" disabled reason={resumeBlock}>다시 돌리기</Button> : <Button variant="primary" onClick={() => void act('resume')}>다시 돌리기</Button>}
                        </div>
                    </>
                )}
                {result && <p className="gw31-alert" role="alert">{result}</p>}
            </div>
            <ConfirmDialog
                open={confirming}
                title="턴 멈추기"
                message={`${server ? serverLabel(server) : serverId} 서버의 턴이 멈춥니다. 「다시 돌리기」 전까지 순이 넘어가지 않습니다.`}
                confirmLabel="턴 멈추기"
                danger
                busy={busy}
                onCancel={() => setConfirming(false)}
                onConfirm={() => void act('pause')}
            />
        </Panel>
    );
}

/** 따라잡기(설계서 §3.4 C1–C7) — 서버마다 늘 보이고, 따라잡는 중이 아니면 「정상 속도로 돌고 있습니다」. */
export function CatchUpTab({ servers, serverId, onSelect }: {
    readonly servers: readonly AdminServer[] | null;
    readonly serverId: string;
    readonly onSelect: (serverId: string) => void;
}) {
    const { status, error, loading, reload } = useDaemonStatus(serverId);
    return (
        <Panel className="admin31-panel" aria-label="밀린 턴 따라잡기">
            <SectionHeader as="h2" title="밀린 턴 따라잡기" />
            <div className="admin31-body">
                <AdminServerPicker servers={servers} value={serverId} onChange={onSelect} />
                {serverId && loading && <StateLine kind="loading" title="따라잡기 상태를 확인하는 중" />}
                {serverId && error && <StateLine kind="error" title="따라잡기 상태를 불러오지 못했습니다" onRetry={() => void reload()} />}
                {serverId && status && !status.catchUp?.active && <p className="gw31-card__line">정상 속도로 돌고 있습니다.</p>}
                {serverId && status?.catchUp?.active && <TurnCatchUpControl catchUp={status.catchUp} serverId={serverId} onChanged={reload} />}
            </div>
        </Panel>
    );
}
