'use client';

// PartialReservedCommand — the reserved-turn ring panel (spec §5.3, legacy PartialReservedCommand.vue).
// The legacy 5-col table is [Turn#] [Y/M] [Time] [Command brief] [edit ✎] over `maxTurn` slots, fed by
// SammoAPI.Command.GetReservedCommand().
//
// [P0-01] game-api `GET /api/reserved-commands`를 소비해 실제 예약 슬롯을 렌더한다.
// 빈 슬롯(slots에 없는 turnIdx)만 '휴식'으로 표시 — 전 슬롯 하드코딩 위조 금지.

import { useEffect, useState } from 'react';
import { api } from '../../lib/api';
import type { ReservedSlot } from '../../lib/types';
import CommandModal from '../CommandModal';

const DEFAULT_VIEW_TURNS = 14; // legacy flippedMaxTurn

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
    const total = maxTurn && maxTurn > 0 ? maxTurn : DEFAULT_VIEW_TURNS;
    const [expanded, setExpanded] = useState(false);
    const [editTurnIdx, setEditTurnIdx] = useState<number | null>(null);
    const [slots, setSlots] = useState<ReservedSlot[]>([]);
    const [meta, setMeta] = useState<{ year?: number; month?: number; turnTime?: string }>({});
    const [loading, setLoading] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const [refreshKey, setRefreshKey] = useState(0);
    // P0-02 — 개인 예약 링 당기기/미루기/반복 입력값.
    const [pushAmount, setPushAmount] = useState(1);
    const [repeatAmount, setRepeatAmount] = useState(1);

    const viewCount = expanded ? total : Math.min(DEFAULT_VIEW_TURNS, total);
    const slotMap = new Map(slots.map((s) => [s.turnIdx, s]));

    useEffect(() => {
        if (!generalId) return;
        let alive = true;
        setLoading(true);
        setError(null);
        api.reservedCommands()
            .then((res) => {
                if (!alive) return;
                setSlots(res.slots ?? []);
                setMeta({
                    year: res.year ?? undefined,
                    month: res.month ?? undefined,
                    turnTime: res.turnTime ?? undefined,
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

    const formatTurnTime = (tt?: string) => {
        if (!tt) return '';
        // 'yyyy-MM-dd HH:mm:ss' → 'HH:mm'
        const m = tt.match(/(\d{2}):(\d{2}):/);
        return m ? `${m[1]}:${m[2]}` : '';
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
                            <div className="rcp-ym">
                                {meta.year != null && meta.month != null ? `${meta.year}-${String(meta.month).padStart(2, '0')}` : ''}
                            </div>
                            <div className="rcp-time">{formatTurnTime(meta.turnTime)}</div>
                            <div className="rcp-brief">{slot?.brief ?? '휴식'}</div>
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
                <button type="button" onClick={() => setEditTurnIdx(0)}>명령</button>
                {total > DEFAULT_VIEW_TURNS && (
                    <button type="button" onClick={() => setExpanded((v) => !v)}>
                        {expanded ? '접기' : '펼치기'}
                    </button>
                )}
                {/* P0-02 — 개인 예약 링 당기기/미루기/반복 */}
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
                                    onToast((out as any).reason || '적용할 수 없습니다.', 'error');
                                }
                            } catch (e: any) {
                                onToast('요청에 실패했습니다: ' + (e?.message || ''), 'error');
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
                                    onToast((out as any).reason || '적용할 수 없습니다.', 'error');
                                }
                            } catch (e: any) {
                                onToast('요청에 실패했습니다: ' + (e?.message || ''), 'error');
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
