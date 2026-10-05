'use client';

// 새 장수 시작 역할 고르기(P-E02). 서버 roles(D121 A안 — 계약판 「K5 → C7 used:null 소비 답」)가 오면 그대로 그린다:
//  열린 역할은 고를 수 있고 자리 칩(cap:null = 「인원 제한 없음」)을 붙인다. used:null 은 숫자를 그리지 않는다.
//  닫힌 역할은 사유 단추 — 서버가 답한 닫힘이라 서버 대기 표지를 달지 않는다.
// roles 가 없으면(옛 서버) 지금 그대로: RETAINER 만 열리고 「예비 주공」은 서버 대기 사유.
import { Chip, ReasonTooltip } from '@opensamguk/ui';
import type { CreationEntryRole } from '@/lib/creation-contract';
import type { RoleCard } from '@/lib/create-view';
import styles from './creation.module.css';

const RETAINER_NOTE = '먼저 재야로 만들고, 다음 화면에서 섬길 주공을 고릅니다. 다음 개인 턴에 그 주공의 부에 들어갑니다.';
const PRE_LORD_WAIT = '예비 주공으로 시작하기는 서버가 아직 받지 않습니다.';

function LegacyOptions({ role, setRole }: { readonly role: CreationEntryRole; readonly setRole: (r: CreationEntryRole) => void }) {
    return (
        <>
            <button type="button" role="option" aria-selected={role === 'RETAINER'} className={`${styles.roleOpt}${role === 'RETAINER' ? ` ${styles.cardOn}` : ''}`} onClick={() => setRole('RETAINER')}>
                <span className={styles.roleName}>주공을 섬기며 시작</span>
                <span className={styles.cardSub}>재야로 만든 뒤 섬길 주공을 고릅니다</span>
                {role === 'RETAINER' ? <Chip tone="bronze">고름</Chip> : null}
            </button>
            <ReasonTooltip reason={PRE_LORD_WAIT}>
                <button type="button" role="option" aria-selected={false} aria-disabled="true" className={styles.roleOpt}>
                    <span className={styles.roleName}>예비 주공으로 시작</span>
                    <span className={styles.cardSub}>본관 현에서 거병을 준비합니다 · 서버 준비 중</span>
                </button>
            </ReasonTooltip>
        </>
    );
}

function CardOption({ card, on, setRole }: { readonly card: RoleCard; readonly on: boolean; readonly setRole: (r: CreationEntryRole) => void }) {
    if (!card.allowed) {
        return (
            <ReasonTooltip reason={card.reason ?? ''}>
                <button type="button" role="option" aria-selected={false} aria-disabled="true" className={styles.roleOpt}>
                    <span className={styles.roleName}>{card.title}</span>
                    <span className={styles.cardSub}>{card.sub}</span>
                </button>
            </ReasonTooltip>
        );
    }
    return (
        <button type="button" role="option" aria-selected={on} className={`${styles.roleOpt}${on ? ` ${styles.cardOn}` : ''}`} onClick={() => setRole(card.role)}>
            <span className={styles.roleName}>{card.title}</span>
            <span className={styles.cardSub}>{card.usedText ? `${card.sub} · ${card.usedText}` : card.sub}</span>
            {card.seatChip ? <Chip tone="neutral">{card.seatChip}</Chip> : null}
            {on ? <Chip tone="bronze">고름</Chip> : null}
        </button>
    );
}

export default function RolePick({ cards, role, setRole, seats }: {
    readonly cards: readonly RoleCard[] | null;
    readonly role: CreationEntryRole;
    readonly setRole: (r: CreationEntryRole) => void;
    /** 「사람 장수 자리 12/50 남음」(playerCap) — 없으면 줄을 그리지 않는다. */
    readonly seats: string | null;
}) {
    return (
        <div className={styles.rolePick}>
            <div role="listbox" aria-label="시작할 역할" className={styles.roleList}>
                {cards
                    ? cards.map((card) => <CardOption key={card.role} card={card} on={role === card.role} setRole={setRole} />)
                    : <LegacyOptions role={role} setRole={setRole} />}
            </div>
            {role === 'RETAINER' ? <p className={styles.help}>{RETAINER_NOTE}</p> : null}
            {seats ? <p className={styles.muted} data-seats="">{seats}</p> : null}
        </div>
    );
}
