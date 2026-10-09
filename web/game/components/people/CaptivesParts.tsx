'use client';

import { Chip, type InputAvailability } from '@opensamguk/ui';
import { HelpedReasonTooltip } from '@/components/campaign/HelpedReasonTooltip';
import { HelpedInputAction } from '@/components/campaign/HelpedInputAction';
import { availabilityOf } from '@/lib/input-availability';
import { plainGlyphs } from '@/lib/plain-glyphs';
import type { CaptivesRead, PeopleOptions } from '@/lib/types';
import styles from './people.module.css';

export interface TalentRow {
    readonly generalId: number;
    readonly name: string;
    readonly available: boolean;
    readonly reason: string | null;
    readonly code: string | null;
}

/** 등용 대상(employ-options.targets) — 불가도 사유와 함께. 서버 사유 속 縣 은 「현」으로(plain-glyphs 한 표). */
export function talentRows(opt: PeopleOptions | null): TalentRow[] {
    return (opt?.targets ?? []).map((t) => ({
        generalId: t.generalId,
        name: t.name,
        available: t.available,
        reason: t.available ? null : plainGlyphs(t.reason?.trim() || '사유를 받지 못했습니다'),
        code: t.available ? null : t.code ?? null,
    }));
}

/** 등용 명령 흐름 쿼리 — 대상 장수를 미리 채운다(`?do=action.employ&target=general:<id>`, K6 흐름 주소). */
export function employQuery(generalId: number): string {
    const q = new URLSearchParams({ do: 'action.employ', target: `general:${generalId}` });
    return `?${q.toString()}`;
}

export function persuadeQuery(generalId: number): string {
    const q = new URLSearchParams({ do: 'action.persuadeCaptive', target: `general:${generalId}` });
    return `?${q.toString()}`;
}

export interface TalentPanelProps {
    readonly employ: PeopleOptions | null;
    readonly search: PeopleOptions | null;
    readonly searchAvailability: InputAvailability | null;
    readonly employAvailability: InputAvailability | null;
    readonly onSearch: () => void;
    /** 고른 인재 — 데스크톱은 목록 아래 「{이름} 등용」, 모바일은 화면이 하단 시트를 연다. */
    readonly picked: number | null;
    readonly onPick: (generalId: number) => void;
    /** 데스크톱: 고른 인재로 명령 흐름을 연다. 모바일은 시트가 단추를 그리므로 null. */
    readonly onEmploy: ((generalId: number) => void) | null;
}

/**
 * 「등용할 수 있는 인재」(보드 V31K4Captives 왼쪽) — 찾지 못한 인물 수 · 인재탐색 · 대상 카드(불가는 사유) · 「{이름} 등용」.
 * 성공 확률은 서버가 주면 숫자(K4-12 `chance`), 안 주면 칸 없음(설계서 P-R05). 비면 「인재탐색으로 찾습니다」.
 */
