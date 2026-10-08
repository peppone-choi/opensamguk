'use client';

import { useState, type ReactNode } from 'react';
import { Chip, ReasonTooltip, type InputAvailability } from '@opensamguk/ui';
import { HelpedReasonTooltip } from '@/components/campaign/HelpedReasonTooltip';
import type { CountyWorks, Works } from '@/lib/campaign-reads';
import { FORTIFICATION, workBody, workChoices, workRows, type WorkChoice, type WorkRow } from '@/lib/territory-view';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import styles from './territory.module.css';

/** 성방 허물기 규칙 문구(설계서 P-T01 입력 표 · §2 모순 4). */
export const REDUCE_RULE = '완공된 성방을 없애고 방비 · 성벽을 각 500 낮춥니다(0 아래로는 안 내려감).';

export interface WorksPanelProps {
    readonly works: Works;
    /** 현마다 `work.start` 가능 여부(「새 공사」). */
    readonly startAvailabilityOf: (row: WorkRow) => InputAvailability | null;
    /** `work.reduce` 가능 여부 — 원장 PLANNED 면 NOT_DELIVERED(점선 「준비 중」). */
    readonly reduceAvailability: InputAvailability | null;
    readonly reduceAvailabilityOf?: (row: WorkRow) => InputAvailability | null;
    readonly onNewWork: (row: WorkRow) => void;
    readonly onReduce: (row: WorkRow) => void;
}

/**
 * 공사 칸(보드 V31K4Territory 오른쪽, 폭 400) — 현별: 진행 막대 · 남은 순 · 멈춘 사유 · 다음 순 경계부터 · 남은 비용 ·
 * 완공 칩(+ 성방 허물기) · 현 창고 · 「새 공사」. 서버가 모르는 멈춤 코드는 「멈춤(사유 준비 중)」.
 */
