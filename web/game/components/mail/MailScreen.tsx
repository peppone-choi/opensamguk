'use client';

// 서신(P-Q02) — 탭 개인 · 세력 · 전체 · 요청, 목록 · 읽기 · 쓰기. K6 설계서 §3.8, 보드 V31K6Mail · MMail · MailDrawer.
// 데스크톱: 왼쪽 목록 360 | 가운데 읽기 | 오른쪽 쓰기 400. 모바일: 목록 → 읽기 → 쓰기(한 화면씩). 좁은 칸(drawer — 외교 화면의 외교 서신)은 목록 ↔ 쓰기.
// 머리줄 서신 서랍(header)은 목록(요청 탭은 받은 요청 + 「조정에서 모두 보기」) 위, 「짧은 서신」 아래 — 폭과 상관없이 함께 보인다.
// 재야는 세력 · 외교 탭을 그리지 않는다. 요청 탭은 받은 요청(발령 응답 · 정치 동의) — 순을 쓰지 않고 그 자리에서 응답한다.
// 서신 화면의 요청 탭은 「받은 것 | 보낸 것」(보낸 도움 요청, D68), 개인 서신 쓰기는 「글 서신 | 도움 요청」이다(MailRequestsPane · MailWrite).
// 외교 서신(diplomacy)은 외교 화면(P-K02)의 칸이다: 외교 화면은 tabs={['diplomacy']}로 같은 부품을 쓴다(탭 줄 없이).
import Link from 'next/link';
import { useState } from 'react';
import { ConfirmDialog, StatusView } from '@opensamguk/ui';
import { api } from '@/lib/api';
import { submitCommandAndAwaitResult } from '@/lib/commandSubmit';
import { MAIL_SCOPE_LABEL, MAIL_SCOPES, type MailItem, type MailScope } from '@/lib/mail/mail-model';
import { deleteMail, useMailbox, type MailMe, type MailOutcome } from '@/lib/mail/use-mail';
import type { UseRequests } from '@/lib/requests';
import { DIPLOMACY_HIDDEN, MailCard, counterpartName, mailTime } from './MailCard';
import { MailCompose } from './MailCompose';
import { MailRequestsPane } from './MailRequestsPane';
import { MailWrite } from './MailWrite';
import styles from './Mail.module.css';

export type MailTab = MailScope | 'requests';

export const DEFAULT_MAIL_TABS: readonly MailTab[] = [...MAIL_SCOPES, 'requests'];

export interface MailScreenProps {
    readonly me: MailMe;
    /** 보일 탭(순서대로). 기본 = 개인 · 세력 · 전체 · 요청. 재야면 세력 · 외교는 빠진다. 하나뿐이면 탭 줄을 그리지 않는다. */
    readonly tabs?: readonly MailTab[];
    readonly initialTab?: MailTab;
    readonly initialRecipientId?: number | null;
    /** 머리줄 배지와 같은 받은 요청 읽기(있으면 다시 읽지 않는다). */
    readonly requests?: UseRequests;
    /** 좁은 칸(drawer — 외교 화면의 외교 서신, 읽기는 목록 안에서 펼침) · 머리줄 서신 서랍(header — 목록 + 짧은 서신). */
    readonly variant?: 'page' | 'drawer' | 'header';
    /** 바뀌면 서신함을 다시 읽는다(페이지 「새로고침」). 턴이 끝날 때는 스스로 다시 읽는다. */
    readonly refreshKey?: number;
    /** 탭을 바꿀 때 — 머리줄 서신 서랍은 주소(`?mail=`)를 맞춘다. */
    readonly onTabChange?: (tab: MailTab) => void;
    /** 종전 제의 수락 후 외교 관계 조회를 새로 읽는다. */
    readonly onDiplomacyResponded?: () => void;
    /** 머리줄 서신 서랍의 링크 주소 — 서신 화면(「서신에서 쓰기」) · 조정 발령 탭(「조정에서 모두 보기」). 서버가 든 주소를 셸 쪽이 만든다. */
    readonly links?: { readonly mail: string; readonly court: string };
}

