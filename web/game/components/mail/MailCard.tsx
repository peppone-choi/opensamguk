'use client';

// 서신 카드 — 방향(보냄 · 받음) · 종류 · 보낸 사람 → 받는 사람 · 시각 · 본문 · 지우기. K6 설계서 §3.8.
// 시각은 서버가 준 실제 시각만(게임 날짜는 봉투에 없다). 지운 서신은 「지운 서신입니다」.
// 외교 서신: 세력 → 세력. 권한이 없어 서버가 가린 서신은 「군주 · 외교권자만 봅니다」. 제의(불가침 · 종전 · 파기)가 붙은
// 받은 서신은 수락 · 거절 자리에 서버 대기 안내 — 응답 입력이 원장에 없다(K6 설계서 §3.7 「받은 제의」, 계약판 K6-05).
import { StatusView, safeNationColor } from '@opensamguk/ui';
import { SafeHtml } from '@/components/SafeHtml';
import { MAIL_SCOPE_LABEL, PROPOSAL_LABEL, type MailItem } from '@/lib/mail/mail-model';
import styles from './Mail.module.css';

export interface MailCardProps {
    readonly item: MailItem;
    /** 지우기(내가 보낸 5분 안의 서신) — 없으면 단추를 그리지 않는다. */
    readonly onDelete?: (item: MailItem) => void;
    readonly busy?: boolean;
}

export function mailTime(iso: string): string {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return '';
    return d.toLocaleString('ko-KR', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit', hour12: false });
}

export const DIPLOMACY_HIDDEN = '외교 서신은 군주 · 외교권자만 봅니다';

export function counterpartName(item: MailItem): string {
    if (item.scope === 'diplomacy') return (item.direction === 'sent' ? item.toNation?.name : item.from?.nation?.name) ?? '';
    const other = item.direction === 'sent' ? item.to : item.from;
    if (item.scope !== 'private') return item.from?.name ?? '';
    return other?.name ?? '';
}

function whoLine(item: MailItem): string {
    const sent = item.direction === 'sent';
    if (item.scope === 'private') return sent ? `나 → ${item.to?.name ?? ''}` : `${item.from?.name ?? ''} → 나`;
    if (item.scope === 'diplomacy') {
        const fromNation = item.from?.nation?.name ?? '';
        return `${fromNation}${item.from ? ` ${item.from.name}` : ''} → ${sent ? item.toNation?.name ?? '' : '우리 세력'}`;
    }
    return `${item.from?.name ?? ''}${sent ? '(나)' : ''}`;
}

export function MailCard({ item, onDelete, busy }: MailCardProps) {
    const sent = item.direction === 'sent';
    const who = whoLine(item);
    return (
        <article className={styles.card} aria-label={`${sent ? '보낸' : '받은'} 서신 — ${counterpartName(item)}`} data-direction={item.direction} data-mail-id={item.id}>
            <div className={styles.cardHead}>
                <span className={`os-chip ${sent ? 'os-chip--info' : 'os-chip--moss'}`}>{sent ? '보냄' : '받음'}</span>
                <span className="os-chip">{MAIL_SCOPE_LABEL[item.scope]}</span>
                {item.proposal ? <span className="os-chip os-chip--bronze">{PROPOSAL_LABEL[item.proposal.kind]}</span> : null}
                {item.proposal?.handled ? <span className="os-chip">답함</span> : null}
                <span className={styles.who}>{who}</span>
                {item.from?.nation ? <span className={styles.nation}><i style={{ background: safeNationColor(item.from.nation.color) }} aria-hidden="true" />{item.from.nation.name}</span> : null}
                <time className={styles.time} dateTime={item.time}>{mailTime(item.time)}</time>
            </div>
            <div className={styles.cardBody}>
                {item.hidden ? <p className={styles.gone}>{DIPLOMACY_HIDDEN}</p>
                    : item.html == null ? <p className={styles.gone}>지운 서신입니다</p> : <SafeHtml html={item.html} />}
            </div>
            {item.proposal && !item.proposal.handled && !sent ? (
                <div className={styles.cardFoot}>
                    <StatusView kind="waiting" title="제의에 답하기는 서버 준비 중입니다" body="수락 · 거절은 외교 화면 「받은 제의」에서 합니다 — 서버가 준비되면 열립니다." />
                </div>
            ) : null}
            {onDelete && item.deletable ? (
                <div className={styles.cardFoot}>
                    <button type="button" className="os-button os-button--ghost" aria-busy={busy || undefined} onClick={() => { if (!busy) onDelete(item); }}>
                        지우기
                    </button>
                </div>
            ) : null}
        </article>
    );
}

export default MailCard;
