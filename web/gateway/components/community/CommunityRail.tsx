'use client';

import Link from 'next/link';
import { useCallback, useEffect, useId, useState, type ReactNode } from 'react';
import { Panel, Portrait, SectionHeader, type SectionTone } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import { useAuth } from '@/lib/auth-context';
import { BoardRequestError, fetchBoardPosts, type BoardPost } from '@/lib/board';

type Popular = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; posts: readonly BoardPost[] };

/** 레일 패널 — 모바일(768 미만)은 목록 아래 접이 패널로 접혀 있다(설계서 §3.1 배치). 데스크톱은 늘 펼쳐 둔다. */
function RailPanel({ title, sub, tone, children }: {
    readonly title: string;
    readonly sub?: string;
    readonly tone?: SectionTone;
    readonly children: ReactNode;
}) {
    const [open, setOpen] = useState(false);
    const body = useId();
    return (
        <Panel className={`gw31-board__rail-panel${open ? '' : ' is-folded'}`} aria-label={title}>
            <SectionHeader
                title={title}
                sub={sub}
                tone={tone}
                actions={(
                    <button type="button" className="os-button os-button--ghost os-button--sm gw31-board__fold" aria-expanded={open} aria-controls={body} onClick={() => setOpen((v) => !v)}>
                        {open ? '접기' : '펼치기'}
                    </button>
                )}
            />
            <div id={body} className="gw31-board__rail-body">{children}</div>
        </Panel>
    );
}

export default function CommunityRail() {
    const { user } = useAuth();
    const [popular, setPopular] = useState<Popular>({ kind: 'loading' });
    const load = useCallback(() => {
        let active = true;
        setPopular({ kind: 'loading' });
        fetchBoardPosts(null, 0, 3, { sort: 'popular' })
            .then((page) => { if (active) setPopular({ kind: 'ready', posts: page.content }); })
            .catch((cause) => { if (active) setPopular({ kind: 'error', message: cause instanceof BoardRequestError ? cause.message : '인기 글을 불러오지 못했습니다.' }); });
        return () => { active = false; };
    }, []);
    useEffect(load, [load]);

    return (
        <aside className="gw31-board__rail" aria-label="커뮤니티 안내">
            <RailPanel title="내 계정">
                {user ? (
                    <div className="gw31-board__me">
                        <Portrait picture={user.picture ?? null} imageServer={user.imageServer ?? 0} size="icon-48" alt="" />
                        <div className="gw31-board__me-text">
                            <b>{user.nickname ?? user.username}</b>
                            <p className="gw31-card__line gw31-card__line--muted">얼굴은 계정 초상입니다. 대표 장수는 계정 설정에서 정합니다.</p>
                            <Link className="gw31-link" href="/account#representative">대표 장수 바꾸기</Link>
                        </div>
                    </div>
                ) : (
                    <p className="gw31-card__line gw31-card__line--muted">로그인하면 글쓰기 · 신고 · 대표 장수 설정을 쓸 수 있습니다.</p>
                )}
            </RailPanel>
            <RailPanel title="인기 글" sub="최근 7일" tone="rust">
                {popular.kind === 'loading' && <StateLine kind="loading" title="인기 글을 불러오는 중" />}
                {popular.kind === 'error' && <StateLine kind="error" title="인기 글을 불러오지 못했습니다" body={popular.message} onRetry={load} />}
                {popular.kind === 'ready' && popular.posts.length === 0 && <StateLine kind="empty" title="최근 7일 인기 글이 없습니다." />}
                {popular.kind === 'ready' && popular.posts.length > 0 && (
                    <ol className="gw31-board__popular">
                        {popular.posts.map((post, index) => (
                            <li key={post.id}>
                                <span className="os-num gw31-board__rank">{index + 1}</span>
                                <Link href={`/board/posts/${post.id}`}>{post.title}</Link>
                                <span className="os-num gw31-card__line--muted" aria-label={`댓글 ${post.commentCount ?? 0}`}>{post.commentCount ?? 0}</span>
                            </li>
                        ))}
                    </ol>
                )}
            </RailPanel>
            <RailPanel title="세 공간의 경계" sub="서로 섞이지 않는다" tone="info">
                <ul className="gw31-board__bounds">
                    <li><b>커뮤니티</b> — 서버 밖, 모든 계정</li>
                    <li><b>회의실</b> — 게임 안, 같은 세력 장수</li>
                    <li><b>기밀실</b> — 게임 안, 기밀실 참여자만 · 열람 기록이 남음</li>
                </ul>
            </RailPanel>
        </aside>
    );
}
