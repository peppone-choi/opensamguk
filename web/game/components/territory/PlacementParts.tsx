'use client';

import Link from 'next/link';
import { useMemo, useState, type ReactNode } from 'react';
import {
    Chip,
    InputAction,
    Portrait,
    ReasonTooltip,
    TargetCandidateList,
    useTargetPicker,
    type InputAvailability,
    type TargetPicker,
} from '@opensamguk/ui';
import type { PlacementCard, Posts } from '@/lib/campaign-reads';
import { placementBody, postKindChoices, targetCandidates, type PlacementBody, type PlacementRow, type PostKindChoice } from '@/lib/territory-view';
import styles from './territory.module.css';

export interface PlacementListProps {
    readonly rows: readonly PlacementRow[];
    /** 카드마다 `placement.assign` 가능 여부 — 화면이 카드의 placeable · blocked 로 정한다(행 없음 = null → 단추 없음). */
    readonly availabilityOf: (row: PlacementRow) => InputAvailability | null;
    readonly onChange: (row: PlacementRow) => void;
    /** 초상 — 배치 조회에는 얼굴이 없어 부 조회(retainerId = cardId)로 푼다. 못 풀면 그리지 않는다. */
    readonly portraitOf?: (cardId: number) => { readonly picture: string | null; readonly imageServer: number } | null;
    /** 조정 발령(P-K01) 주소 — 사람 장수 각주의 고리. */
    readonly courtHref?: string;
}

/**
 * 배치 목록(보드 V31K4Territory 배치 칸, 폭 440) — 카드 행: 초상 · 이름 · 지금 자리 · 부임 중 · 대기 칩 · 「바꾸기」.
 * 불가 카드는 「바꾸기」가 점선 + 서버 사유(InputAction BLOCKED). 비어 있으면 「배치할 NPC 인물이 없습니다」.
 */
export function PlacementList({ rows, availabilityOf, onChange, portraitOf, courtHref }: PlacementListProps) {
    return (
        <div className={styles.placement}>
            {rows.length === 0 ? (
                <p className={styles.empty} role="status">배치할 NPC 인물이 없습니다(사람 장수는 발령).</p>
            ) : (
                <ul className={styles.rows} aria-label="배치 카드">
                    {rows.map((r) => {
                        const face = portraitOf?.(r.cardId) ?? null;
                        return (
                            <li key={r.cardId} className={styles.row} data-card-id={r.cardId}>
                                {face ? <Portrait picture={face.picture} imageServer={face.imageServer} size="card-36" alt="" /> : null}
                                <span className={styles.rowText}>
                                    <span className="os-serif" style={{ fontWeight: 700 }}>{r.name}</span>
                                    <span className={styles.chips}>
                                        <span className={styles.muted}>{r.now ?? '미배치'}</span>
                                        {r.moving ? <Chip tone="info">부임 중</Chip> : null}
                                        {r.pending ? <Chip tone="bronze">{`대기 — 다음 턴부터 ${r.pending}`}</Chip> : null}
                                    </span>
                                </span>
                                <InputAction
                                    inputId="placement.assign"
                                    availability={availabilityOf(r)}
                                    label="바꾸기"
                                    variant="ghost"
                                    onAct={() => onChange(r)}
                                />
                            </li>
                        );
                    })}
                </ul>
            )}
            <p className={styles.note}>
                사람 장수는 배치가 아니라 조정에서 발령합니다.
                {courtHref ? <> <Link href={courtHref} className={styles.link}>조정에서 발령 →</Link></> : null}
            </p>
        </div>
    );
}

