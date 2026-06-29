'use client';

// PartialReservedCommand — the reserved-turn ring panel (spec §5.3, legacy PartialReservedCommand.vue).
// The legacy 5-col table is [Turn#] [Y/M] [Time] [Command brief] [edit ✎] over `maxTurn` slots, fed by
// SammoAPI.Command.GetReservedCommand().
//
// [P0-01] game-api `GET /api/reserved-commands`를 소비해 실제 예약 슬롯을 렌더한다.
// 빈 슬롯(slots에 없는 turnIdx)만 '휴식'으로 표시 — 전 슬롯 하드코딩 위조 금지.

import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import { formatYearMonthPhase, TURN_PHASE_LABELS } from '../../lib/format';
import type { ReservedSlot } from '../../lib/types';
import CommandModal from '../CommandModal';

const DEFAULT_VIEW_TURNS = 36;

function outcomeReason(out: { reason?: unknown }): string | null {
    return typeof out.reason === 'string' && out.reason.length > 0 ? out.reason : null;
}

function errorMessage(e: unknown): string {
    return e instanceof Error ? e.message : '';
}

export interface PartialReservedCommandProps {
    /** Caller's own general id (front-info.general.generalId) — required to reserve. */
    generalId: number;
    /** Caller's own nation id — scopes the nation picker. */
    nationId?: number;
    /** Total reservable slots (const.maxTurn); falls back to the 14-row view. */
    maxTurn?: number;
    /** Soft-refresh after a reserve. */
    onReserved?: () => void;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
}

