'use client';

import Link from 'next/link';
import React, { useEffect, useState } from 'react';
import BoardList from '@/components/board/BoardList';
import BoardPagination from '@/components/board/BoardPagination';
import BoardShell from '@/components/board/BoardShell';
import BoardTabs from '@/components/board/BoardTabs';
import { BoardRequestError, fetchBoardPosts, type BoardCategory, type BoardPostPage } from '@/lib/board';
import { useAuth } from '@/lib/auth-context';

const EMPTY_PAGE: BoardPostPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };

export default function BoardIndex(): React.ReactElement {
  const { user } = useAuth();
  const [category, setCategory] = useState<BoardCategory>('NOTICE');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<BoardPostPage>(EMPTY_PAGE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    void fetchBoardPosts(category, page)
      .then((next) => {
        if (active) setData(next);
      })
      .catch((cause) => {
        if (active) setError(cause instanceof BoardRequestError ? cause.message : '게시판을 불러오지 못했습니다.');
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [category, page]);

  function selectCategory(next: BoardCategory): void {
    setCategory(next);
    setPage(0);
  }

  return (
    <BoardShell>
      <section className="board-heading">
        <div>
          <p className="board-eyebrow">OPEN SAMGUK COMMUNITY</p>
          <h1>커뮤니티 게시판</h1>
          <p>공지, 자유로운 이야기, 건의를 <span className="board-heading-subject">한곳에서</span> 확인하세요.</p>
        </div>
        {user ? <Link className="btn-primary board-heading-action" href="/board/write">글쓰기</Link> : <Link className="btn-ghost board-heading-action" href="/login?next=%2Fboard%2Fwrite">로그인 후 글쓰기</Link>}
      </section>
      <BoardTabs active={category} onSelect={selectCategory} />
      {loading ? <div className="board-loading" role="status">게시글을 불러오는 중…</div> : null}
      {error ? <div className="auth-error" role="alert">{error}</div> : null}
      {!loading && !error ? <BoardList posts={data.content} /> : null}
      {!loading && !error ? <BoardPagination onPage={setPage} page={data.page} totalPages={data.totalPages} /> : null}
    </BoardShell>
  );
}
