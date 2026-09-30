'use client';

import { useEffect, useState } from 'react';
import { Chip, EmptyState, SectionHeader } from '@opensamguk/ui';
import { fetchNotices, formatNoticeDate, type Notice } from '@/lib/notices';

/** 서버가 주는 공지는 최대 20건이다(gateway-api /notices). */
const NOTICE_MAX = 20;

/**
 * 공지(01 로그인 · 02 로비 우측). gateway-api `/notices` 공개 피드. 세 상태를 구분한다:
 * 불러오는 중 / 불러올 수 없음(서버 오류) / 공지 없음. 본문은 평문(pre-line).
 * 처음엔 `limit`건, 「공지 모두 보기」로 패널 안에서 20건까지 펼친다(설계서 NB7). 한 번에 하나만 펼친다.
 */
export default function NoticeBoard({ limit = 5, className = '' }: { readonly limit?: number; readonly className?: string }) {
    const [notices, setNotices] = useState<Notice[] | null | undefined>(undefined);
    const [openId, setOpenId] = useState<number | null>(null);
    const [showAll, setShowAll] = useState(false);
    const [attempt, setAttempt] = useState(0);

    useEffect(() => {
        let alive = true;
        setNotices(undefined);
        fetchNotices().then((list) => {
            if (alive) setNotices(list);
        });
        return () => {
            alive = false;
        };
    }, [attempt]);

    const visible = notices ? notices.slice(0, showAll ? NOTICE_MAX : limit) : [];
    const hidden = notices ? Math.min(notices.length, NOTICE_MAX) - visible.length : 0;

    return (
        <section className={`os-panel os-panel--static notice-board ${className}`.trim()} aria-label="공지">
            <SectionHeader title="공지" sub={notices ? `${notices.length}건` : undefined} />
            {notices === undefined && <div className="notice-board__flag">불러오는 중…</div>}
            {notices === null && (
                <div className="notice-board__flag notice-board__flag--error" role="alert">
                    공지를 불러올 수 없습니다.
                    <button type="button" className="os-button os-button--ghost gw31-btn" onClick={() => setAttempt((n) => n + 1)}>다시 시도</button>
                </div>
            )}
            {notices && notices.length === 0 && <EmptyState title="공지가 없습니다." />}
            {notices && notices.length > 0 && (
                <ul className="notice-board__list">
                    {visible.map((n) => (
                        <li key={n.id} className={`notice-board__item${n.pinned ? ' is-pinned' : ''}`}>
                            <button
                                type="button"
                                className="notice-board__row"
                                aria-expanded={openId === n.id}
                                onClick={() => setOpenId((cur) => (cur === n.id ? null : n.id))}
                            >
                                <span className="os-num notice-board__date">{formatNoticeDate(n.publishedAt)}</span>
                                {n.pinned && <Chip tone="bronze">고정</Chip>}
                                <span className="notice-board__title">{n.title}</span>
                            </button>
                            {openId === n.id && <p className="notice-board__body">{n.body}</p>}
                        </li>
                    ))}
                </ul>
            )}
            {hidden > 0 && (
                <button type="button" className="os-button os-button--ghost gw31-btn notice-board__more" onClick={() => setShowAll(true)}>
                    공지 모두 보기 · {hidden}건 더
                </button>
            )}
        </section>
    );
}
