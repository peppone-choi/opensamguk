'use client';

// 서신 카드 — 방향(보냄 · 받음) · 종류 · 보낸 사람 → 받는 사람 · 시각 · 본문 · 지우기. K6 설계서 §3.8.
// 시각은 서버가 준 실제 시각만(게임 날짜는 봉투에 없다). 지운 서신은 「지운 서신입니다」.
import { SafeHtml } from '@/components/SafeHtml';
import { MAIL_SCOPE_LABEL, type MailItem } from '@/lib/mail/mail-model';
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

export function counterpartName(item: MailItem): string {
    const other = item.direction === 'sent' ? item.to : item.from;
    if (item.scope !== 'private') return item.from?.name ?? '';
    return other?.name ?? '';
}

export function MailCard({ item, onDelete, busy }: MailCardProps) {
    const sent = item.direction === 'sent';
    const who = item.scope === 'private'
        ? (sent ? `나 → ${item.to?.name ?? ''}` : `${item.from?.name ?? ''} → 나`)
        : `${item.from?.name ?? ''}${sent ? '(나)' : ''}`;
    return (
        <article className={styles.card} aria-label={`${sent ? '보낸' : '받은'} 서신 — ${counterpartName(item)}`} data-direction={item.direction} data-mail-id={item.id}>
            <div className={styles.cardHead}>
                <span className={`os-chip ${sent ? 'os-chip--info' : 'os-chip--moss'}`}>{sent ? '보냄' : '받음'}</span>
                <span className="os-chip">{MAIL_SCOPE_LABEL[item.scope]}</span>
                <span className={styles.who}>{who}</span>
                {item.from?.nation ? <span className={styles.nation}><i style={{ background: item.from.nation.color }} aria-hidden="true" />{item.from.nation.name}</span> : null}
                <time className={styles.time} dateTime={item.time}>{mailTime(item.time)}</time>
            </div>
            <div className={styles.cardBody}>
                {item.html == null ? <p className={styles.gone}>지운 서신입니다</p> : <SafeHtml html={item.html} />}
            </div>
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