export function TalentPanel({ employ, search, searchAvailability, employAvailability, onSearch, picked, onPick, onEmploy }: TalentPanelProps) {
    const rows = talentRows(employ);
    const chosen = rows.find((r) => r.generalId === picked && r.available) ?? null;
    const undiscovered = search?.undiscoveredCount;
    return (
        <div className={styles.talent}>
            <div className={styles.talentHead}>
                {undiscovered != null ? <Chip>{`찾지 못한 인물 ${undiscovered}명`}</Chip> : null}
                <HelpedInputAction inputId="action.search" availability={searchAvailability} label="인재탐색 — 명령 목록에 넣기" variant="ghost" onAct={onSearch} />
            </div>
            <p className={styles.muted}>등용은 한 순에 한 사람입니다. 성공하면 내 부 인물 카드가 되고 명망 코스트가 오릅니다.</p>
            {rows.length === 0 ? (
                <p className={styles.empty} role="status">이 현에서 찾은 재야 인물이 없습니다 — 인재탐색으로 찾습니다.</p>
            ) : (
                <div role="listbox" aria-label="인재" className={styles.talentList}>
                    {rows.map((r) => r.available ? (
                        <button key={r.generalId} type="button" role="option" aria-selected={r.generalId === picked}
                            className={['os-opt', styles.talentRow, r.generalId === picked ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
                            onClick={() => onPick(r.generalId)}>
                            <span className="os-opt__text"><span className="os-opt__name">{r.name}</span><span className="os-opt__sub">재야</span></span>
                            <span className="os-opt__end"><Chip tone="moss">등용 가능</Chip></span>
                        </button>
                    ) : (
                        <HelpedReasonTooltip inputId="action.employ" key={r.generalId} reason={r.reason ?? ''} code={r.code ?? undefined} title={`${r.name} — 지금 등용할 수 없습니다`} block>
                            {(describedBy) => (
                                <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy}
                                    className={`os-opt os-opt--no ${styles.talentRow}`}>
                                    <span className="os-opt__text"><span className="os-opt__name">{r.name}</span><span className="os-opt__sub">재야</span></span>
                                    <span className="os-opt__end"><span className="os-opt__why">{r.reason}</span></span>
                                </button>
                            )}
                        </HelpedReasonTooltip>
                    ))}
                </div>
            )}
            {chosen && onEmploy ? (
                <HelpedInputAction inputId="action.employ" availability={employAvailability} label={`${chosen.name} 등용 — 명령 목록에 넣기`}
                    onAct={() => onEmploy(chosen.generalId)} block />
            ) : null}
        </div>
    );
}

export interface CaptivePanelProps {
    readonly captives: CaptivesRead;
    readonly busy: boolean;
    readonly onPersuade: (generalId: number) => void;
    readonly onRelease: (generalId: number) => void;
}

/** Server-provided custody name only; the raw province id is never shown as a location label. */
function heldProvinceLabel(name: string | null | undefined): string {
    const shown = name?.trim();
    return shown ? `구금 위치 ${shown}` : '구금 위치 이름 확인 불가';
}

/** Actual custody rows and server verdicts. Persuasion reserves a turn; release is immediate. */
export function CaptivePanel({ captives, busy, onPersuade, onRelease }: CaptivePanelProps) {
    return (
        <div className={styles.captives}>
            <p className={styles.muted}>설득은 개인 순을 쓰고, 석방은 순을 쓰지 않습니다.</p>
            {captives.targets.length === 0 ? <p className={styles.empty} role="status">현재 확인된 포로가 없습니다.</p> :
                captives.targets.map(target => (
                    <section className={styles.dispose} key={target.generalId} aria-label={`${target.name} 포로 처분`}>
                        <strong>{target.name}</strong>
                        <span className={styles.muted}>{`현재 소속 ${target.nationName ?? `#${target.nationId}`} · ${heldProvinceLabel(target.heldProvinceName)} · 포획 ${target.capturedAt.year}년 ${target.capturedAt.month}월 ${target.capturedAt.phase}순 · 기한 없음`}</span>
                        {target.actualProvinceId !== target.heldProvinceId ?
                            <span className={styles.warn}>현재 위치가 구금 위치와 달라 처분할 수 없습니다.</span> : null}
                        <div className={styles.chips}>
                            <HelpedInputAction inputId="action.persuadeCaptive"
                                availability={availabilityOf('action.persuadeCaptive', { options: {
                                    available: target.persuadeAvailable, code: target.persuadeCode, reason: target.persuadeReason,
                                } })} label="설득 — 순 고르기" variant="ghost" onAct={() => onPersuade(target.generalId)} />
                            <HelpedInputAction inputId="court.releaseCaptive"
                                availability={availabilityOf('court.releaseCaptive', { options: {
                                    available: target.releaseAvailable, code: target.releaseCode, reason: target.releaseReason,
                                } })} label="석방" variant="ghost" busy={busy} onAct={() => onRelease(target.generalId)} />
                        </div>
                    </section>
                ))}
        </div>
    );
}
