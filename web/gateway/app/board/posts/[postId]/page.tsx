'use client';

import Link from 'next/link';
import { useParams, useRouter } from 'next/navigation';
import React, { useEffect, useState } from 'react';
import BoardAuthor from '@/components/board/BoardAuthor';
import BoardCommentForm from '@/components/board/BoardCommentForm';
import BoardShell from '@/components/board/BoardShell';
import {
  boardCategoryLabel,
  boardDate,
  createBoardComment,
  deleteBoardComment,
  deleteBoardPost,
  fetchBoardPost,
  setBoardPostPinned,
  type BoardComment,
  type BoardPostDetail,
} from '@/lib/board';
import { useAuth } from '@/lib/auth-context';

export default function BoardPostDetail(): React.ReactElement {
  const { postId } = useParams<{ postId: string }>();
  const router = useRouter();
  const { user } = useAuth();
  const [data, setData] = useState<BoardPostDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(null);
    setActionError(null);
    void fetchBoardPost(postId)
      .then((next) => {
        if (active) setData(next);
      })
      .catch((cause) => {
        if (active) setLoadError(cause instanceof Error ? cause.message : '게시글을 불러오지 못했습니다.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [postId]);

  async function submitComment(content: string): Promise<void> {
    const created = await createBoardComment(postId, content);
    setData((current) => current ? { ...current, comments: [...current.comments, created] } : current);
  }

  async function removePost(): Promise<void> {
    if (!data) return;
    setActionError(null);
    setBusyId(data.post.id);
    try {
      await deleteBoardPost(data.post.id);
      router.push('/board');
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : '게시글을 삭제하지 못했습니다.');
    } finally {
      setBusyId(null);
    }
  }

  async function removeComment(comment: BoardComment): Promise<void> {
    if (!data) return;
    setActionError(null);
    setBusyId(comment.id);
    try {
      await deleteBoardComment(data.post.id, comment.id);
      setData((current) => current ? { ...current, comments: current.comments.filter((item) => item.id !== comment.id) } : current);
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : '댓글을 삭제하지 못했습니다.');
    } finally {
      setBusyId(null);
    }
  }

  async function togglePinned(): Promise<void> {
    if (!data) return;
    setActionError(null);
    setBusyId(data.post.id);
    try {
      const post = await setBoardPostPinned(data.post.id, !data.post.pinned);
      setData((current) => current ? { ...current, post } : current);
    } catch (cause) {
      setActionError(cause instanceof Error ? cause.message : '고정 상태를 변경하지 못했습니다.');
    } finally {
      setBusyId(null);
    }
  }

  if (loading) {
    return <BoardShell><div className="board-loading" role="status">게시글을 불러오는 중…</div></BoardShell>;
  }
  if (loadError || !data) {
    return <BoardShell><div className="auth-error" role="alert">{loadError ?? '게시글을 찾을 수 없습니다.'}</div></BoardShell>;
  }

  return (
    <BoardShell>
      <article className="board-post">
        <Link className="board-back-link" href="/board">게시판 목록</Link>
        <header className="board-post-header">
          <div className="board-list-labels">
            <span className={`board-category board-category-${data.post.category.toLowerCase()}`}>{boardCategoryLabel(data.post.category)}</span>
            {data.post.pinned ? <span className="board-pin">고정</span> : null}
          </div>
          <h1>{data.post.title}</h1>
          <div className="board-post-meta">
            <BoardAuthor imageServer={data.post.authorImageServer} name={data.post.authorName} picture={data.post.authorPicture} size={40} />
            <time dateTime={data.post.createdAt}>{boardDate(data.post.createdAt)}</time>
          </div>
        </header>
        <div className="board-post-content" dangerouslySetInnerHTML={{ __html: data.post.contentHtml }} />
        <div className="board-post-actions">
          {actionError ? <p className="field-error" role="alert">{actionError}</p> : null}
          {data.post.canDelete ? <button className="btn-danger" disabled={busyId === data.post.id} onClick={() => void removePost()} type="button">게시글 삭제</button> : null}
          {user?.role === 'ADMIN' ? <button className="btn-ghost" disabled={busyId === data.post.id} onClick={() => void togglePinned()} type="button">{data.post.pinned ? '고정 해제' : '게시글 고정'}</button> : null}
        </div>
      </article>
      <section className="board-comments" aria-labelledby="board-comments-heading">
        <h2 id="board-comments-heading">댓글 {data.comments.length}</h2>
        <div className="board-comment-list">
          {data.comments.length === 0 ? <div className="board-empty">첫 댓글을 남겨보세요.</div> : data.comments.map((comment) => (
            <article className="board-comment" key={comment.id}>
              <div className="board-comment-meta">
                <BoardAuthor imageServer={comment.authorImageServer} name={comment.authorName} picture={comment.authorPicture} size={28} />
                <time dateTime={comment.createdAt}>{boardDate(comment.createdAt)}</time>
              </div>
              <p>{comment.content}</p>
              {comment.canDelete ? <button className="board-text-button" disabled={busyId === comment.id} onClick={() => void removeComment(comment)} type="button">댓글 삭제</button> : null}
            </article>
          ))}
        </div>
        {user ? <BoardCommentForm disabled={false} onSubmit={submitComment} /> : <p className="board-login-note"><Link href={`/login?next=${encodeURIComponent(`/board/posts/${postId}`)}`}>로그인</Link>하면 댓글을 남길 수 있습니다.</p>}
      </section>
    </BoardShell>
  );
}
