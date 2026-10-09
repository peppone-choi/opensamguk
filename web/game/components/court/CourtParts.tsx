'use client';

import Link from 'next/link';
import { useState, type ReactNode } from 'react';
import {
    Chip,
    PeoplePicker,
    Portrait,
    ReasonTooltip,
    TargetCandidateList,
    useTargetPicker,
    type InputAvailability,
    type PersonOption,
    type TargetCandidate,
    type TargetPicker,
} from '@opensamguk/ui';
import { HelpedReasonTooltip } from '@/components/campaign/HelpedReasonTooltip';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import type { RewardPanelView } from '@/lib/court-reward-view';
import type { CourtChoice, IssuedDispatchRow } from '@/lib/court-view';
import styles from './court.module.css';

/** 막힌 확인 단추 — 누르면 사유 시트(네이티브 disabled 금지). */
function BlockedConfirm({ label, reason }: { readonly label: string; readonly reason: string }) {
    return (
        <ReasonTooltip reason={reason} className="os-ia">
            {(describedBy) => (
                <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true"
                    aria-haspopup="dialog" aria-describedby={describedBy}>{label}</button>
            )}
        </ReasonTooltip>
    );
}

// ── 발령 ─────────────────────────────────────────────────────────────

export interface IssuedDispatchesProps {
    readonly rows: readonly IssuedDispatchRow[];
    /** 접수만 되고 처리 전인 발령 문구(DISPATCH_QUEUED_TEXT). */
    readonly queued: string | null;
    readonly availability: InputAvailability | null;
    readonly onNew: () => void;
    /** 내 부에 사람 장수가 없을 때 — 「영지」 배치로 가는 고리. */
    readonly territoryHref?: string;
    readonly noPeople?: boolean;
    /** 내린 발령 읽기가 정상이 아닐 때(불러오는 중 · 실패 · 서버 상태) 목록 · 「없습니다」 대신 그릴 것. */
    readonly state?: ReactNode;
}

/** 발령 칸 — 내린 발령(대상 · 현 · 상태 · 응답 기한) + 「새 발령」(court.dispatch, 주공 아니면 점선 + 사유). */
export function IssuedDispatches({ rows, queued, availability, onNew, territoryHref, noPeople = false, state }: IssuedDispatchesProps) {
    return (
        <div className={styles.col}>
            {queued ? <Chip tone="info">{queued}</Chip> : null}
            {state ?? (rows.length === 0 ? <p className={styles.muted}>내린 발령이 없습니다.</p> : (
                <ul className={styles.rows} aria-label="내린 발령">
                    {rows.map((r) => (
                        <li key={r.dispatchId} className={styles.row}>
                            <span className={styles.rowText}>
                                <span className="os-serif" style={{ fontWeight: 700 }}>{`${r.target} → ${r.county}`}</span>
                                {r.due ? <span className={styles.muted}>{`응답 기한 ${r.due}`}</span> : null}
                                {r.blocked ? <span className={styles.errLine}>{r.blocked}</span> : null}
                            </span>
                            <Chip tone={r.pending ? 'bronze' : 'neutral'}>{r.status}</Chip>
                        </li>
                    ))}
                </ul>
            ))}
            {noPeople ? (
                <p className={styles.muted}>
                    발령할 사람 장수가 없습니다. NPC 인물은 배치로 옮깁니다.
                    {territoryHref ? <> <Link href={territoryHref} className={styles.link}>영지 →</Link></> : null}
                </p>
            ) : null}
            <HelpedInputAction inputId="court.dispatch" availability={availability} label="새 발령" onAct={onNew} block />
        </div>
    );
}