export default function PartialReservedCommand({
    generalId,
    nationId,
    maxTurn,
    onReserved,
    onToast,
}: PartialReservedCommandProps) {
    const total = Math.max(DEFAULT_VIEW_TURNS, maxTurn && maxTurn > 0 ? maxTurn : DEFAULT_VIEW_TURNS);
    const [editTurnIdx, setEditTurnIdx] = useState<number | null>(null);
    const [slots, setSlots] = useState<ReservedSlot[]>([]);
    const [meta, setMeta] = useState<{
        year?: number;
        month?: number;
        turnPhase?: number | null;
        turnTime?: string;
        turnTerm?: number;
    }>({});
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);
    // P0-02 — 개인 예약 링 당기기/미루기/반복 입력값.
    const [pushAmount, setPushAmount] = useState(1);
    const [repeatAmount, setRepeatAmount] = useState(1);

    const viewCount = total;
    const slotMap = new Map(slots.map((s) => [s.turnIdx, s]));

    useEffect(() => {
        if (!generalId) return;
        let alive = true;
        setLoading(true);
        setError(null);
        api.reservedCommands(generalId)
            .then((res) => {
                if (!alive) return;
                setSlots(res.slots ?? []);
                setMeta({
                    year: res.year ?? undefined,
                    month: res.month ?? undefined,
                    turnPhase: res.turnPhase ?? undefined,
                    turnTime: res.turnTime ?? undefined,
                    turnTerm: res.turnTerm ?? undefined,
                });
            })
            .catch((err: unknown) => {
                if (!alive) return;
                const msg = err instanceof Error ? err.message : '예약 명령 조회 실패';
                setError(msg);
            })
            .finally(() => {
                if (alive) setLoading(false);
            });
        return () => {
            alive = false;
        };
    }, [generalId, refreshKey]);

    const handleReserved = () => {
        setEditTurnIdx(null);
        setRefreshKey((k) => k + 1);
        onReserved?.();
    };

    const parseTurnTime = (tt?: string): Date | null => {
        if (!tt) return null;
        const m = tt.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})(?::(\d{2}))?/);
        if (!m) return null;
        return new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), Number(m[4]), Number(m[5]), Number(m[6] ?? 0));
    };

    const pad2 = (value: number) => String(value).padStart(2, '0');

    const slotYearMonthText = (turnIdx: number) => {
        if (meta.year == null || meta.month == null) return '';
        const phase = meta.turnPhase != null && meta.turnPhase >= 1 && meta.turnPhase <= 3 ? meta.turnPhase : 1;
        const absolutePhase = (meta.month - 1) * 3 + (phase - 1) + turnIdx;
        const year = meta.year + Math.floor(absolutePhase / 36);
        const phaseOfYear = ((absolutePhase % 36) + 36) % 36;
        const month = Math.floor(phaseOfYear / 3) + 1;
        return formatYearMonthPhase(year, month, TURN_PHASE_LABELS[phaseOfYear % 3]);
    };

    const slotTimeFor = (turnIdx: number) => {
        const base = parseTurnTime(meta.turnTime);
        if (!base || !meta.turnTerm) return '';
        const time = new Date(base.getTime() + turnIdx * meta.turnTerm * 60_000);
        return `${pad2(time.getHours())}:${pad2(time.getMinutes())}`;
    };

    return (
        <section className="reserved-command-panel" id="reservedCommandPanel" aria-label="명령 목록">
            <div className="rcp-title">명령 목록</div>
            {loading && <div className="rcp-flag">불러오는 중…</div>}
            {error && <div className="rcp-flag" style={{ color: 'var(--color-danger, #dc2626)' }}>{error}</div>}

            <div className="rcp-table">
                {Array.from({ length: viewCount }, (_, i) => i).map((turnIdx) => {
                    const slot = slotMap.get(turnIdx);
                    return (
                        <div key={turnIdx} className="rcp-row">
                            <div className="rcp-idx">{turnIdx + 1}</div>
                            <div className="rcp-ym">{slotYearMonthText(turnIdx)}</div>
                            <div className="rcp-time">{slotTimeFor(turnIdx)}</div>
                            <div className="rcp-brief" title={slot?.brief ?? '휴식'}>{slot?.brief ?? '휴식'}</div>
                            <div className="rcp-edit">
                                <button
                                    type="button"
                                    className="rcp-edit-btn"
                                    aria-label={`${turnIdx + 1}턴 명령 편집`}
                                    onClick={() => setEditTurnIdx(turnIdx)}
                                >
                                    ✎
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>

            <div className="rcp-actions">
                <span style={{ marginLeft: 'auto', display: 'inline-flex', gap: 4, alignItems: 'center' }}>
                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>당기기/미루기</span>
                    <input
                        type="number"
                        value={pushAmount}
                        onChange={(e) => setPushAmount(Number(e.target.value))}
                        style={{ width: '4ch', fontSize: 'var(--text-xs)' }}
                    />
                    <button
                        type="button"
                        style={{ fontSize: 'var(--text-xs)' }}
                        onClick={async () => {
                            try {
                                const out = await api.commandQueue.push(generalId, pushAmount);
                                if (out.status === 'AVAILABLE') {
                                    onToast('적용되었습니다.', 'success');
                                    setRefreshKey((k) => k + 1);
                                    onReserved?.();
                                } else {
                                    onToast(outcomeReason(out) ?? '적용할 수 없습니다.', 'error');
                                }
                            } catch (e: unknown) {
                                onToast('요청에 실패했습니다: ' + errorMessage(e), 'error');
                            }
                        }}
                    >
                        적용
                    </button>
                    <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)', marginLeft: 4 }}>반복</span>
                    <input
                        type="number"
                        value={repeatAmount}
                        onChange={(e) => setRepeatAmount(Number(e.target.value))}
                        style={{ width: '4ch', fontSize: 'var(--text-xs)' }}
                    />
                    <button
                        type="button"
                        style={{ fontSize: 'var(--text-xs)' }}
                        onClick={async () => {
                            try {
                                const out = await api.commandQueue.repeat(generalId, repeatAmount);
                                if (out.status === 'AVAILABLE') {
                                    onToast('적용되었습니다.', 'success');
                                    setRefreshKey((k) => k + 1);
                                    onReserved?.();
                                } else {
                                    onToast(outcomeReason(out) ?? '적용할 수 없습니다.', 'error');
                                }
                            } catch (e: unknown) {
                                onToast('요청에 실패했습니다: ' + errorMessage(e), 'error');
                            }
                        }}
                    >
                        적용
                    </button>
                </span>
            </div>

            {editTurnIdx != null && (
                <CommandModal
                    onClose={() => setEditTurnIdx(null)}
                    onToast={onToast}
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={editTurnIdx}
                    onReserved={handleReserved}
                />
            )}
        </section>
    );
}
