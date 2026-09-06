'use client';
// Phase 4X-C 10 「리플레이」 — 페이즈 스크럽 + 텍스트 로그 + 정산 (spec v4.1 §7). 원천 /api/battles/replays/{id}. 2.5D 렌더 없음.
import { useEffect, useState } from 'react';
import { Chip, EmptyState, Panel, SectionHeader, type ChipTone } from '@opensamguk/ui';
import { api } from '../../lib/api';
import type { BattleReplayDetail } from '../../types/game';

const RESULT_TONE: Record<string, ChipTone> = { conquered: 'bronze', defenders_down: 'moss', retreat: 'rust', repelled: 'neutral' };
const PHASE_TEXT = ['', '상순', '중순', '하순'];

export default function BattleReplayPlayer({ id, battleCenterHref, operationHref }: { readonly id: number; readonly battleCenterHref: string; readonly operationHref?: string }) {
    const [data, setData] = useState<BattleReplayDetail | null>(null);
    const [error, setError] = useState('');
    const [idx, setIdx] = useState(0);
    const [speed, setSpeed] = useState(1);
    const [playing, setPlaying] = useState(false);

    useEffect(() => {
        Promise.resolve().then(() => api.battleReplay<BattleReplayDetail>(id)).then((r) => { setData(r); setError(''); }).catch(() => setError('리플레이를 불러올 수 없습니다.'));
    }, [id]);
    const total = data?.battlePhases.length ?? 0;
    useEffect(() => {
        if (!playing || total === 0) return;
        const t = window.setInterval(() => setIdx((i) => { if (i + 1 >= total) { setPlaying(false); return i; } return i + 1; }), 1500 / speed);
        return () => window.clearInterval(t);
    }, [playing, speed, total]);

    if (error) return <EmptyState title={error} />;
    if (data == null) return <p className="text-muted">리플레이를 불러오는 중…</p>;
    const { summary, battlePhases, settlement, plan, seed } = data;
    const current = battlePhases[idx];
    return (
        <div className="replay" id="battle-replay">
            <div className="replay__head">
                <div className="replay__side">
                    <b>{summary.attackerName}</b>
                    <span className="os-num">{settlement.attackerCrewBefore.toLocaleString()} → {settlement.attackerCrewAfter.toLocaleString()} (-{settlement.attackerDead.toLocaleString()})</span>
                </div>
                <div className="replay__vs">
                    <span>對</span>
                    <Chip tone={RESULT_TONE[summary.result] ?? 'neutral'}>{summary.resultLabel}</Chip>
                    <span className="os-num">{total}페이즈 · 결정론</span>
                </div>
                <div className="replay__side replay__side--right">
                    <b>{summary.defenderCityName}</b>
                    <span className="os-num">적 사상 -{settlement.defenderDead.toLocaleString()}</span>
                </div>
            </div>
            <div className="replay__grid">
                <Panel className="replay__panel" aria-label="페이즈">
                    <SectionHeader title="페이즈" sub={`${summary.year}年 ${summary.month}月 ${PHASE_TEXT[summary.phase] ?? ''} 해결`} />
                    <div className="replay__scrub" role="group" aria-label="페이즈 스크럽">
                        <button type="button" className="os-button os-button--ghost os-button--sm" aria-label="이전 페이즈" onClick={() => setIdx((i) => Math.max(0, i - 1))} disabled={idx === 0}>‹</button>
                        <span className="os-num" aria-live="polite">{total === 0 ? 0 : idx + 1} / {total}</span>
                        <button type="button" className="os-button os-button--ghost os-button--sm" aria-label="다음 페이즈" onClick={() => setIdx((i) => Math.min(total - 1, i + 1))} disabled={idx >= total - 1}>›</button>
                        <button type="button" className="os-button os-button--ghost os-button--sm" onClick={() => setPlaying((p) => !p)} disabled={total === 0}>{playing ? '정지' : '재생'}</button>
                        {[0.5, 1, 2].map((s) => (
                            <button key={s} type="button" className={`os-button os-button--ghost os-button--sm${speed === s ? ' is-active' : ''}`} aria-pressed={speed === s} onClick={() => setSpeed(s)}>{s}×</button>
                        ))}
                    </div>
                    {total === 0 ? (
                        <EmptyState title="페이즈 기록이 없습니다." hint="접촉 전에 끝난 전투입니다." />
                    ) : (
                        <ol className="replay__phases" aria-label="페이즈 로그">
                            {battlePhases.map((p, i) => {
                                const stopHere = plan?.stopAtPhase != null && plan.stopAtPhase === p.i;
                                return (
                                    <li key={p.i} className={`replay__phase${i === idx ? ' is-current' : ''}`} aria-current={i === idx ? 'step' : undefined} onClick={() => setIdx(i)}>
                                        <span className="replay__phase-n os-num">P{p.i}</span>
                                        <span className="replay__phase-text">
                                            {p.contact ? `${p.def}${p.defKind === 'city' ? '(성)' : ''} 과 접촉 · ` : ''}교전 · 아군 -{p.deadA.toLocaleString()} · 적 -{p.deadD.toLocaleString()} · 아군 병력 {p.crewA.toLocaleString()} · 상대 {p.defKind === 'city' ? '성 HP' : '병력'} {p.hpD.toLocaleString()}
                                            {stopHere && <Chip tone="rust">조건 발동 · {plan?.planStopLabel ?? plan?.planStop}</Chip>}
                                        </span>
                                    </li>
                                );
                            })}
                        </ol>
                    )}
                    {current && (
                        <p className="replay__current os-num" aria-label="현재 페이즈">
                            P{current.i} · {current.def} · 아군 {current.crewA.toLocaleString()} · 상대 {current.hpD.toLocaleString()}
                        </p>
                    )}
                </Panel>
                <Panel className="replay__panel" aria-label="캠페인 정산">
                    <SectionHeader title="캠페인 정산" />
                    <dl className="replay__rows os-num">
                        <dt>사상</dt><dd>-{settlement.attackerDead.toLocaleString()} (적 -{settlement.defenderDead.toLocaleString()})</dd>
                        <dt>군량 소모</dt><dd>-{settlement.riceUsed.toLocaleString()}</dd>
                        <dt>점령</dt><dd>{settlement.conquered ? '점령' : '미점령'}</dd>
                        {plan && (<>
                            <dt>태세</dt><dd>{plan.stanceLabel ?? plan.stance ?? '-'}</dd>
                            <dt>조건</dt><dd>{[plan.retreatLossPct != null ? `손실 ${plan.retreatLossPct}%` : null, plan.retreatMoraleBelow != null ? `사기 ${plan.retreatMoraleBelow} 미만` : null].filter(Boolean).join(' · ') || '없음'}</dd>
                            <dt>발동</dt><dd>{plan.planStopLabel ?? '조건 미발동'}</dd>
                        </>)}
                    </dl>
                    <p className="replay__hash">같은 seed·입력이면 같은 결과를 재생합니다 · <code>{seed.replayHash.slice(0, 8)}</code></p>
                    <div className="replay__links">
                        <a className="os-button os-button--ghost os-button--sm" href={battleCenterHref}>감찰부 기록</a>
                        {summary.operationId != null && operationHref && <a className="os-button os-button--ghost os-button--sm" href={operationHref}>작전 #{summary.operationId}</a>}
                    </div>
                </Panel>
            </div>
        </div>
    );
}
