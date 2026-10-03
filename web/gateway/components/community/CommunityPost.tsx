'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState } from 'react';
import { Button, Chip, ConfirmDialog, Icon, Portrait } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import { useAuth } from '@/lib/auth-context';
import {
    BoardRequestError,
    boardCategoryLabel,
    boardDate,
    createBoardComment,
    deleteBoardComment,
    deleteBoardPost,
    fetchBoardPost,
    reportBoardComment,
    reportBoardPost,
    setBoardPostPinned,
    type BoardComment,
    type BoardPostDetail,
} from '@/lib/board';
import CommunityShell from './CommunityShell';
import { RepresentativeChip } from './CommunityList';
import { CommentForm, ReportSheet, type ReportTarget } from './PostParts';

type Load = { kind: 'loading' } | { kind: 'missing' } | { kind: 'error'; message: string } | { kind: 'ready'; data: BoardPostDetail };
type Confirm = { kind: 'post' } | { kind: 'comment'; comment: BoardComment } | null;

const BUSY = '처리 중입니다';

/**
 * P-G07 커뮤니티 글(설계서 §3.2, 보드 V31K5BoardPost · MBoardPost). 글 · 댓글 지우기는 확인 대화상자를 거친다
 * (옛 화면은 바로 지웠다). 쓰기 조작은 서버가 준 권한(canDelete · 운영자)으로만 보이고, 서버가 다시 검사한다.
 */