export function MailScreen({ me, tabs: wanted = DEFAULT_MAIL_TABS, initialTab, initialRecipientId = null, requests, variant = 'page', refreshKey = 0, onTabChange, onDiplomacyResponded, links }: MailScreenProps) {
    const tabs = wanted.filter((t) => (t !== 'national' && t !== 'diplomacy') || me.nationId > 0);
    const [tab, setTab] = useState<MailTab>(initialTab && tabs.includes(initialTab) ? initialTab : tabs[0] ?? 'private');
    const [screen, setScreen] = useState<'list' | 'read' | 'write'>(initialRecipientId != null ? 'write' : 'list');
    const [openId, setOpenId] = useState<number | null>(null);
    const [confirm, setConfirm] = useState<MailItem | null>(null);
    const [busyId, setBusyId] = useState<number | null>(null);
    const [notice, setNotice] = useState<MailOutcome | null>(null);
    const scope: MailScope = tab === 'requests' ? 'private' : tab;
    // 재야가 외교 칸만 연 경우처럼 남는 탭이 없으면 아무 서신함도 읽지 않는다(개인 서신으로 떨어지지 않게).
    const none = tabs.length === 0;
    const box = useMailbox(none || tab === 'requests' ? null : me, scope, refreshKey);
    const items = box.load.state === 'ready' ? box.load.items : [];
    const open = items.find((it) => it.id === openId) ?? null;
    // 외교 서신을 볼 권한이 없으면 서버가 모든 행을 가린다 — 목록 대신 한 줄(§3.7).
    const allHidden = scope === 'diplomacy' && items.length > 0 && items.every((it) => it.hidden);

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

    const respond = async (item: MailItem, accept: boolean) => {
        if (busyId != null || item.scope !== 'diplomacy' || item.direction !== 'received' ||
            item.hidden || item.proposal?.kind !== 'stop_war' || item.proposal.handled) return;
        setBusyId(item.id); setNotice(null);
        try {
            const out = await submitCommandAndAwaitResult(() => accept
                ? api.messageAccept(item.id, me.generalId) : api.messageDecline(item.id, me.generalId));
            if (out.status === 'applied') {
                setNotice({ kind: 'ok', text: accept ? '종전 제의를 수락했습니다. 양 세력의 교전이 끝났습니다.' : '종전 제의를 거절했습니다.' });
                box.reload();
                if (accept) onDiplomacyResponded?.();
            } else if (out.status === 'rejected') {
                setNotice({ kind: 'error', text: out.reason ?? '종전 제의에 답하지 못했습니다.',
                    ...(out.code ? { code: out.code } : {}) });
                box.reload();
            } else {
                setNotice({ kind: 'info', text: '응답 처리 중입니다. 결과와 외교 서신을 다시 확인해 주세요.' });
                box.reload();
            }
        } catch {
            setNotice({ kind: 'error', text: '종전 제의 결과를 확인하지 못했습니다. 다시 조회해 주세요.' });
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
            {allHidden ? <StatusView kind="denied" title={DIPLOMACY_HIDDEN} howTo="군주가 되거나 외교권을 받으면 읽을 수 있습니다." /> : null}
            {items.length > 0 && !allHidden ? (
                <ul className={styles.rows} aria-label={`${MAIL_SCOPE_LABEL[scope]} 서신`}>
                    {items.map((it) => (
                        <li key={it.id}>
                            {variant !== 'page' ? (
                                <MailCard item={it} onDelete={setConfirm} onRespond={(item, accept) => void respond(item, accept)} busy={busyId === it.id} />
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
                                    <span className={styles.rowPreview}>{it.hidden ? DIPLOMACY_HIDDEN : it.html == null ? '지운 서신입니다' : previewText(it.html)}</span>
                                </button>
                            )}
                        </li>
                    ))}
                </ul>
            ) : null}
            {box.load.state === 'ready' && items.length > 0 && !allHidden ? (
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

    if (none) {
        return (
            <section className={styles.mail} data-variant={variant} aria-label="서신" data-testid="mail-screen">
                <StatusView kind="empty" title={wanted.includes('diplomacy') ? '소속 세력이 없어 외교 서신이 없습니다' : '소속 세력이 없어 볼 서신함이 없습니다'} body="세력에 들어가면 이곳이 열립니다." />
            </section>
        );
    }

    return (
        <section className={styles.mail} data-variant={variant} data-screen={screen} aria-label="서신" data-testid="mail-screen">
            {/* 탭 묶음(tablist)에는 탭만 둔다 — 「서신 쓰기」 단추를 같은 묶음에 넣으면 aria-required-children 위반(K10 10-02 측정). */}
            <div className={styles.tabs}>
                {tabs.length > 1 ? (
                    <div className={styles.tabList} role="tablist" aria-label="서신 묶음">
                        {tabs.map((t) => (
                            <button key={t} type="button" role="tab" aria-selected={t === tab} className={styles.tab}
                                onClick={() => { setTab(t); setOpenId(null); setScreen('list'); setNotice(null); onTabChange?.(t); }}>
                                {t === 'requests' ? `요청${requests && requests.waiting > 0 ? ` ${requests.waiting}` : ''}` : MAIL_SCOPE_LABEL[t]}
                            </button>
                        ))}
                    </div>
                ) : null}
                {tab !== 'requests' && variant !== 'header' ? (
                    <button type="button" className={`os-button os-button--primary ${styles.writeButton}`} onClick={() => setScreen('write')}>
                        {tab === 'diplomacy' ? '외교 서신 쓰기' : '서신 쓰기'}
                    </button>
                ) : null}
            </div>
            {notice ? <p className={styles.outcome} data-kind={notice.kind} role={notice.kind === 'error' ? 'alert' : 'status'}>{notice.text}</p> : null}

            {tab === 'requests' ? (
                <MailRequestsPane generalId={me.generalId} requests={requests} variant={variant} courtHref={links?.court} />
            ) : (
                <div className={styles.panes}>
                    <div className={styles.listPane}>{list}</div>
                    {variant === 'page' ? (
                        <div className={styles.readPane}>
                            <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={() => setScreen('list')}>← 서신 목록</button>
                            {open ? <MailCard item={open} onDelete={setConfirm} onRespond={(item, accept) => void respond(item, accept)} busy={busyId === open.id} /> : <p className={styles.muted}>읽을 서신을 고르세요.</p>}
                        </div>
                    ) : null}
                    <div className={styles.writePane}>
                        <button type="button" className={`os-button os-button--ghost ${styles.back}`} onClick={() => setScreen('list')}>← 서신 목록</button>
                        <MailWrite me={me} scope={scope} initialRecipientId={initialRecipientId} onSent={box.reload}
                            short={variant === 'header'} fullHref={links?.mail} />
                    </div>
                </div>
            )}
            {/* 머리줄 서신 서랍의 요청 탭에도 짧은 서신을 둔다(보드) — 받는 곳은 개인 서신. */}
            {variant === 'header' && tab === 'requests' ? (
                <div className={styles.writePane}><MailCompose me={me} scope="private" short fullHref={links?.mail} /></div>
            ) : null}

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
