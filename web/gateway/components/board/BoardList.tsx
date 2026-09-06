import Link from 'next/link';
import React from 'react';
import { EmptyState, Portrait } from '@opensamguk/ui';
import { boardCategoryLabel, boardDate, type BoardPost } from '@/lib/board';

/** 작성자 표시 — 계정 아이콘 40 + 닉네임 + 대표 장수 배지(설정된 계정만). */
export function BoardAuthorBadge({ post }: { readonly post: BoardPost }) {
  return (
    <span className="board-author board-author--row">
      <Portrait picture={post.authorPicture ?? null} imageServer={post.authorImageServer ?? 0} size="icon-40" alt="" />
      <span className="board-author-text">
        <span>{post.authorName}</span>
        {post.authorGeneralName && (
          <span className="board-server-badge os-chip" title="계정 대표 장수">
            {post.authorGeneralName}{post.authorWorldId != null ? ` · 월드 ${post.authorWorldId}` : ''}
          </span>
        )}
      </span>
    </span>
  );
}

export default function BoardList({ posts }: { readonly posts: readonly BoardPost[] }) {
  if (posts.length === 0) {
    return <EmptyState illustration="posts" title="아직 게시글이 없습니다." className="board-empty" />;
  }
  return (
    <div className="board-list" aria-label="게시글 목록">
      {posts.map((post) => (
        <article className="board-list-item" key={post.id}>
          <BoardAuthorBadge post={post} />
          <div className="board-list-main">
            <div className="board-list-labels">
              <span className={`board-category board-category-${post.category.toLowerCase()}`}>
                {boardCategoryLabel(post.category)}
              </span>
              {post.pinned ? <span className="board-pin">고정</span> : null}
            </div>
            <Link className="board-post-link" href={`/board/posts/${post.id}`}>{post.title}</Link>
            <div className="board-list-meta">
              <time dateTime={post.createdAt}>{boardDate(post.createdAt)}</time>
              {post.viewCount != null && <span className="os-num">조회 {post.viewCount.toLocaleString()}</span>}
              {post.commentCount != null && <span className="os-num">댓글 {post.commentCount.toLocaleString()}</span>}
            </div>
          </div>
        </article>
      ))}
    </div>
  );
}