export default function CommunityPost({ postId }: { readonly postId: string }) {
    const router = useRouter();
    const { user } = useAuth();
    const [load, setLoad] = useState<Load>({ kind: 'loading' });
    const [busy, setBusy] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const [note, setNote] = useState<string | null>(null);
    const [report, setReport] = useState<ReportTarget | null>(null);
    const [confirm, setConfirm] = useState<Confirm>(null);

    const open = useCallback(() => {
        let active = true;
        setLoad({ kind: 'loading' });
        fetchBoardPost(postId)
            .then((data) => { if (active) setLoad({ kind: 'ready', data }); })
            .catch((cause) => {
                if (!active) return;
                if (cause instanceof BoardRequestError && cause.status === 404) setLoad({ kind: 'missing' });
                else setLoad({ kind: 'error', message: cause instanceof Error ? cause.message : '게시글을 불러오지 못했습니다.' });
            });
        return () => { active = false; };
    }, [postId]);
    useEffect(open, [open]);

    const run = async (action: () => Promise<void>, fallback: string) => {
        setBusy(true);
        setActionError(null);
        try {
            await action();
        } catch (cause) {
            setActionError(cause instanceof Error ? cause.message : fallback);
        } finally {
            setBusy(false);
            setConfirm(null);
        }
    };
    const update = (change: (data: BoardPostDetail) => BoardPostDetail) =>
        setLoad((current) => (current.kind === 'ready' ? { kind: 'ready', data: change(current.data) } : current));

    if (load.kind !== 'ready') {
        return (
            <CommunityShell>
                <article className="gw31-post">
                    <Link className="gw31-link gw31-post__back" href="/board"><Icon name="arrow-left" size={16} />게시판 목록</Link>
                    {load.kind === 'loading' && <StateLine kind="loading" title="게시글을 불러오는 중" />}
                    {load.kind === 'missing' && <StateLine kind="empty" title="게시글을 찾을 수 없습니다" body="지워졌거나 주소가 잘못되었습니다." />}
                    {load.kind === 'error' && <StateLine kind="error" title="게시글을 불러오지 못했습니다" body={load.message} onRetry={open} />}
                </article>
            </CommunityShell>
        );
    }

    const { post, comments } = load.data;
    // 「수정됨」은 뺐다 — board-api 는 고정 · 해제 때도 updatedAt 을 바꿔(updatePin) 내용을 고친 적 없는 글에도 붙었다(#1211 리뷰).
    // 서버가 내용 수정 시각(editedAt)을 따로 주면 다시 단다(계약판 K5 → C0 메모).
    const meta = [boardDate(post.createdAt), post.viewCount != null ? `조회 ${post.viewCount.toLocaleString()}` : null].filter(Boolean).join(' · ');
    const removePost = () => run(async () => {
        await deleteBoardPost(post.id);
        router.push('/board');
    }, '게시글을 삭제하지 못했습니다.');
    const removeComment = (comment: BoardComment) => run(async () => {
        await deleteBoardComment(post.id, comment.id);
        update((data) => ({ ...data, comments: data.comments.filter((item) => item.id !== comment.id) }));
    }, '댓글을 삭제하지 못했습니다.');
    const togglePin = () => run(async () => {
        const next = await setBoardPostPinned(post.id, !post.pinned);
        update((data) => ({ ...data, post: next }));
    }, '고정 상태를 바꾸지 못했습니다.');
    const startReport = (target: ReportTarget) => { setNote(null); setActionError(null); setReport(target); };
    const sendReport = async (reason: string) => {
        if (!report) return;
        if (report.kind === 'post') await reportBoardPost(post.id, reason);
        else await reportBoardComment(post.id, report.id, reason);
        setReport(null);
        setNote('신고를 접수했습니다. 운영자가 확인합니다.');
    };
    const action = (label: string, onClick: () => void, variant: 'ghost' | 'danger' = 'ghost') => (busy
        ? <Button size="sm" variant={variant} disabled reason={BUSY}>{label}</Button>
        : <Button size="sm" variant={variant} onClick={onClick}>{label}</Button>);

    return (
        <CommunityShell>
            <article className="gw31-post">
                <Link className="gw31-link gw31-post__back" href="/board"><Icon name="arrow-left" size={16} />게시판 목록</Link>
                <div className="gw31-board__labels">
                    <Chip>{boardCategoryLabel(post.category)}</Chip>
                    {post.pinned && <Chip tone="bronze">고정</Chip>}
                </div>
                <h1 className="gw31-post__title os-serif">{post.title}</h1>
                <div className="gw31-post__meta">
                    <Portrait picture={post.authorPicture ?? null} imageServer={post.authorImageServer ?? 0} size="icon-40" alt="" />
                    <b>{post.authorName}</b>
                    <RepresentativeChip name={post.authorGeneralName} />
                    <span className="os-num gw31-post__when">{meta}</span>
                </div>
                <div className="gw31-post__content" dangerouslySetInnerHTML={{ __html: post.contentHtml }} />
                <div className="gw31-post__actions">
                    {user && !post.canDelete && action('신고', () => startReport({ kind: 'post', id: post.id }))}
                    {post.canDelete && action('게시글 삭제', () => setConfirm({ kind: 'post' }), 'danger')}
                    {user?.role === 'ADMIN' && action(post.pinned ? '고정 해제' : '게시글 고정', () => void togglePin())}
                </div>
                {actionError && <p className="gw31-alert" role="alert">{actionError}</p>}
                {note && <p className="gw31-post__note" role="status">{note}</p>}
                <section className="gw31-post__comments" aria-labelledby="board-comments-heading">
                    <h2 id="board-comments-heading" className="gw31-post__h2 os-serif">댓글 {comments.length}</h2>
                    {comments.length === 0 && <StateLine kind="empty" title="첫 댓글을 남겨보세요." />}
                    {comments.map((comment) => (
                        <article className="gw31-post__comment" key={comment.id}>
                            <Portrait picture={comment.authorPicture ?? null} imageServer={comment.authorImageServer ?? 0} size="icon-28" alt="" />
                            <div className="gw31-post__comment-body">
                                <span className="gw31-post__comment-head"><b>{comment.authorName}</b><time className="os-num" dateTime={comment.createdAt}>{boardDate(comment.createdAt)}</time></span>
                                <p>{comment.content}</p>
                            </div>
                            <span className="gw31-post__comment-actions">
                                {user && !comment.canDelete && action('신고', () => startReport({ kind: 'comment', id: comment.id }))}
                                {comment.canDelete && action('댓글 삭제', () => setConfirm({ kind: 'comment', comment }))}
                            </span>
                        </article>
                    ))}
                    {user
                        ? <CommentForm onSubmit={async (content) => {
                            const created = await createBoardComment(postId, content);
                            update((data) => ({ ...data, comments: [...data.comments, created] }));
                        }} />
                        : <p className="gw31-card__line"><Link className="gw31-link" href={`/login?next=${encodeURIComponent(`/board/posts/${postId}`)}`}>로그인</Link>하면 댓글을 남길 수 있습니다.</p>}
                </section>
            </article>
            {report && <ReportSheet target={report} onClose={() => setReport(null)} onSubmit={sendReport} />}
            <ConfirmDialog
                open={confirm !== null}
                title={confirm?.kind === 'comment' ? '댓글 삭제' : '게시글 삭제'}
                message={confirm?.kind === 'comment' ? '댓글을 지우면 되돌릴 수 없습니다.' : '글을 지우면 목록에서 사라지고 되돌릴 수 없습니다.'}
                confirmLabel="지우기"
                danger
                busy={busy}
                onCancel={() => setConfirm(null)}
                onConfirm={() => void (confirm?.kind === 'comment' ? removeComment(confirm.comment) : removePost())}
            />
        </CommunityShell>
    );
}
