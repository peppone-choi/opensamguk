'use client';

import Link from 'next/link';
import { Chip, SectionHeader, type InputAvailability } from '@opensamguk/ui';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { InputHelpStrip } from '@/components/help/HelpStrip';
import type { Siege } from '@/lib/campaign-reads';
import { UNKNOWN_COUNTY, siegeCells, siegeProgressText, siegeStatus, timelineRow, type FortRow, type KvCell } from '@/lib/siege-view';
import styles from './siege.module.css';

export type SiegePick = { readonly kind: 'siege'; readonly countyId: number } | { readonly kind: 'fort'; readonly id: string };

export const countyName = (siege: Pick<Siege, 'countyName'>) => siege.countyName ?? UNKNOWN_COUNTY;

/** 목록(보드 listbox opt) — 포위한 성 · 도로 보루. 줄 전체가 단추(44 이상), 고른 줄은 aria-pressed. */
export function SiegeList({ sieges, forts, picked, onPick }: {
    readonly sieges: readonly Siege[];
    readonly forts: readonly FortRow[];
    readonly picked: SiegePick | null;
    readonly onPick: (pick: SiegePick) => void;
}) {
    return (
        <ul className={styles.list} aria-label="포위">
            {sieges.map((s) => {
                const status = siegeStatus(s.status);
                const on = picked?.kind === 'siege' && picked.countyId === s.countyId;
                return (
                    <li key={`s${s.countyId}`}>
                        <button type="button" className={styles.opt} aria-pressed={on} onClick={() => onPick({ kind: 'siege', countyId: s.countyId })}>
                            <span className={styles.optText}>
                                <span className={`os-serif ${styles.optName}`}>{countyName(s)}</span>
                                <span className={styles.muted}>{`${s.defenderNationName ?? '어느 세력'} 소속 · ${siegeProgressText(s)}`}</span>
                            </span>
                            <Chip tone={status.tone}>{status.label}</Chip>
                        </button>
                    </li>
                );
            })}
            {forts.map((f) => {
                const on = picked?.kind === 'fort' && picked.id === f.id;
                return (
                    <li key={`f${f.id}`}>
                        <button type="button" className={styles.opt} aria-pressed={on} onClick={() => onPick({ kind: 'fort', id: f.id })}>
                            <span className={styles.optText}>
                                <span className={`os-serif ${styles.optName}`}>{f.name}</span>
                                <span className={styles.muted}>{`도로 보루 · ${f.ownerName} · ${f.cells[2].label} ${f.cells[2].value}`}</span>
                            </span>
                            <Chip tone={f.mine ? 'moss' : 'neutral'}>{f.mine ? '우리 보루' : '보루'}</Chip>
                        </button>
                    </li>
                );
            })}
        </ul>
    );
}

function Cells({ cells }: { readonly cells: readonly KvCell[] }) {
    return (
        <dl className={styles.cells}>
            {cells.map((c) => (
                <div key={c.label} className={styles.cell} data-warn={c.warn ? '' : undefined}>
                    <dt className={styles.muted}>{c.label}</dt>
                    <dd className="os-mono">{c.value}</dd>
                </div>
            ))}
        </dl>
    );
}

/** 형편 — 6칸 + 보급 칩. 시야 규칙 전이라 서버는 관여한 포위만 준다(SiegeReader). */
export function SiegeState({ siege }: { readonly siege: Siege }) {
    return (
        <div className={styles.stack}>
            <Cells cells={siegeCells(siege)} />
            <div className={styles.chips}>
                <span className={styles.muted}>보급</span>
                {siege.besiegerFed == null ? <Chip>포위군 급식 — 확인 불가</Chip>
                    : siege.besiegerFed ? <Chip>포위군 급식 받는 중</Chip> : <Chip tone="rust">포위군 급식 부족</Chip>}
                {/* 서버는 공격 · 수비 쪽 모두에 포위를 준다(SiegeReader) — 어느 쪽에 좋은지 색으로 가르지 않는다. */}
                <Chip>{siege.countySupplied ? '성 안 보급 이어짐' : '성 안 보급 끊김'}</Chip>
            </div>
        </div>
    );
}

