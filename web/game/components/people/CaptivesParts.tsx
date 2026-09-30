'use client';

import { useState } from 'react';
import { Chip, InputAction, ReasonTooltip, StatusView, type InputAvailability } from '@opensamguk/ui';
import { plainGlyphs } from '@/lib/plain-glyphs';
import type { PeopleOptions } from '@/lib/types';
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

export interface TalentPanelProps {
    readonly employ: PeopleOptions | null;
    readonly search: PeopleOptions | null;
    readonly searchAvailability: InputAvailability | null;
    readonly employAvailability: InputAvailability | null;
    readonly onSearch: () => void;
    /** 고른 인재로 명령 흐름을 연다(`?do=action.employ&target=general:<id>`). */
    readonly onEmploy: (generalId: number) => void;
}

/**
 * 「등용할 수 있는 인재」(보드 V31K4Captives 왼쪽) — 찾지 못한 인물 수 · 인재탐색 · 대상 카드(불가는 사유) · 「{이름} 등용」.
 * 성공 확률은 서버가 주면 숫자(K4-12 `chance`), 안 주면 칸 없음. 비면 「인재탐색으로 찾습니다」.
 */
export function TalentPanel({ employ, search, searchAvailability, employAvailability, onSearch, onEmploy }: TalentPanelProps) {
    const rows = talentRows(employ);
    const [picked, setPicked] = useState<number | null>(null);
    const chosen = rows.find((r) => r.generalId === picked && r.available) ?? null;
    const undiscovered = search?.undiscoveredCount;
    return (
        <div className={styles.talent}>
            <div className={styles.talentHead}>
                {undiscovered != null ? <Chip>{`찾지 못한 인물 ${undiscovered}명`}</Chip> : null}
                <InputAction inputId="action.search" availability={searchAvailability} label="인재탐색 — 명령 목록에 넣기" variant="ghost" onAct={onSearch} />
            </div>
            {rows.length === 0 ? (
                <p className={styles.empty} role="status">이 현에서 찾은 재야 인물이 없습니다 — 인재탐색으로 찾습니다.</p>
            ) : (
                <div role="listbox" aria-label="인재" className={styles.talentList}>
                    {rows.map((r) => r.available ? (
                        <button key={r.generalId} type="button" role="option" aria-selected={r.generalId === picked}
                            className={['os-opt', styles.talentRow, r.generalId === picked ? 'os-opt--sel' : ''].filter(Boolean).join(' ')}
                            onClick={() => setPicked(r.generalId)}>
                            <span className="os-opt__text"><span className="os-opt__name">{r.name}</span><span className="os-opt__sub">재야</span></span>
                            <span className="os-opt__end"><Chip tone="moss">등용 가능</Chip></span>
                        </button>
                    ) : (
                        <ReasonTooltip key={r.generalId} reason={r.reason ?? ''} code={r.code ?? undefined} title={`${r.name} — 지금 등용할 수 없습니다`} block>
                            {(describedBy) => (
                                <button type="button" role="option" aria-selected="false" aria-disabled="true" aria-describedby={describedBy}
                                    className={`os-opt os-opt--no ${styles.talentRow}`}>
                                    <span className="os-opt__text"><span className="os-opt__name">{r.name}</span><span className="os-opt__sub">재야</span></span>
                                    <span className="os-opt__end"><span className="os-opt__why">{r.reason}</span></span>
                                </button>
                            )}
                        </ReasonTooltip>
                    ))}
                </div>
            )}
            {chosen ? (
                <InputAction inputId="action.employ" availability={employAvailability} label={`${chosen.name} 등용 — 명령 목록에 넣기`}
                    onAct={() => onEmploy(chosen.generalId)} block />
            ) : null}
        </div>
    );
}

export interface CaptivePanelProps {
    /** 포로 설득(action.persuadeCaptive) — 원장 PLANNED 면 NOT_DELIVERED. */
    readonly persuade: InputAvailability | null;
    /**
     * 석방 · 억류 — 원장 행이 생기면(계약판 K4-16, 이름은 C1 이 정한다) 화면이 그 행을 넘긴다. inputId 는 행에서 읽는다 —
     * 여기서 짓지 않는다. 행이 없으면 null → 그리지 않는다.
     */
    readonly release: InputAvailability | null;
    readonly detain: InputAvailability | null;
    readonly onPersuade: () => void;
    readonly onRelease: () => void;
    readonly onDetain: () => void;
}

/**
 * 「잡은 포로」(보드 오른쪽) — 포로 목록 읽기(K4-12) 전까지 서버 대기 A. 처분 단추는 원장 행이 있는 것만(설득은 PLANNED 점선).
 */
export function CaptivePanel({ persuade, release, detain, onPersuade, onRelease, onDetain }: CaptivePanelProps) {
    return (
        <div className={styles.captives}>
            <StatusView kind="waiting" title="포로 목록 — 준비 중"
                body="포로를 읽는 서버 기능이 아직 없습니다. 포로가 생기면 이 자리에 옛 주인 · 결속 · 설득 확률이 보입니다." />
            <div className={styles.dispose}>
                <span className={styles.muted}>처분</span>
                <div className={styles.chips}>
                    <InputAction inputId="action.persuadeCaptive" availability={persuade} label="설득" variant="ghost" onAct={onPersuade} />
                    {release ? <InputAction inputId={release.inputId} availability={release} label="석방" variant="ghost" onAct={onRelease} /> : null}
                    {detain ? <InputAction inputId={detain.inputId} availability={detain} label="억류" variant="ghost" onAct={onDetain} /> : null}
                </div>
                <p className={styles.muted}>설득은 포로와 같은 자리에서 쓰는 직접 행동입니다.</p>
            </div>
        </div>
    );
}
