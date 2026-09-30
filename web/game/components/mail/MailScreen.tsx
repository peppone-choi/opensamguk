'use client';

// 서신(P-Q02) — 탭 개인 · 세력 · 전체 · 요청, 목록 · 읽기 · 쓰기. K6 설계서 §3.8, 보드 V31K6Mail · MMail · MailDrawer.
// 데스크톱: 왼쪽 목록 360 | 가운데 읽기 | 오른쪽 쓰기 400. 모바일: 목록 → 읽기 → 쓰기(한 화면씩). 서랍(drawer)은 목록 + 짧은 쓰기.
// 재야는 세력 탭을 그리지 않는다. 요청 탭은 받은 요청(발령 응답 · 정치 동의) — 순을 쓰지 않고 그 자리에서 응답한다.
import { useState } from 'react';
import { ConfirmDialog, StatusView } from '@opensamguk/ui';
import { IncomingRequests } from '@/components/requests/IncomingRequests';
import { MAIL_SCOPE_LABEL, MAIL_SCOPES, type MailItem, type MailScope } from '@/lib/mail/mail-model';
import { deleteMail, useMailbox, type MailMe, type MailOutcome } from '@/lib/mail/use-mail';
import type { UseRequests } from '@/lib/requests';
import { MailCard, counterpartName, mailTime } from './MailCard';
import { MailCompose } from './MailCompose';
import styles from './Mail.module.css';

export type MailTab = MailScope | 'requests';

export interface MailScreenProps {
    readonly me: MailMe;
    readonly initialTab?: MailTab;
    readonly initialRecipientId?: number | null;
    /** 머리줄 배지와 같은 받은 요청 읽기(있으면 다시 읽지 않는다). */
    readonly requests?: UseRequests;
    /** 서랍(머리줄 서신 단추) — 좁은 폭, 읽기는 목록 안에서 펼친다. */
    readonly variant?: 'page' | 'drawer';
}

