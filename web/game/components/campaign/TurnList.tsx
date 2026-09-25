'use client';

import { useEffect, useState } from 'react';
import { Chip, Panel, SectionHeader } from '@opensamguk/ui';
import CommandModal from '@/components/CommandModal';
import { api } from '@/lib/api';
import type { ReservedCommandsResponse } from '@/lib/types';
import { HwihaEmpty } from './HwihaStates';

/** 휘하는 개인 턴 12순이다(정본 설계 §3). */
const HWIHA_SLOTS = 12;

export interface TurnListProps {
    readonly generalId: number;
    readonly nationId: number;
    readonly refreshKey: number;
    readonly isHwihaWorld: boolean;
    readonly onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
    readonly onReserved: () => void;
}

/**
 * 명령 목록 12순 — 직접 행동, 한 순에 하나. 예약 링은 기존 `/api/reserved-commands` 를 읽고,
 * 빈 순의 「+ 예약」은 기존 명령 창(휘하 월드에서는 출사·출병만 받는다)을 연다.
 */
export default function TurnList({ generalId, nationId, refreshKey, isHwihaWorld, onToast, onReserved }: TurnListProps) {
    const [data, setData] = useState<ReservedCommandsResponse | null>(null);
    const [error, setError] = useState<string | null>(null);
    const [localKey, setLocalKey] = useState(0);
    const [editTurnIdx, setEditTurnIdx] = useState<number | null>(null);

    useEffect(() => {
        let alive = true;
        api.reservedCommands(generalId)
            .then((res) => {
                if (alive) {
                    setData(res);
                    setError(null);
                }
            })
            .catch((e: unknown) => {
                if (alive) setError(e instanceof Error ? e.message : '명령 목록을 불러오지 못했습니다.');
            });
        return () => {
            alive = false;
        };
    }, [generalId, refreshKey, localKey]);

    const slots = Array.from({ length: HWIHA_SLOTS }, (_, turnIdx) => data?.slots.find((s) => s.turnIdx === turnIdx) ?? null);

    return (
        <Panel id="reservedCommandPanel" style={{ padding: 12 }}>
            <SectionHeader
                title={`명령 목록 ${HWIHA_SLOTS}순`}
                sub={data?.turnTime ? `직접 행동 · 한 순에 하나 · 다음 실행 ${data.turnTime}` : '직접 행동 · 한 순에 하나'}
            />
            {error ? <HwihaEmpty>{`불러오지 못했습니다 — ${error}`}</HwihaEmpty> : null}
            {!data && !error ? <HwihaEmpty>불러오는 중입니다.</HwihaEmpty> : null}
            {data ? (
                <div style={{ display: 'grid', gap: 4, paddingTop: 8 }}>
                    {slots.map((slot, turnIdx) => {
                        // 예약 링은 빈 순을 slots 에 싣지 않는다 — 없는 turnIdx 가 빈 순이다.
                        const empty = !slot;
                        return (
                            <div
                                key={turnIdx}
                                style={{
                                    display: 'grid',
                                    gridTemplateColumns: '28px 1fr auto',
                                    gap: 8,
                                    alignItems: 'center',
                                    minHeight: 44,
                                    padding: '4px 6px',
                                    border: empty ? '1px dotted var(--line-2)' : '1px solid var(--line)',
                                    borderRadius: 3,
                                    background: empty ? 'transparent' : 'var(--panel)',
                                }}
                            >
                                <span className="os-num" style={{ color: 'var(--muted)', fontSize: 12 }}>
                                    {turnIdx + 1}
                                </span>
                                <span
                                    style={{
                                        minWidth: 0,
                                        fontSize: 13,
                                        fontWeight: empty ? 400 : 700,
                                        color: empty ? 'var(--muted)' : undefined,
                                        overflow: 'hidden',
                                        textOverflow: 'ellipsis',
                                        whiteSpace: 'nowrap',
                                    }}
                                >
                                    {empty ? '빈 순' : slot!.brief || slot!.action}
                                </span>
                                {empty ? (
                                    <button
                                        type="button"
                                        className="os-button os-button--ghost os-button--sm"
                                        onClick={() => setEditTurnIdx(turnIdx)}
                                        disabled={!isHwihaWorld}
                                        title={isHwihaWorld ? undefined : '휘하 규칙 서버에서만 예약합니다'}
                                    >
                                        + 예약
                                    </button>
                                ) : (
                                    <Chip tone="info">예약</Chip>
                                )}
                            </div>
                        );
                    })}
                </div>
            ) : null}
            {editTurnIdx != null ? (
                <CommandModal
                    ruleProfile="HWIHA"
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={editTurnIdx}
                    onClose={() => setEditTurnIdx(null)}
                    onToast={onToast}
                    onReserved={() => {
                        setLocalKey((k) => k + 1);
                        onReserved();
                    }}
                />
            ) : null}
        </Panel>
    );
}