/** 포위 기록 — 서버 timeline 순서 그대로. 모르는 사건 코드는 「공성 사건」. */
export function SiegeTimeline({ siege }: { readonly siege: Siege }) {
    if (siege.timeline.length === 0) return <p className={styles.empty}>아직 기록이 없습니다.</p>;
    return (
        <ol className={styles.timeline} aria-label="포위 기록">
            {siege.timeline.map((entry, i) => {
                const row = timelineRow(entry);
                return (
                    <li key={i} className={styles.tlRow}>
                        <span className={`os-mono ${styles.tlWhen}`}>{row.when}</span>
                        <span>{row.what}{row.detail ? <span className={styles.muted}>{` · ${row.detail}`}</span> : null}</span>
                    </li>
                );
            })}
        </ol>
    );
}

export function FortState({ fort }: { readonly fort: FortRow }) {
    return (
        <div className={styles.stack}>
            <Cells cells={fort.cells} />
            <p className={styles.note}>{fort.mine ? '우리 세력 보루입니다.' : `${fort.ownerName} 보루입니다. 길목을 막고 있어 에워싸 떨어뜨려야 지나갈 수 있습니다.`}</p>
        </div>
    );
}

/** 명령 — 강공 · 항복 권고를 명령 목록에 넣는다(순은 명령 흐름에서 고른다). */
export function SiegeCommands({ assault, demand, onAct, onHelp }: {
    readonly assault: InputAvailability | null;
    readonly demand: InputAvailability | null;
    readonly onAct: (inputId: 'action.assault' | 'action.demandSurrender') => void;
    readonly onHelp: () => void;
}) {
    return (
        <div className={styles.commands}>
            <InputHelpStrip inputId="action.assault" onOpenHelp={onHelp} />
            <HelpedInputAction inputId="action.assault" availability={assault} label="강공 — 순 고르기" variant="danger" block onAct={() => onAct('action.assault')} />
            <HelpedInputAction inputId="action.demandSurrender" availability={demand} label="항복 권고 — 순 고르기" block onAct={() => onAct('action.demandSurrender')} />
        </div>
    );
}

/** 항복 권고 — 서버 판정(SiegeRules.surrenderDemandAccepted: 성 안 사기 · 민심 문턱, 난수 없음)을 그대로 보인다. */
export function SurrenderNote({ siege }: { readonly siege: Siege }) {
    return (
        <div className={styles.stack}>
            <p className={siege.surrenderDemandAccepted ? styles.okLine : styles.muted} role="status">
                {siege.surrenderDemandAccepted ? '지금 권하면 받아들입니다.' : '지금 권하면 거절합니다.'}
            </p>
            <p className={styles.note}>성 안 사기와 민심이 둘 다 충분히 떨어지면 받아들입니다. 운이 아니라 정해진 문턱입니다.</p>
        </div>
    );
}

/**
 * 함락되면 — 서버 SiegeService 함락 규칙(현 소유가 포위 세력으로 넘어가고 창고는 현에 남는다 · 같은 순 월 세입은 새 주인)만 적는다.
 * 공격 · 수비 어느 쪽이 봐도 맞게 포위 세력 이름으로 쓴다.
 */
export function FallNote({ siege }: { readonly siege: Siege }) {
    const to = siege.besieger.nationName ?? '포위 세력';
    return (
        <ul className={styles.bullets}>
            <li><b>현</b> — {countyName(siege)} 전체가 넘어갑니다(새 주인 {to}).</li>
            <li><b>창고</b> — 현 창고는 그 자리에 남고, 다스림만 넘어갑니다.</li>
            <li><b>세입</b> — 같은 순의 월 세입은 새 주인에게 갑니다.</li>
        </ul>
    );
}

export function StratagemLink({ href }: { readonly href: string }) {
    return <Link href={href} className={styles.link}>계책 덱에서 공성 계책 쓰기 →</Link>;
}

export function Section({ title, sub, children, label }: { readonly title: string; readonly sub?: string; readonly label?: string; readonly children: React.ReactNode }) {
    return (
        <section className="os-panel" aria-label={label ?? title}>
            <SectionHeader title={title} sub={sub} />
            {children}
        </section>
    );
}