export interface DispatchSheetProps {
    readonly people: readonly PersonOption[] | null;
    readonly target: number | null;
    readonly onTargetChange: (generalId: number | null) => void;
    /** 고른 사람 기준의 현 후보(dispatch-options?targetGeneralId=). 받는 중이면 null. */
    readonly counties: readonly TargetCandidate[] | null;
    /** Failed or denied candidate reads replace the list and its empty message. */
    readonly countiesState?: ReactNode;
    readonly busy: boolean;
    readonly onSubmit: (args: { readonly targetGeneralId: number; readonly countyId: number }) => void;
    readonly onCancel: () => void;
    readonly help?: ReactNode;
    readonly mapSlot?: (picker: TargetPicker) => ReactNode;
}

/** 새 발령 시트 — 사람 고르기(내 부 사람 장수) → 현(지도 대상 고르기 · 목록, 불가는 사유) → 「이 현으로 발령」. */
export function DispatchSheet({ people, target, onTargetChange, counties, countiesState, busy, onSubmit, onCancel, help, mapSlot }: DispatchSheetProps) {
    const picker = useTargetPicker({ kind: 'place', candidates: counties ?? [], onCancel });
    const county = countiesState ? null : counties?.find((c) => c.targetId === picker.selected[0] && c.available)?.targetId ?? null;
    const missing = target == null ? '발령할 사람을 고르세요.' : county == null ? '발령할 현을 고르세요.' : null;
    return (
        <section className={styles.sheet} aria-label="새 발령" data-input-id="court.dispatch">
            {help}
            <PeoplePicker
                load={people ? { state: 'ready', people } : { state: 'loading' }}
                selected={target}
                onChange={(id) => { picker.clear(); onTargetChange(id); }}
                groups={['mine']}
                label="발령할 사람"
            />
            {target != null ? (
                countiesState ?? (counties ? (
                    <div className={styles.targets}>
                        {mapSlot ? mapSlot(picker) : null}
                        <TargetCandidateList picker={picker} candidates={counties} label="발령할 현" />
                    </div>
                ) : <p className={styles.muted} role="status">현 후보를 불러오는 중…</p>)
            ) : null}
            <div className={styles.actions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                {missing ? <BlockedConfirm label="이 현으로 발령" reason={missing} /> : (
                    <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                        onClick={() => { if (!busy && target != null && county != null) onSubmit({ targetGeneralId: target, countyId: Number(county) }); }}>
                        이 현으로 발령
                    </button>
                )}
            </div>
        </section>
    );
}

// ── 포상 ─────────────────────────────────────────────────────────────

export interface RewardPanelProps {
    /** 서버 상사 선택지의 보기(lib/court-reward-view). 읽기가 READY 가 아니면 null. */
    readonly view: RewardPanelView | null;
    readonly selected: number | null;
    readonly amount: string;
    readonly reward: InputAvailability | null;
    readonly confiscate: InputAvailability | null;
    readonly busy: boolean;
    /** 상사 칸의 알림(접수 · 거절 · 보내기 실패). */
    readonly notice?: { readonly tone: 'ok' | 'error'; readonly text: string } | null;
    /** 받은 선택지가 있는데 다시 읽기가 실패했다. */
    readonly refreshFailed?: boolean;
    readonly onSelect: (retainerId: number) => void;
    readonly onAmountChange: (raw: string) => void;
    readonly onSubmit: () => void;
    readonly onRetry: () => void;
    readonly onConfiscate: () => void;
    /** 받은 포상 기록(기록 「조정 공문」 거르기, K5). */
    readonly recordsHref?: string;
    /** 상사 선택지 읽기가 정상이 아닐 때(불러오는 중 · 실패 · 서버 상태) 대상 목록 대신 그릴 것. */
    readonly state?: ReactNode;
}

/**
 * 포상 칸 — 상사(court.reward: 직속 인물 카드 · 금액), 몰수(court.confiscate, 원장 PLANNED), 봉록(읽기).
 * 규칙 · 상한 · 카드 · 쓸 수 있는 창고 금 · 미리 보기 · 막는 까닭은 모두 서버 상사 선택지에서 온 보기 그대로 그린다(셈 없음).
 * 미리 보기는 저장된 스냅샷 추정치이며 접수 · 지급 확정이 아니다.
 */
export function RewardPanel({ view, selected, amount, reward, confiscate, busy, notice, refreshFailed = false, onSelect, onAmountChange,
    onSubmit, onRetry, onConfiscate, recordsHref, state }: RewardPanelProps) {
    const ready = reward?.status === 'AVAILABLE';
    return (
        <div className={styles.col}>
            <section className={styles.block} aria-label="상사" data-input-id="court.reward">
                <h4 className={styles.sub}>상사 — 직속 인물에게 창고 금을 내립니다</h4>
                {notice ? <p className={notice.tone === 'ok' ? styles.okLine : styles.errLine} role="status">{notice.text}</p> : null}
                {view ? <p className={styles.muted}>{view.rule}</p> : null}
                {ready && state ? state : ready && view && view.cards.length === 0 ? <p className={styles.muted}>상사할 직속 인물 카드가 없습니다.</p> : ready && view ? (
                    <>
                        <p className={styles.muted}>{view.snapshot}</p>
                        {view.queue ? <p className={styles.muted}>{view.queue}</p> : null}
                        {refreshFailed ? (
                            <div className={styles.actions}>
                                <span className={styles.errLine}>상사 선택지를 다시 불러오지 못했습니다.</span>
                                <button type="button" className="os-button os-button--ghost" onClick={onRetry}>다시 시도</button>
                            </div>
                        ) : null}
                        <div role="listbox" aria-label="상사할 인물" className={styles.list}>
                            {view.cards.map((t) => {
                                const sel = t.retainerId === selected;
                                return (
                                    <button key={t.retainerId} type="button" role="option" aria-selected={sel}
                                        className={['os-opt', sel ? 'os-opt--sel' : ''].filter(Boolean).join(' ')} onClick={() => onSelect(t.retainerId)}>
                                        <Portrait picture={t.picture} imageServer={t.imageServer} size="card-24" alt="" />
                                        <span className="os-opt__text"><span className="os-opt__name">{t.name}</span></span>
                                        <span className="os-opt__end"><Chip>{`충성 ${t.loyalty}`}</Chip></span>
                                    </button>
                                );
                            })}
                        </div>
                        {view.usable ? (
                            <span className={styles.chips} data-reward-usable={view.usable.state}>
                                <span className={styles.muted}>쓸 수 있는 창고 금</span>
                                <Chip tone={view.usable.state === 'known' ? 'neutral' : 'bronze'}>{view.usable.amount}</Chip>
                                <span className={styles.muted}>{view.usable.note}</span>
                            </span>
                        ) : null}
                        <label className={styles.field}>
                            <span className={styles.muted}>금액</span>
                            <input className="os-input" inputMode="numeric" value={amount} onChange={(e) => onAmountChange(e.target.value)} aria-label="상사 금액" />
                        </label>
                        {view.effect || view.stock ? (
                            <div role="status" aria-label="상사 미리 보기">
                                {view.effect ? <p className={view.effect.warn ? styles.warnLine : styles.muted}>{view.effect.text}</p> : null}
                                {view.stock ? <p className={view.stock.warn ? styles.warnLine : styles.muted}>{view.stock.text}</p> : null}
                                {view.debits.length > 0 ? (
                                    <ul className={styles.rows} aria-label="창고별 차감 추정">
                                        {view.debits.map((d) => <li key={d.key} className={styles.muted}>{d.text}</li>)}
                                    </ul>
                                ) : null}
                                {view.unchecked ? <p className={styles.muted}>{view.unchecked}</p> : null}
                            </div>
                        ) : view.checking ? (
                            <p className={styles.muted} role="status">금액을 확인하는 중…</p>
                        ) : null}
                        <div className={styles.actions}>
                            {view.previewState === 'error' ? <button type="button" className="os-button os-button--ghost" onClick={onRetry}>다시 시도</button> : null}
                            {view.blocked ? <BlockedConfirm label="상사 — 접수" reason={view.blocked} /> : (
                                <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                                    onClick={() => { if (!busy) onSubmit(); }}>
                                    상사 — 접수
                                </button>
                            )}
                        </div>
                    </>
                ) : ready ? null : (
                    <HelpedInputAction inputId="court.reward" availability={reward} label="상사" onAct={() => {}} block />
                )}
            </section>
            <section className={styles.block} aria-label="몰수">
                <HelpedInputAction inputId="court.confiscate" availability={confiscate} label="몰수" variant="ghost" onAct={onConfiscate} block />
            </section>
            <section className={styles.block} aria-label="봉록">
                <h4 className={styles.sub}>봉록</h4>
                <p className={styles.muted}>봉록은 달마다 저절로 나갑니다. 누가 얼마를 받는지는 서버가 곧 줍니다.</p>
                <Chip tone="info">준비 중</Chip>
            </section>
            {recordsHref ? <Link href={recordsHref} className={styles.link}>받은 포상 기록 →</Link> : null}
        </div>
    );
}

// ── 조정 결정(부대 탈퇴 지시 · 현 포기 · 천도) ──────────────────────────

export interface CourtDecisionCardProps {
    readonly inputId: 'court.releaseCorps' | 'court.abandonCounty' | 'court.moveCapital';
    readonly title: string;
    readonly desc: string;
    readonly availability: InputAvailability | null;
    readonly onOpen: () => void;
    /** 천도 칸의 「지금 수도」 — 못 불러오면 「불러오지 못했습니다」. */
    readonly extra?: ReactNode;
}

export function CourtDecisionCard({ inputId, title, desc, availability, onOpen, extra }: CourtDecisionCardProps) {
    return (
        <section className={styles.block} aria-label={title}>
            <h4 className={styles.sub}>{title}</h4>
            <p className={styles.muted}>{desc}</p>
            {extra}
            <HelpedInputAction inputId={inputId} availability={availability} label={`${title} — 고르기`} onAct={onOpen} block />
        </section>
    );
}

export interface CourtChoiceSheetProps {
    readonly inputId: string;
    readonly title: string;
    readonly choices: readonly CourtChoice[];
    readonly busy: boolean;
    readonly onSubmit: (args: Readonly<Record<string, string | number>>) => void;
    readonly onCancel: () => void;
    readonly help?: ReactNode;
    /** 현 포기 · 천도는 지도 대상 고르기 — 화면이 채운다. 선택은 이 시트의 목록과 같은 key. */
    readonly mapSlot?: ReactNode;
}

/** 조정 명령 시트 — 선택지(불가는 사유) → 「이대로 접수」. 라벨의 id 꼬리는 뗀다(stripIdSuffix). */
export function CourtChoiceSheet({ inputId, title, choices, busy, onSubmit, onCancel, help, mapSlot }: CourtChoiceSheetProps) {
    const [key, setKey] = useState<string | null>(null);
    const chosen = choices.find((c) => c.key === key && c.available) ?? null;
    return (
        <section className={styles.sheet} aria-label={title} data-input-id={inputId}>
            {help}
            {mapSlot}
            <div role="listbox" aria-label={`${title} 대상`} className={styles.list}>
                {choices.length === 0 ? <p className={styles.muted}>고를 대상이 없습니다.</p> : null}
                {choices.map((c) => c.available ? (
                    <button key={c.key} type="button" role="option" aria-selected={c.key === key}
                        className={['os-opt', c.key === key ? 'os-opt--sel' : ''].filter(Boolean).join(' ')} onClick={() => setKey(c.key)}>
                        <span className="os-opt__text"><span className="os-opt__name">{c.label}</span></span>
                    </button>
                ) : (
                    <HelpedReasonTooltip inputId={inputId} key={c.key} reason={c.reason ?? ''} code={c.code ?? undefined} title={`${c.label} — 고를 수 없습니다`} block>
                        {(describedBy) => (
                            <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy} className="os-opt os-opt--no">
                                <span className="os-opt__text"><span className="os-opt__name">{c.label}</span></span>
                                <span className="os-opt__end"><span className="os-opt__why">{c.reason}</span></span>
                            </button>
                        )}
                    </HelpedReasonTooltip>
                ))}
            </div>
            <div className={styles.actions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                {chosen ? (
                    <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                        onClick={() => { if (!busy) onSubmit(chosen.args); }}>이대로 접수</button>
                ) : <BlockedConfirm label="이대로 접수" reason="대상을 고르세요." />}
            </div>
        </section>
    );
}

/** 시트 머리 — 제목과 닫기(44). 자기 머리 · 취소가 없는 시트(받은 요청 · 포상)에 붙인다(바깥 누르기만으로 닫히면 모바일에서 길이 안 보인다). */
export function SheetHead({ title, onClose }: { readonly title: string; readonly onClose: () => void }) {
    return (
        <div className={styles.sheetHead}>
            <h3 className={styles.sheetTitle}>{title}</h3>
            <button type="button" className="os-button os-button--sm" onClick={onClose}>닫기</button>
        </div>
    );
}

/** 맨 아래 고리 카드 — 관직(K8) · 외교(K6). */
export function CourtLinks({ officeHref, diplomacyHref }: { readonly officeHref?: string; readonly diplomacyHref?: string }) {
    return (
        <nav className={styles.links} aria-label="조정 다른 화면">
            {officeHref ? <Link href={officeHref} className={styles.linkCard}>관직 — 조정 · 지방 관직 · 작위 →</Link> : null}
            {diplomacyHref ? <Link href={diplomacyHref} className={styles.linkCard}>외교 — 원조 · 불가침 · 종전 · 선전포고 →</Link> : null}
        </nav>
    );
}

// ── 모바일 조정 결정 목록(V3MReason) ───────────────────────────────────

export interface DecisionItem {
    readonly inputId: string;
    readonly name: string;
    readonly desc: string;
    readonly availability: InputAvailability | null;
    /** 받은 요청 수(발령 응답 · 정치 동의) — 「응답 대기 n」. */
    readonly waiting?: number;
    readonly onOpen: () => void;
}

/** 모바일 첫 화면 — 행 60: 이름 · 설명 · 상태 칩 또는 사유. 원장 행이 없는 입력(availability null)은 그리지 않는다. */
export function CourtDecisionList({ items }: { readonly items: readonly DecisionItem[] }) {
    return (
        <ul className={styles.decisions} aria-label="조정 결정">
            {items.filter((i) => i.availability).map((i) => {
                const a = i.availability!;
                const text = (
                    <span className="os-opt__text">
                        <span className="os-opt__name">{i.name}</span>
                        <span className="os-opt__sub">{i.desc}</span>
                    </span>
                );
                if (a.status === 'AVAILABLE') {
                    return (
                        <li key={i.inputId}>
                            <button type="button" className={`os-opt ${styles.decision}`} data-input-id={i.inputId} onClick={i.onOpen}>
                                {text}
                                <span className="os-opt__end">{i.waiting ? <Chip tone="bronze">{`응답 대기 ${i.waiting}`}</Chip> : null}</span>
                            </button>
                        </li>
                    );
                }
                const reason = a.status === 'NOT_DELIVERED' ? '준비 중' : a.reason?.trim() || '사유를 받지 못했습니다';
                return (
                    <li key={i.inputId}>
                        <HelpedReasonTooltip reason={reason} code={a.code} inputId={i.inputId} title={`${i.name} — 지금 할 수 없습니다`} block>
                            {(describedBy) => (
                                <button type="button" className={`os-opt os-opt--no ${styles.decision}`} aria-disabled="true" aria-haspopup="dialog"
                                    aria-describedby={describedBy} data-input-id={i.inputId} data-input-status={a.status}>
                                    {text}
                                    <span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
                                </button>
                            )}
                        </HelpedReasonTooltip>
                    </li>
                );
            })}
        </ul>
    );
}