export function MailScreen({ me, initialTab = 'private', initialRecipientId = null, requests, variant = 'page' }: MailScreenProps) {
    const tabs: MailTab[] = [...MAIL_SCOPES.filter((s) => s !== 'national' || me.nationId > 0), 'requests'];
    const [tab, setTab] = useState<MailTab>(tabs.includes(initialTab) ? initialTab : 'private');
    const [screen, setScreen] = useState<'list' | 'read' | 'write'>(initialRecipientId != null ? 'write' : 'list');
    const [openId, setOpenId] = useState<number | null>(null);
    const [confirm, setConfirm] = useState<MailItem | null>(null);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [notice, setNotice] = useState<MailOutcome | null>(null);
    const scope: MailScope = tab === 'requests' ? 'private' : tab;
    const box = useMailbox(tab === 'requests' ? null : me, scope);
    const items = box.load.state === 'ready' ? box.load.items : [];
    const open = items.find((it) => it.id === openId) ?? null;

    const remove = async (item: MailItem) => {
        setConfirm(null); setBusyId(item.id); setNotice(null);
        try {
            const out = await deleteMail(me, item);
            setNotice(out);
            if (out.kind !== 'error') { box.reload(); if (openId === item.id) setOpenId(null); }
        } catch {
            setNotice({ kind: 'error', text: '서신을 지우지 못했습니다 — 잠시 뒤 다시 해 보세요' });
        } finally {
            setBusyId(null);
        }
    };

    const list = (
        <div className={styles.list}>
            {box.load.state === 'loading' ? <StatusView kind="loading" rows={4} /> : null}
            {box.load.state === 'denied' ? (
                <StatusView kind="denied" title="이 서신함은 볼 수 없습니다" howTo="다시 들어오거나 이 서버의 내 장수를 확인해 주세요." />
            ) : null}
            {box.load.state === 'error' ? <StatusView kind="error" title={box.load.message} body="빈 서신함이 아닙니다 — 불러오기가 실패했습니다." onRetry={box.reload} /> : null}
            {box.load.state === 'none' ? <StatusView kind="empty" title={box.load.message} body="세력에 들어가면 세력 서신을 주고받을 수 있습니다." /> : null}
            {box.load.state === 'ready' && items.length === 0 ? (
                <StatusView kind="empty" title="서신이 없습니다" body="「서신 쓰기」로 먼저 보내 보세요." />
            ) : null}
            {items.length > 0 ? (
                <ul className={styles.rows} aria-label={`${MAIL_SCOPE_LABEL[scope]} 서신`}>
                    {items.map((it) => (
                        <li key={it.id}>
                            {variant === 'drawer' ? (
                                <MailCard item={it} onDelete={setConfirm} busy={busyId === it.id} />
                            ) : (
                                <button
                                    type="button"
                                    className={styles.row}
                                    aria-current={it.id === openId || undefined}
                                    onClick={() => { setOpenId(it.id); setScreen('read'); }}
                                >
                                    <span className={`os-chip ${it.direction === 'sent' ? 'os-chip--info' : 'os-chip--moss'}`}>{it.direction === 'sent' ? '보냄' : '받음'}</span>
                                    <span className={styles.rowWho}>{counterpartName(it)}</span>
                                    <span className={styles.rowTime}>{mailTime(it.time)}</span>
                                    <span className={styles.rowPreview}>{it.html == null ? '지운 서신입니다' : previewText(it.html)}</span>
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            ) : null}
            {box.load.state === 'ready' && items.length > 0 ? (
                <div className={styles.older}>
                    {box.older === 'end' ? <p className={styles.muted}>더 이전 서신이 없습니다</p> : (
                        <button type="button" className="os-button os-button--ghost os-button--block" aria-busy={box.older === 'loading' || undefined} onClick={() => void box.loadOlder()}>
                            {box.older === 'loading' ? '불러오는 중…' : '이전 서신'}
                        </button>
                    )}
                    {box.older === 'error' ? <p className={styles.error} role="alert">이전 서신을 불러오지 못했습니다 — 다시 눌러 주세요</p> : null}
                </div>
            ) : null}
        </div>
    );

    return (
        <section className={styles.mail} data-variant={variant} data-screen={screen} aria-label="서신" data-testid="mail-screen">
            <div className={styles.tabs} role="tablist" aria-label="서신 묶음">
                {tabs.map((t) => (
                    <button key={t} type="button" role="tab" aria-selected={t === tab} className={styles.tab}
                        onClick={() => { setTab(t); setOpenId(null); setScreen('list'); setNotice(null); }}>
                        {t === 'requests' ? `요청${requests && requests.waiting > 0 ? ` ${requests.waiting}` : ''}` : MAIL_SCOPE_LABEL[t]}
                    </button>
                ))}
                {tab !== 'requests' ? (
                    <button type="button" className={`os-button os-button--primary ${styles.writeButton}`} onClick={() => setScreen('write')}>서신 쓰기</button>
                ) : null}
            </div>
            {notice ? <p className={styles.outcome} data-kind={notice.kind} role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p> : null}

            {tab === 'requests' ? (
                <div className={styles.requests}>
                    <IncomingRequests generalId={me.generalId} source={requests} compact={variant === 'drawer'} />
                </div>
            ) : (
                <div className={styles.panes}>
                    <div className={styles.listPane}>{list}</div>
                    {variant === 'page' ? (
                        <div className={styles.readPane}>
                            <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={() => setScreen('list')}>← 서신 목록</button>
                            {open ? <MailCard item={open} onDelete={setConfirm} busy={busyId === open.id} /> : <p className={styles.muted}>읽을 서신을 고르세요.</p>}
                        </div>
                    ) : null}
                    <div className={styles.writePane}>
                        <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={() => setScreen('list')}>← 서신 목록</button>
                        <MailCompose me={me} scope={scope} initialRecipientId={initialRecipientId} onSent={box.reload} />
                    </div>
                </div>
            )}

            <ConfirmDialog
                open={confirm != null}
                title="이 서신을 지웁니다"
                message="보낸 지 5분 안의 내 서신만 지울 수 있습니다. 받는 사람에게는 「지운 서신입니다」로 보입니다."
                confirmLabel="지우기"
                cancelLabel="그대로 두기"
                danger
                busy={busyId != null}
                onConfirm={() => { if (confirm) void remove(confirm); }}
                onCancel={() => setConfirm(null)}
            />
        </section>
    );
}

function previewText(html: string): string {
    const text = html.replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ').trim();
    return text.length > 60 ? `${text.slice(0, 60)}…` : text;
}

export default MailScreen;