export function WorksPanel({ works, startAvailabilityOf, reduceAvailability, reduceAvailabilityOf, onNewWork, onReduce }: WorksPanelProps) {
    const rows = workRows(works);
    return (
        <div className={styles.works}>
            {works.provisional ? <div className={styles.policyHead}><Chip tone="info">잠정</Chip></div> : null}
            {rows.length === 0 ? (
                <p className={styles.empty} role="status">공사를 걸 현이 없습니다 — 현령 자리에 있거나 군주여야 합니다.</p>
            ) : (
                <ul className={styles.rows} aria-label="공사">
                    {rows.map((r) => (
                        <li key={r.countyId} className={styles.workRow} data-county-id={r.countyId}>
                            <span>
                                <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                {r.commanderyName ? <span className={styles.muted}>{` ${r.commanderyName}`}</span> : null}
                            </span>
                            {r.active ? (
                                <div className={styles.progress}>
                                    <div className={styles.chips}>
                                        <span>{r.active.label}</span>
                                        <span className="os-mono">{`${r.active.percent}%`}</span>
                                        <span className={styles.muted}>{`남은 ${r.active.remainingPhases}순`}</span>
                                        {r.active.nextBoundary ? <Chip tone="info">다음 순 경계부터</Chip> : null}
                                    </div>
                                    <div className={styles.bar} role="meter" aria-label={`${r.name} ${r.active.label} 진척`}
                                        aria-valuemin={0} aria-valuemax={100} aria-valuenow={r.active.percent} data-stopped={r.active.stop ? true : undefined}>
                                        <i style={{ width: `${r.active.percent}%` }} />
                                    </div>
                                    {r.active.stop ? <span className={styles.warn}>{r.active.stop}</span> : null}
                                    {r.active.remainingCost.length > 0 ? <span className={styles.muted}>{`남은 비용 ${r.active.remainingCost.join(' · ')}`}</span> : null}
                                </div>
                            ) : <span className={styles.muted}>진행 중인 공사 없음</span>}
                            {r.completed.length > 0 ? (
                                <span className={styles.chips}>{r.completed.map((c) => <Chip key={c} tone="moss">{c}</Chip>)}</span>
                            ) : null}
                            {r.hasFortification && reduceAvailability ? (
                                <div className={styles.reduce}>
                                    <HelpedInputAction inputId="work.reduce" availability={reduceAvailabilityOf?.(r) ?? reduceAvailability} label="성방 허물기" variant="ghost" onAct={() => onReduce(r)} />
                                    <span className={styles.muted}>{REDUCE_RULE}</span>
                                </div>
                            ) : null}
                            {r.reduction ? <p role="status">{r.reduction.status === 'PENDING'
                                ? '성방 감축을 접수했습니다 — 다음 순 경계부터 적용합니다.'
                                : r.reduction.status === 'APPLIED' ? '성방 감축을 완료했습니다.'
                                    : `성방을 감축하지 못했습니다 — ${r.reduction.reason?.reason || '사유를 확인해 주세요.'}`}</p> : null}
                            {r.warehouse.length > 0 ? <span className={styles.muted}>{`현 창고 ${r.warehouse.join(' · ')}`}</span> : null}
                            <HelpedInputAction inputId="work.start" availability={startAvailabilityOf(r)} label="새 공사" onAct={() => onNewWork(r)} />
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

/** 도로 · 보루처럼 추가 인자(접경 · 칸)가 필요한 공사 — 화면이 지도 고르기로 채운다. */
export interface WorkExtra {
    /** 시트 안에 그릴 고르기(지도 480×320 + 목록). */
    readonly node: ReactNode;
    /** 고른 값(`{edgeId}` · `{edgeId, fortRow, fortCol}` …). 아직 안 골랐으면 null. */
    readonly body: Readonly<Record<string, unknown>> | null;
    /** body 가 null 일 때 「이 공사로」의 사유. */
    readonly missing: string;
}

function WorkOption({ choice, selected, onPick }: { readonly choice: WorkChoice; readonly selected: boolean; readonly onPick: () => void }) {
    const text = (
        <span className="os-opt__text">
            <span className="os-opt__name">{choice.label}</span>
            <span className="os-opt__sub">{[...choice.cost, `예상 ${choice.phases}순`].join(' · ')}</span>
        </span>
    );
    if (!choice.available) {
        const reason = choice.reason ?? '';
        return (
            <HelpedReasonTooltip inputId="work.start" reason={reason} code={choice.code ?? undefined} title={`${choice.label} — 지금 시작할 수 없습니다`} block>
                {(describedBy) => (
                    <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy}
                        className="os-opt os-opt--no" data-work={choice.work}>
                        {text}<span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
                    </button>
                )}
            </HelpedReasonTooltip>
        );
    }
    return (
        <button type="button" role="option" aria-selected={selected} className={['os-opt', selected ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
            data-work={choice.work} onClick={onPick}>
            {text}
        </button>
    );
}

export interface WorkReductionSheetProps {
    readonly county: CountyWorks;
    readonly busy: boolean;
    readonly onSubmit: (body: Readonly<Record<string, unknown>>) => void;
    readonly onCancel: () => void;
}

export function WorkReductionSheet({ county, busy, onSubmit, onCancel }: WorkReductionSheetProps) {
    const available = county.reducible === true;
    return (
        <section className={styles.sheet} aria-label={`${county.name} 성방 감축`}>
            <h3 className={`os-serif ${styles.sheetTitle}`}>{`${county.name} — 성방 허물기`}</h3>
            <p>{REDUCE_RULE}</p>
            <p>다음 순 경계부터 적용합니다. 금 · 쌀 · 철 · 목재 · 군마 비용은 없습니다.</p>
            {!available ? <p role="status">{county.reduceBlocked?.reason || '감축 가능 여부를 확인해 주세요.'}</p> : null}
            <div className={styles.sheetActions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                <button type="button" className="os-button" data-input-id="work.reduce"
                    data-input-status={available && !busy ? 'AVAILABLE' : 'BLOCKED'} aria-disabled={!available || busy}
                    onClick={() => { if (available && !busy) onSubmit({ countyId: county.countyId, work: FORTIFICATION }); }}>
                    {busy ? '보내는 중' : '이 성방 허물기'}
                </button>
            </div>
        </section>
    );
}

export interface WorkSheetProps {
    readonly county: CountyWorks;
    readonly busy: boolean;
    readonly onSubmit: (body: Readonly<Record<string, unknown>>) => void;
    readonly onCancel: () => void;
    readonly help?: ReactNode;
    /** 공사마다 추가 인자 — 필요 없으면 null. */
    readonly extraFor?: (work: string) => WorkExtra | null;
}

/** 새 공사 시트 — 공사 카드(비용 · 예상 순 · 불가 사유) → (도로 · 보루면 지도 고르기) → 「이 공사로」. */
export function WorkSheet({ county, busy, onSubmit, onCancel, help, extraFor }: WorkSheetProps) {
    const choices = workChoices(county);
    const [work, setWork] = useState<string | null>(null);
    const extra = work ? extraFor?.(work) ?? null : null;
    const missing = !work ? '공사를 고르세요.' : extra && !extra.body ? extra.missing : null;
    return (
        <section className={styles.sheet} aria-label={`${county.name} 새 공사`} data-input-id="work.start">
            {help}
            <h3 className={`os-serif ${styles.sheetTitle}`}>{`${county.name} — 새 공사`}</h3>
            <div role="listbox" aria-label="공사" className={styles.kinds}>
                {choices.map((c) => <WorkOption key={c.work} choice={c} selected={c.work === work} onPick={() => setWork(c.work)} />)}
            </div>
            {extra ? <div className={styles.targets}>{extra.node}</div> : null}
            <div className={styles.sheetActions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                {missing ? (
                    <ReasonTooltip reason={missing} className="os-ia">
                        {(describedBy) => (
                            <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true"
                                aria-haspopup="dialog" aria-describedby={describedBy}>이 공사로</button>
                        )}
                    </ReasonTooltip>
                ) : (
                    <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                        onClick={() => { if (work && !busy) onSubmit(workBody(county.countyId, work, extra?.body)); }}>
                        이 공사로
                    </button>
                )}
            </div>
        </section>
    );
}
