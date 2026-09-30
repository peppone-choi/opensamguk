'use client';

import { useState, type ReactNode } from 'react';
import { Chip, InputAction, ReasonTooltip, Seg, type InputAvailability } from '@opensamguk/ui';
import type { CodeLabel, Policies } from '@/lib/campaign-reads';
import {
    commanderyPolicyRows,
    corpsPolicyRows,
    countyPolicyRows,
    policyBody,
    policyOptions,
    type PolicyRow,
    type PolicyScope,
} from '@/lib/territory-view';
import styles from './territory.module.css';

const TAB_LABEL: Readonly<Record<PolicyScope, string>> = { COUNTY: '현', COMMANDERY: '군', CORPS: '군단' };
const EMPTY: Readonly<Record<PolicyScope, string>> = {
    COUNTY: '방침을 걸 현이 없습니다 — 현령 자리에 있거나 군주여야 합니다.',
    COMMANDERY: '방침을 걸 군이 없습니다 — 군주여야 합니다.',
    CORPS: '방침을 걸 군단이 없습니다.',
};

export interface PolicyPanelProps {
    readonly policies: Policies;
    /** 줄마다 `policy.set` 가능 여부 — 화면이 settable · blocked 로 정한다. */
    readonly availabilityOf: (row: PolicyRow) => InputAvailability | null;
    readonly onChange: (row: PolicyRow) => void;
}

/**
 * 방침 칸(보드 V31K4Territory 가운데) — 머리 「빈자리 기본 · …」(+ 잠정) · 탭 현 · 군 · 군단 ·
 * 줄: 대상 · 현령 · 지금 방침(+ 출처 칩 · 건 때) · 대기 칩 · 지난 적용 · 「바꾸기」.
 */
export function PolicyPanel({ policies, availabilityOf, onChange }: PolicyPanelProps) {
    const [tab, setTab] = useState<PolicyScope>('COUNTY');
    const byTab: Record<PolicyScope, PolicyRow[]> = {
        COUNTY: countyPolicyRows(policies),
        COMMANDERY: commanderyPolicyRows(policies),
        CORPS: corpsPolicyRows(policies),
    };
    const rows = byTab[tab];
    const tabs = (Object.keys(TAB_LABEL) as PolicyScope[]).map((value) => ({ value, label: TAB_LABEL[value], count: byTab[value].length }));
    return (
        <div className={styles.policy}>
            <div className={styles.policyHead}>
                {policies.defaultPolicy ? <Chip>{`빈자리 기본 · ${policies.defaultPolicy.label}`}</Chip> : null}
                {policies.provisional ? <Chip tone="info">잠정</Chip> : null}
            </div>
            <Seg label="방침 대상" options={tabs} value={tab} onChange={setTab} />
            {rows.length === 0 ? (
                <p className={styles.empty} role="status">{EMPTY[tab]}</p>
            ) : (
                <ul className={styles.rows} aria-label={`${TAB_LABEL[tab]} 방침`}>
                    {rows.map((r) => (
                        <li key={r.targetId} className={styles.row} data-target-id={r.targetId}>
                            <span className={styles.rowText}>
                                <span>
                                    <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                    {r.sub ? <span className={styles.muted}>{` ${r.sub}`}</span> : null}
                                </span>
                                {r.seat ? <span className={styles.muted}>{`현령 ${r.seat}`}</span> : null}
                                <span className={styles.chips}>
                                    <span>{r.now ?? '방침 없음'}</span>
                                    {r.source ? <Chip>{r.source}</Chip> : null}
                                    {r.since ? <span className={styles.muted}>{`${r.since}부터`}</span> : null}
                                    {r.pending ? <Chip tone="bronze">{`대기 — 다음 턴부터 ${r.pending}`}</Chip> : null}
                                </span>
                                {r.lastApplied ? <span className={styles.muted}>{`지난 적용 ${r.lastApplied}`}</span> : null}
                            </span>
                            <InputAction inputId="policy.set" availability={availabilityOf(r)} label="바꾸기" variant="ghost" onAct={() => onChange(r)} />
                        </li>
                    ))}
                </ul>
            )}
        </div>
    );
}

export interface PolicySheetProps {
    readonly policies: Policies;
    readonly row: PolicyRow;
    readonly busy: boolean;
    readonly onSubmit: (body: Readonly<Record<string, unknown>>) => void;
    readonly onCancel: () => void;
    readonly help?: ReactNode;
}

/**
 * 방침 바꾸기 시트 — 고른다고 바로 보내지 않는다(옛 select onChange 즉시 전송 결함, K6 지적 6).
 * 방침을 고른 뒤 「이 방침으로」. 지금 방침은 「지금」 칩, 건 방침이 있으면 「방침 거두기」.
 */
export function PolicySheet({ policies, row, busy, onSubmit, onCancel, help }: PolicySheetProps) {
    const options: CodeLabel[] = policyOptions(policies, row);
    const [code, setCode] = useState<string | null>(null);
    return (
        <section className={styles.sheet} aria-label={`${row.name} 방침`} data-input-id="policy.set">
            {help}
            <h3 className={`os-serif ${styles.sheetTitle}`}>{`${row.name} — 방침`}</h3>
            <div role="listbox" aria-label="방침" className={styles.kinds}>
                {options.map((o) => {
                    const sel = o.code === code;
                    return (
                        <button key={o.code} type="button" role="option" aria-selected={sel}
                            className={['os-opt', sel ? 'os-opt--sel' : ''].filter(Boolean).join(' ')} onClick={() => setCode(o.code)}>
                            <span className="os-opt__text"><span className="os-opt__name">{o.label}</span></span>
                            {o.label === row.now ? <span className="os-opt__end"><Chip>지금</Chip></span> : null}
                        </button>
                    );
                })}
            </div>
            <div className={styles.sheetActions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                {code ? (
                    <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined}
                        onClick={() => { if (!busy) onSubmit(policyBody(row, code)); }}>
                        이 방침으로
                    </button>
                ) : (
                    <ReasonTooltip reason="방침을 고르세요." className="os-ia">
                        {(describedBy) => (
                            <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true"
                                aria-haspopup="dialog" aria-describedby={describedBy}>이 방침으로</button>
                        )}
                    </ReasonTooltip>
                )}
            </div>
        </section>
    );
}
