'use client';

// PartialReservedCommand — the reserved-turn ring panel (spec §5.3, legacy PartialReservedCommand.vue).
// The legacy 5-col table is [Turn#] [Y/M] [Time] [Command brief] [edit ✎] over `maxTurn` slots, fed by
// SammoAPI.Command.GetReservedCommand().
//
// BACKEND GAP (flagged, NOT fabricated): game-api exposes NO reserved-turn READ endpoint — front-info
// carries `recentRecord` (a log feed) but no per-slot reserved command list, and there is no
// GetReservedCommand equivalent. So we render the ring SCAFFOLD with '휴식' defaults and a per-slot
// edit (✎) button that opens CommandModal targeting that turnIdx (the reserve WRITE path exists:
// POST /api/command/{code}?turnIdx). When the read endpoint lands, replace `slots` with its data.

import { useState } from 'react';
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

    const viewCount = expanded ? total : Math.min(DEFAULT_VIEW_TURNS, total);
    const slots = Array.from({ length: viewCount }, (_, i) => i);

    return (
        <section className="reserved-command-panel" id="reservedCommandPanel" aria-label="명령 목록">
            <div className="rcp-title">명령 목록</div>
            <div className="rcp-flag">예약 명령 조회 API가 아직 없어 기본값(휴식)으로 표시됩니다.</div>

            <div className="rcp-table">
                {slots.map((turnIdx) => (
                    <div key={turnIdx} className="rcp-row">
                        <div className="rcp-idx">{turnIdx + 1}</div>
                        {/* Y/M + time unknown without the read endpoint → left blank (not fabricated). */}
                        <div className="rcp-ym" />
                        <div className="rcp-time" />
                        <div className="rcp-brief">휴식</div>
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
                ))}
            </div>

            <div className="rcp-actions">
                <button type="button" onClick={() => setEditTurnIdx(0)}>명령</button>
                {total > DEFAULT_VIEW_TURNS && (
                    <button type="button" onClick={() => setExpanded((v) => !v)}>
                        {expanded ? '접기' : '펼치기'}
                    </button>
                )}
            </div>

            {editTurnIdx != null && (
                <CommandModal
                    onClose={() => setEditTurnIdx(null)}
                    onToast={onToast}
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={editTurnIdx}
                    onReserved={onReserved}
                />
            )}
        </section>
    );
}
