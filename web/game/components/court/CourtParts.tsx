'use client';

import Link from 'next/link';
import { useState, type ReactNode } from 'react';
import {
    Chip,
    InputAction,
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
import { REWARD_RULE, rewardMaxMoney, rewardMoney, rewardPreview, type CourtChoice, type IssuedDispatchRow, type RewardTarget } from '@/lib/court-view';
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
            <InputAction inputId="court.dispatch" availability={availability} label="새 발령" onAct={onNew} block />
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
    readonly targets: readonly RewardTarget[];
    readonly reward: InputAvailability | null;
    readonly confiscate: InputAvailability | null;
    readonly busy: boolean;
    readonly onReward: (args: { readonly retainerId: number; readonly money: number }) => void;
    readonly onConfiscate: () => void;
    /** 받은 포상 기록(기록 「조정 공문」 거르기, K5). */
    readonly recordsHref?: string;
    /** 부 인물 읽기가 정상이 아닐 때(불러오는 중 · 실패 · 서버 상태) 대상 목록 대신 그릴 것. */
    readonly state?: ReactNode;
}

/**
 * 포상 칸 — 상사(court.reward: 내 부 인물 · 금액), 몰수(court.confiscate, 원장 PLANNED), 봉록(읽기).
 * 상사 규칙(금 100당 충성 +1 · 한 번 +10 · 충성 100)은 서버 상수 그대로 보이고, 고른 인물 · 금액으로 오를 충성과 충성 없이 나가는 금을
 * 미리 보인다 — 서버는 적은 금 전부를 낸다. 쓸 수 있는 창고 금은 서버 값(계약판 K4-15) 전까지 「준비 중」.
 */
export function RewardPanel({ targets, reward, confiscate, busy, onReward, onConfiscate, recordsHref, state }: RewardPanelProps) {
    const [who, setWho] = useState<number | null>(null);
    const [raw, setRaw] = useState('');
    const money = rewardMoney(raw);
    const ready = reward?.status === 'AVAILABLE';
    const target = targets.find((t) => t.retainerId === who) ?? null;
    const preview = target && money != null ? rewardPreview(money, target.loyalty) : null;
    const won = (n: number) => n.toLocaleString('ko-KR');
    const max = target ? rewardMaxMoney(target.loyalty) : null;
    const full = target != null && target.loyalty >= REWARD_RULE.loyaltyCap;
    const missing = who == null ? '상사할 인물을 고르세요.'
        : money == null ? '금액을 1 이상의 정수로 적으세요.'
        : money < REWARD_RULE.moneyPerLoyalty ? `금 ${REWARD_RULE.moneyPerLoyalty} 이상이어야 충성이 오릅니다.`
        : max != null && money > max ? (full
            ? `충성은 이미 ${REWARD_RULE.loyaltyCap}입니다 — 금 ${won(REWARD_RULE.moneyPerLoyalty)}으로 상을 내린 기록만 남길 수 있습니다.`
            : `이번에 충성을 올릴 수 있는 금은 최대 ${won(max)}입니다.`)
        : null;
    return (
        <div className={styles.col}>
            <section className={styles.block} aria-label="상사" data-input-id="court.reward">
                <h4 className={styles.sub}>상사 — 직속 인물에게 창고 금을 내립니다</h4>
                <p className={styles.muted}>
                    {`금 ${REWARD_RULE.moneyPerLoyalty}당 충성 +1 · 한 번에 최대 +${REWARD_RULE.maxGain} · 충성은 ${REWARD_RULE.loyaltyCap}까지 — 충성을 올릴 수 있는 만큼까지만 냅니다.`}
                </p>
                <span className={styles.chips} data-waiting="reward-usable">
                    <span className={styles.muted}>쓸 수 있는 창고 금</span>
                    <Chip tone="info">준비 중</Chip>
                </span>
                {ready && state ? state : ready && targets.length === 0 ? <p className={styles.muted}>상사할 직속 인물 카드가 없습니다.</p> : ready ? (
                    <>
                        <div role="listbox" aria-label="상사할 인물" className={styles.list}>
                            {targets.map((t) => {
                                const sel = t.retainerId === who;
                                return (
                                    <button key={t.retainerId} type="button" role="option" aria-selected={sel}
                                        className={['os-opt', sel ? 'os-opt--sel' : ''].filter(Boolean).join(' ')} onClick={() => setWho(t.retainerId)}>
                                        <Portrait picture={t.picture} imageServer={t.imageServer} size="card-24" alt="" />
                                        <span className="os-opt__text"><span className="os-opt__name">{t.name}</span></span>
                                        <span className="os-opt__end"><Chip>{`충성 ${t.loyalty}`}</Chip></span>
                                    </button>
                                );
                            })}
                        </div>
                        <label className={styles.field}>
                            <span className={styles.muted}>금액</span>
                            <input className="os-input" inputMode="numeric" value={raw} onChange={(e) => setRaw(e.target.value)} aria-label="상사 금액" />
                        </label>
                        {preview && !missing ? (
                            <p className={preview.wasted > 0 ? styles.warnLine : styles.muted} role="status" aria-label="상사 미리 보기">
                                {full ? `충성은 이미 ${REWARD_RULE.loyaltyCap}입니다 — 상을 내린 기록 · 결속 사건만 남습니다` : `충성 +${preview.gain}`}
                                {!full && preview.wasted > 0 ? ` — 충성 없이 나가는 금 ${won(preview.wasted)}(100 단위 나머지)` : ''}
                            </p>
                        ) : null}
                        <div className={styles.actions}>
                            {missing ? <BlockedConfirm label="상사 — 접수" reason={missing} /> : (
                                <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                                    onClick={() => { if (!busy && who != null && money != null) onReward({ retainerId: who, money }); }}>
                                    상사 — 접수
                                </button>
                            )}
                        </div>
                    </>
                ) : (
                    <InputAction inputId="court.reward" availability={reward} label="상사" onAct={() => {}} block />
                )}
            </section>
            <section className={styles.block} aria-label="몰수">
                <InputAction inputId="court.confiscate" availability={confiscate} label="몰수" variant="ghost" onAct={onConfiscate} block />
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
            <InputAction inputId={inputId} availability={availability} label={`${title} — 고르기`} onAct={onOpen} block />
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
                    <ReasonTooltip key={c.key} reason={c.reason ?? ''} code={c.code ?? undefined} title={`${c.label} — 고를 수 없습니다`} block>
                        {(describedBy) => (
                            <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy} className="os-opt os-opt--no">
                                <span className="os-opt__text"><span className="os-opt__name">{c.label}</span></span>
                                <span className="os-opt__end"><span className="os-opt__why">{c.reason}</span></span>
                            </button>
                        )}
                    </ReasonTooltip>
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
                        <ReasonTooltip reason={reason} code={a.code} inputId={i.inputId} title={`${i.name} — 지금 할 수 없습니다`} block>
                            {(describedBy) => (
                                <button type="button" className={`os-opt os-opt--no ${styles.decision}`} aria-disabled="true" aria-haspopup="dialog"
                                    aria-describedby={describedBy} data-input-id={i.inputId} data-input-status={a.status}>
                                    {text}
                                    <span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
                                </button>
                            )}
                        </ReasonTooltip>
                    </li>
                );
            })}
        </ul>
    );
}