function KindRow({ choice, selected, onPick }: { readonly choice: PostKindChoice; readonly selected: boolean; readonly onPick: () => void }) {
    if (!choice.available) {
        const reason = choice.reason ?? '';
        return (
            <ReasonTooltip reason={reason} code={choice.code ?? undefined} title={`${choice.label} — 고를 수 없습니다`} block>
                {(describedBy) => (
                    <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy}
                        className="os-opt os-opt--no" data-post={choice.post}>
                        <span className="os-opt__text"><span className="os-opt__name">{choice.label}</span></span>
                        <span className="os-opt__end"><span className="os-opt__why">{reason}</span></span>
                    </button>
                )}
            </ReasonTooltip>
        );
    }
    return (
        <button type="button" role="option" aria-selected={selected} className={['os-opt', selected ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
            data-post={choice.post} onClick={onPick}>
            <span className="os-opt__text"><span className="os-opt__name">{choice.label}</span></span>
        </button>
    );
}

export interface PlacementSheetProps {
    readonly card: PlacementCard;
    readonly posts: Posts;
    readonly busy: boolean;
    readonly onSubmit: (body: PlacementBody) => void;
    readonly onCancel: () => void;
    /** 도움말 띠(K7, 높이 44) — 인자 패널 맨 위. */
    readonly help?: ReactNode;
    /** 현을 고를 때 목록 위에 그릴 지도(시트 안 480×320, 모바일은 위 절반). 화면이 K2 지도로 채운다. */
    readonly mapSlot?: (picker: TargetPicker) => ReactNode;
}

/**
 * 배치 시트(설계서 P-T01 입력 표) — 자리 종류 → (현령이면 현 · 사자면 세력 · 정찰이면 지금 선 구역) → 「이 자리로」.
 * 불가 자리 · 맡은 사람 있는 현도 사유와 함께 보인다. 입력 몸통은 placementBody 가 만든다.
 */
export function PlacementSheet({ card, posts, busy, onSubmit, onCancel, help, mapSlot }: PlacementSheetProps) {
    const kinds = useMemo(() => postKindChoices(posts), [posts]);
    const [post, setPost] = useState<string | null>(null);
    const chosen = kinds.find((k) => k.post === post) ?? null;
    const option = posts.posts.find((p) => p.post === post) ?? null;
    const candidates = useMemo(() => (option ? targetCandidates(option) : []), [option]);
    const picker = useTargetPicker({ kind: 'place', candidates, onCancel });
    const target = picker.selected[0] ?? null;
    const result = post ? placementBody(card, post, target) : { error: '자리 종류를 고르세요.' };
    const submit = () => { if ('body' in result && !busy) onSubmit(result.body); };

    return (
        <section className={styles.sheet} aria-label={`${card.name} 배치`} data-input-id="placement.assign">
            {help}
            <h3 className={`os-serif ${styles.sheetTitle}`}>{`${card.name} — 어느 자리에`}</h3>
            <div role="listbox" aria-label="자리 종류" className={styles.kinds}>
                {kinds.map((k) => (
                    <KindRow key={k.post} choice={k} selected={k.post === post} onPick={() => { setPost(k.post); picker.clear(); }} />
                ))}
            </div>
            {chosen?.need === 'county' || chosen?.need === 'nation' ? (
                <div className={styles.targets}>
                    {chosen.need === 'county' && mapSlot ? mapSlot(picker) : null}
                    <TargetCandidateList picker={picker} candidates={candidates} label={chosen.need === 'county' ? '맡길 현' : '보낼 세력'} />
                </div>
            ) : null}
            {chosen?.need === 'here' ? <p className={styles.muted}>카드가 지금 선 구역에서 정찰합니다.</p> : null}
            <div className={styles.sheetActions}>
                <button type="button" className="os-button os-button--ghost" onClick={onCancel}>그만두기</button>
                {'body' in result ? (
                    <button type="button" className="os-button os-button--primary" aria-busy={busy || undefined} onClick={submit}>이 자리로</button>
                ) : (
                    <ReasonTooltip reason={result.error} className="os-ia">
                        {(describedBy) => (
                            <button type="button" className="os-button os-button--ghost os-button--disabled" aria-disabled="true"
                                aria-haspopup="dialog" aria-describedby={describedBy}>이 자리로</button>
                        )}
                    </ReasonTooltip>
                )}
            </div>
        </section>
    );
}
