'use client';

import { useEffect, useState } from 'react';
import { Chip, EmptyState, SectionHeader } from '@opensamguk/ui';
import { fetchNotices, formatNoticeDate, type Notice } from '@/lib/notices';

/**
 * 공지(01 로그인 · 02 로비 우측). gateway-api `/notices` 공개 피드. 세 상태를 구분한다:
 * 불러오는 중 / 불러올 수 없음(서버 오류) / 공지 없음. 본문은 평문(pre-line).
 */
export default function NoticeBoard({ limit = 5 }: { readonly limit?: number }) {
    const [notices, setNotices] = useState<Notice[] | null | undefined>(undefined);
    const [openId, setOpenId] = useState<number | null>(null);

    useEffect(() => {
        let alive = true;
        fetchNotices().then((list) => {
            if (alive) setNotices(list);
        });
        return () => {
            alive = false;
        };
    }, []);

    return (
        <section className="os-panel os-panel--static notice-board" aria-label="공지">
            <SectionHeader title="공지" sub={notices ? `${notices.length}건` : undefined} />
            {notices === undefined && <div className="notice-board__flag">불러오는 중…</div>}
            {notices === null && <div className="notice-board__flag notice-board__flag--error">공지를 불러올 수 없습니다.</div>}
            {notices && notices.length === 0 && <EmptyState title="공지가 없습니다." />}
            {notices && notices.length > 0 && (
                <ul className="notice-board__list">
                    {notices.slice(0, limit).map((n) => (
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
        </section>
    );
}
