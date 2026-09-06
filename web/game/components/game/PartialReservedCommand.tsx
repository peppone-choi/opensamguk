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
import type { MyBattlePlansResponse } from '../../types/game';
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
    refreshKey?: number;
    /** Soft-refresh after a reserve. */
    onReserved?: () => void;
    onToast: (msg: string, type: 'success' | 'error' | 'info') => void;
    /** 명령 모달 헤더 히어로(조작 대상 장수). */
    hero?: { picture?: string | null; imageServer?: number | null; name?: string | null; nationColor?: string | null } | null;
    /** Phase 4X-C: 09 「명령 봉인」 화면 경로(서버 접두 포함) — 있으면 `che_출병` 예약 슬롯에 「봉인」 링크를 단다. */
    battlePlanHref?: string;
    /** 자율행동 창이 열려 있으면 「봉인됨」 칩을 점선으로 — AI 가 명령을 바꾼 턴에는 계획이 적용되지 않는다(spec R9·M5). */
    autorunNotice?: boolean;
}

export default function PartialReservedCommand({
    generalId,
    nationId,
    maxTurn,
    refreshKey: externalRefreshKey = 0,
    onReserved,
    onToast,
    hero = null,
    battlePlanHref,
    autorunNotice = false,
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
    // Phase 4X-C: 내 미소비 출병 계획(봉인 여부 칩). 실패는 조용히 빈 목록 — 링크 자체는 계획 없이도 뜬다.
    const [sealedCities, setSealedCities] = useState<Set<number>>(() => new Set());
    useEffect(() => {
        if (!generalId || !battlePlanHref) return;
        let alive = true;
        Promise.resolve()
            .then(() => api.myBattlePlans<MyBattlePlansResponse>())
            .then((r) => { if (alive) setSealedCities(new Set(r.plans.filter((p) => p.sealed).map((p) => p.targetCityId))); })
            .catch(() => { if (alive) setSealedCities(new Set()); });
        return () => { alive = false; };
    }, [generalId, battlePlanHref, refreshKey, externalRefreshKey]);
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
    }, [generalId, refreshKey, externalRefreshKey]);

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

    const reservedCount = slots.filter((s) => s.turnIdx < viewCount).length;
    const nowIdx = 0;
    return (
        <section className="reserved-command-panel os-panel os-panel--static" id="reservedCommandPanel" aria-label="명령 목록">
            <div className="os-section-header rcp-title">
                <span className="os-section-header__bar" aria-hidden="true" />
                <h3 className="os-section-header__title">명령 목록</h3>
                <span className="os-section-header__sub">{viewCount}순 · 예약 {reservedCount}</span>
                <span className="os-section-header__spacer" />
                <span className="os-chip os-chip--bronze">조작 대상: 본인</span>
            </div>
            {loading && <div className="rcp-flag">불러오는 중…</div>}
            {error && <div className="rcp-flag" role="alert" style={{ color: 'var(--rust-2)' }}>{error}</div>}
            <div className="rcp-head" aria-hidden="true">
                <span>#</span>
                <span>순 · 실행</span>
                <span>명령 · 대상</span>
                <span />
            </div>
            <div className="rcp-table">
                {Array.from({ length: viewCount }, (_, i) => i).map((turnIdx) => {
                    const slot = slotMap.get(turnIdx);
                    const state = slot ? (turnIdx === nowIdx ? 'now' : 'planned') : 'rest';
                    return (
                        <div key={turnIdx} className={`rcp-row os-slot${state === 'now' ? ' os-slot--now' : state === 'rest' ? ' os-slot--rest' : ''}`} data-state={state}>
                            <div className="rcp-idx os-slot__n">{turnIdx + 1}</div>
                            <div className="rcp-when">
                                <div className="rcp-ym">{slotYearMonthText(turnIdx)}</div>
                                <div className="rcp-time os-num">{slotTimeFor(turnIdx)}</div>
                            </div>
                            <div className="rcp-brief os-slot__cmd" title={slot?.brief ?? '휴식'}>
                                {slot?.brief ?? '휴식'}
                                {/* Phase 4X-C(S11·R14): che_출병 + 숫자 destCityID 인 예약에만 「봉인」 링크. 봉인된 미소비 계획이 있으면 「봉인됨」 칩. */}
                                {battlePlanHref && slot?.action === 'che_출병' && typeof slot.arg.destCityID === 'number' && (
                                    sealedCities.has(slot.arg.destCityID) ? (
                                        <a
                                            className={`os-chip os-chip--bronze rcp-seal rcp-seal--sealed${autorunNotice ? ' rcp-seal--autorun' : ''}`}
                                            href={`${battlePlanHref}?city=${slot.arg.destCityID}`}
                                            title={autorunNotice ? 'AI 가 명령을 바꾼 턴에는 적용되지 않습니다' : '봉인된 출병 계획 보기'}
                                        >
                                            봉인됨
                                        </a>
                                    ) : (
                                        <a className="os-chip rcp-seal" href={`${battlePlanHref}?city=${slot.arg.destCityID}`} title="출병 계획 봉인(09)">봉인</a>
                                    )
                                )}
                            </div>
                            <div className="rcp-edit">
                                <button
                                    type="button"
                                    className="rcp-edit-btn os-button os-button--ghost os-button--sm"
                                    aria-label={`${turnIdx + 1}턴 명령 편집`}
                                    onClick={() => setEditTurnIdx(turnIdx)}
                                >
                                    편집
                                </button>
                            </div>
                        </div>
                    );
                })}
            </div>
            <div className="rcp-actions">
                <span className="rcp-actions__group">
                    <span className="rcp-actions__label">당기기/미루기</span>
                    <input
                        type="number"
                        className="os-inset rcp-actions__num"
                        aria-label="당기기/미루기 수량"
                        value={pushAmount}
                        onChange={(e) => setPushAmount(Number(e.target.value))}
                    />
                    <button
                        type="button"
                        className="os-button os-button--ghost os-button--sm"
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
                    <span className="rcp-actions__label">반복</span>
                    <input
                        type="number"
                        className="os-inset rcp-actions__num"
                        aria-label="반복 수량"
                        value={repeatAmount}
                        onChange={(e) => setRepeatAmount(Number(e.target.value))}
                    />
                    <button
                        type="button"
                        className="os-button os-button--ghost os-button--sm"
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
                <button type="button" className="os-button os-button--primary rcp-add" onClick={() => setEditTurnIdx(nowIdx)}>
                    명령 추가 · 편집
                </button>
            </div>
            {editTurnIdx != null && (
                <CommandModal
                    onClose={() => setEditTurnIdx(null)}
                    onToast={onToast}
                    generalId={generalId}
                    nationId={nationId}
                    turnIdx={editTurnIdx}
                    onReserved={handleReserved}
                    hero={hero}
                />
            )}
        </section>
    );
}
