import Link from 'next/link';
import React from 'react';
import { boardCategoryLabel, boardDate, type BoardPost } from '@/lib/board';

export default function BoardList({ posts }: { readonly posts: readonly BoardPost[] }) {
  if (posts.length === 0) {
    return <div className="board-empty">아직 게시글이 없습니다.</div>;
  }

  return (
    <div className="board-list" aria-label="게시글 목록">
      {posts.map((post) => (
        <article className="board-list-item" key={post.id}>
          <div className="board-list-main">
            <div className="board-list-labels">
              <span className={`board-category board-category-${post.category.toLowerCase()}`}>
                {boardCategoryLabel(post.category)}
              </span>
              {post.pinned ? <span className="board-pin">고정</span> : null}
            </div>
            <Link className="board-post-link" href={`/board/posts/${post.id}`}>{post.title}</Link>
          </div>
          <div className="board-list-meta">
            <span>{post.authorName}</span>
            <time dateTime={post.createdAt}>{boardDate(post.createdAt)}</time>
          </div>
        </article>
      ))}
    </div>
  );
}
