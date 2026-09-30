'use client';

// 인자 칸 — 장소(지도 대상 고르기 목록) · 사람(사람 고르기, NPC 포함) · 부대 · 선택 · 수량.
// 장소 · 사람은 K3 공용 부품을 쓴다. 부대 · 선택은 같은 행 모양(os-opt)으로 여기서 그린다.
import { useEffect, useId, useMemo, type ReactNode } from 'react';
import { PeoplePicker, ReasonTooltip, TargetCandidateList, useTargetPicker } from '@opensamguk/ui';
import type { ArgValue, Draft } from '@/lib/command-flow/flow-state';
import type { ArgField, Candidate } from '@/lib/command-flow/options';
import { NO_REASON, targetKindOf, toPersonOptions, toTargetCandidates } from '@/lib/command-flow/parts-adapter';
import styles from './CommandFlow.module.css';

export interface ArgFieldProps {
    readonly field: ArgField;
    readonly draft: Draft;
    readonly missing: boolean;
    readonly onChange: (key: string, value: ArgValue) => void;
    /** 지도 고르기 다리(K2 지도 층). 없으면 「지도에서 고르기」를 그리지 않는다 — 목록이 기본 조작이다. */
    readonly onMapPick?: (field: ArgField) => void;
    readonly inputId: string;
    /** 수량 칸의 상한 — 고른 자원 · 방법의 서버 최대(options.amountMax). 모르면 null. */
    readonly amountMax?: number | null;
}

export default function ArgFieldView(props: ArgFieldProps) {
    const { field } = props;
    if (targetKindOf(field)) return <PlaceField {...props} />;
    if (field.kind === 'person') return <PersonField {...props} />;
    if (field.kind === 'amount') return <AmountField {...props} />;
    return <OptionField {...props} />;
}

function Frame({ field, missing, extra, children }: { field: ArgField; missing: boolean; extra?: ReactNode; children: ReactNode }) {
    const id = useId();
    return (
        <section className={styles.field} aria-labelledby={id} data-arg-key={field.key}>
            <div className={styles.fieldHead}>
                <h4 id={id} className={styles.fieldLabel}>{field.label}</h4>
                {extra}
            </div>
            {missing ? <p className={styles.dropped} role="alert">「{field.label}」을 고르세요.</p> : null}
            {children}
        </section>
    );
}

function PlaceField({ field, draft, missing, onChange, onMapPick, inputId }: ArgFieldProps) {
    const candidates = useMemo(() => toTargetCandidates(field), [field]);
    const value = typeof draft[field.key] === 'string' ? (draft[field.key] as string) : null;
    const kind = targetKindOf(field)!;
    const picker = useTargetPicker({ kind, candidates, initialSelected: value ? [value] : [], onCancel: noop });
    // 초안이 바깥(이어받기 · 주소 · 비우기)에서 바뀌면 목록 고름을 맞추고, 목록에서 고르면 초안에 적는다.
    useEffect(() => {
        const cur = picker.selected[0] ?? null;
        if (value === cur) return;
        if (value) picker.pick(value); else picker.clear();
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [value]);
    useEffect(() => {
        const cur = picker.selected[0] ?? null;
        if (cur !== value) onChange(field.key, cur);
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [picker.selected]);

    const guide = inputId === 'action.deploy' || inputId === 'action.move' ? 'tutorial.march' : undefined;
    return (
        <Frame
            field={field}
            missing={missing}
            extra={onMapPick ? (
                <button type="button" className="os-button os-button--ghost" data-guide={guide} onClick={() => onMapPick(field)}>
                    지도에서 고르기
                </button>
            ) : null}
        >
            {candidates.length === 0
                ? <p className={styles.candEmpty}>고를 수 있는 곳을 서버가 주지 않았습니다.</p>
                : <TargetCandidateList picker={picker} candidates={candidates} label={field.label} />}
        </Frame>
    );
}

function PersonField({ field, draft, missing, onChange }: ArgFieldProps) {
    const people = useMemo(() => toPersonOptions(field), [field]);
    const raw = draft[field.key];
    const selected = typeof raw === 'string' && raw !== '' ? Number(raw) : null;
    return (
        <Frame field={field} missing={missing}>
            <PeoplePicker
                load={{ state: 'ready', people }}
                selected={selected}
                onChange={(generalId) => onChange(field.key, generalId == null ? null : String(generalId))}
                groups={['all']}
                label={field.label}
            />
        </Frame>
    );
}

/** 부대(여러 개) · 선택 · 자원 — 가능 · 불가를 같이 보이고, 불가 행은 행 전체가 사유를 연다. */
function OptionField({ field, draft, missing, onChange }: ArgFieldProps) {
    const multiple = Boolean(field.multiple);
    const raw = draft[field.key];
    const chosen = multiple
        ? new Set((Array.isArray(raw) ? (raw as readonly number[]) : []).map(String))
        : new Set(typeof raw === 'string' ? [raw] : []);
    const toggle = (c: Candidate) => {
        if (!multiple) { onChange(field.key, c.value); return; }
        const next = new Set(chosen);
        if (next.has(c.value)) next.delete(c.value); else next.add(c.value);
        onChange(field.key, [...next].map(Number));
    };
    return (
        <Frame field={field} missing={missing}>
            {field.candidates.length === 0 ? <p className={styles.candEmpty}>고를 것을 서버가 주지 않았습니다.</p> : (
                <div role="listbox" aria-label={field.label} aria-multiselectable={multiple || undefined} className={styles.cands}>
                    {field.candidates.map((c) => {
                        const text = (
                            <span className={styles.candMain}>
                                <span>{c.label}</span>
                                {c.detail ? <span className={styles.candDetail}>{c.detail}</span> : null}
                            </span>
                        );
                        if (!c.available) {
                            const reason = c.reason ?? NO_REASON;
                            return (
                                <ReasonTooltip key={c.value} reason={reason} title={`${c.label} — 고를 수 없습니다`} block>
                                    {(describedBy: string) => (
                                        <button
                                            type="button" role="option" aria-selected="false" aria-disabled="true"
                                            aria-haspopup="dialog" aria-describedby={describedBy}
                                            className={styles.cand} data-multi={multiple} data-value={c.value}
                                        >
                                            <span className={styles.mark} aria-hidden="true" />
                                            <span className={styles.candMain}>
                                                <span>{c.label}</span>
                                                <span className={styles.candReason}>{reason}</span>
                                            </span>
                                        </button>
                                    )}
                                </ReasonTooltip>
                            );
                        }
                        const on = chosen.has(c.value);
                        return (
                            <button
                                key={c.value} type="button" role="option" aria-selected={on}
                                className={styles.cand} data-multi={multiple} data-value={c.value}
                                onClick={() => toggle(c)}
                            >
                                <span className={styles.mark} aria-hidden="true" />
                                {text}
                            </button>
                        );
                    })}
                </div>
            )}
        </Frame>
    );
}

/** 수량 — 서버가 준 최대까지. 빠른 조절(1 · 절반 · 최대) + 직접 입력. 최대를 모르면(자원을 안 골랐으면) 입력을 막지 않고 안내한다. */
function AmountField({ field, draft, missing, onChange, amountMax }: ArgFieldProps) {
    const id = useId();
    const raw = draft[field.key];
    const value = typeof raw === 'number' ? raw : null;
    const max = amountMax ?? null;
    const set = (n: number) => onChange(field.key, Number.isFinite(n) ? Math.trunc(n) : null);
    return (
        <Frame field={field} missing={missing}>
            <div className={styles.amount}>
                <label htmlFor={id} className="sr-only">{field.label}</label>
                <input
                    id={id} type="number" inputMode="numeric" min={1} max={max ?? undefined}
                    className={`os-inset ${styles.amountInput}`}
                    value={value ?? ''}
                    onChange={(e) => set(e.target.value === '' ? NaN : Number(e.target.value))}
                />
                {max != null && max >= 1 ? (
                    <>
                        <button type="button" className={`os-button os-button--ghost ${styles.amountButton}`} onClick={() => set(1)}>1</button>
                        <button type="button" className={`os-button os-button--ghost ${styles.amountButton}`} onClick={() => set(Math.max(1, Math.floor(max / 2)))}>절반</button>
                        <button type="button" className={`os-button os-button--ghost ${styles.amountButton}`} onClick={() => set(max)}>최대</button>
                        <span className={styles.amountMax}>최대 {max.toLocaleString('ko-KR')}</span>
                    </>
                ) : (
                    <span className={styles.amountMax}>{field.maxFrom === 'resource' ? '자원을 먼저 고르세요' : '방법을 먼저 고르세요'}</span>
                )}
            </div>
        </Frame>
    );
}

function noop() {}
