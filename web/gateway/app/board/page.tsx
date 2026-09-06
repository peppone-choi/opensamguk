'use client';
// 커뮤니티(ADR-LITE-049 13) — 서버 밖·계정 단위 공간. 분류 6 + 카운트, 최신/인기/내 글, 검색, 글쓰기,
// 목록 행(아이콘 40 + 서버 배지 + 조회·댓글), 우측 내 계정(대표 장수)·인기 글·세 공간의 경계.
// 목록 쿼리 계약(`category=&page=&size=`)은 그대로다; sort/q 는 기본값이 아닐 때만 붙는다.
import Link from 'next/link';
import React, { useEffect, useState } from 'react';
import { Panel, PillTabs, Portrait, SectionHeader } from '@opensamguk/ui';
import BoardList from '@/components/board/BoardList';
import BoardPagination from '@/components/board/BoardPagination';
import BoardShell from '@/components/board/BoardShell';
import BoardTabs from '@/components/board/BoardTabs';
import {
  BoardRequestError,
  fetchBoardCategoryCounts,
  fetchBoardPosts,
  type BoardCategory,
  type BoardCategoryCount,
  type BoardPost,
  type BoardPostPage,
  type BoardSort,
} from '@/lib/board';
import { useAuth } from '@/lib/auth-context';

const EMPTY_PAGE: BoardPostPage = { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 };
const SORT_TABS: { key: BoardSort; label: string }[] = [
  { key: 'latest', label: '최신' }, { key: 'popular', label: '인기' }, { key: 'mine', label: '내 글' },
];

export default function BoardIndex(): React.ReactElement {
  const { user } = useAuth();
  const [category, setCategory] = useState<BoardCategory | null>('NOTICE');
  const [sort, setSort] = useState<BoardSort>('latest');
  const [queryDraft, setQueryDraft] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<BoardPostPage>(EMPTY_PAGE);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [counts, setCounts] = useState<readonly BoardCategoryCount[] | undefined>(undefined);
  const [popular, setPopular] = useState<readonly BoardPost[] | null>(null);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    void fetchBoardPosts(category, page, 20, { sort, q: query })
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
  }, [category, page, sort, query]);

  // 우측 레일·분류 카운트 — 실패해도 목록을 막지 않는다(카운트 없이 라벨만).
  useEffect(() => {
    let active = true;
    void fetchBoardCategoryCounts().then((next) => { if (active) setCounts(next); }).catch(() => {});
    void fetchBoardPosts(null, 0, 3, { sort: 'popular' })
      .then((next) => { if (active) setPopular(next.content); })
      .catch(() => { if (active) setPopular([]); });
    return () => { active = false; };
  }, []);

  function selectCategory(next: BoardCategory | null): void {
    setCategory(next);
    setPage(0);
  }
  function selectSort(next: BoardSort): void {
    setSort(next);
    setPage(0);
  }
  function submitSearch(event: React.FormEvent): void {
    event.preventDefault();
    setQuery(queryDraft.trim());
    setPage(0);
  }

  return (
    <BoardShell>
      <section className="board-heading">
        <div>
          <p className="board-eyebrow">OPEN SAMGUK COMMUNITY</p>
          <h1>커뮤니티 게시판</h1>
          <p>서버 밖, 계정 단위 공간입니다. 국가 회의실·기밀실과 분리됩니다.</p>
        </div>
        {user ? <Link className="btn-primary board-heading-action" href="/board/write">글쓰기</Link> : <Link className="btn-ghost board-heading-action" href="/login?next=%2Fboard%2Fwrite">로그인 후 글쓰기</Link>}
      </section>
      <div className="board-layout">
        <div className="board-column">
          <BoardTabs active={category} onSelect={selectCategory} counts={counts} showAll />
          <div className="board-toolbar">
            <PillTabs<BoardSort>
              label="정렬"
              value={sort}
              onChange={selectSort}
              tabs={SORT_TABS.map((t) => (t.key === 'mine' && !user ? { ...t, label: '내 글' } : t))}
            />
            <form className="board-search" role="search" onSubmit={submitSearch}>
              <input
                type="search"
                aria-label="검색"
                placeholder="검색"
                value={queryDraft}
                onChange={(event) => setQueryDraft(event.target.value)}
                maxLength={100}
              />
              <button type="submit" className="os-button os-button--sm">검색</button>
            </form>
          </div>
          {sort === 'mine' && !user ? <div className="board-login-note">내 글은 로그인 뒤 볼 수 있습니다.</div> : null}
          {query ? <p className="board-query-note">「{query}」 검색 결과 <span className="os-num">{data.totalElements.toLocaleString()}</span>건</p> : null}
          {loading ? <div className="board-loading" role="status">게시글을 불러오는 중…</div> : null}
          {error ? <div className="auth-error" role="alert">{error}</div> : null}
          {!loading && !error ? <BoardList posts={data.content} /> : null}
          {!loading && !error ? <BoardPagination onPage={setPage} page={data.page} totalPages={data.totalPages} /> : null}
        </div>
        <aside className="board-rail" aria-label="커뮤니티 안내">
          <Panel className="board-rail-panel">
            <SectionHeader title="내 계정" />
            {user ? (
              <div className="board-rail-account">
                <Portrait picture={user.picture ?? null} imageServer={user.imageServer ?? 0} size="icon-48" alt="" />
                <div>
                  <b>{user.nickname ?? user.username}</b>
                  <p className="board-rail-muted">얼굴은 계정 전콘입니다. 대표 장수는 계정 설정에서 정합니다.</p>
                  <Link className="os-button os-button--sm os-button--ghost" href="/account#representative">대표 장수 변경</Link>
                </div>
              </div>
            ) : (
              <p className="board-rail-muted board-rail-pad">로그인하면 글쓰기·신고·대표 장수 설정을 쓸 수 있습니다.</p>
            )}
          </Panel>
          <Panel className="board-rail-panel">
            <SectionHeader title="인기 글" sub="최근 7일" tone="rust" />
            {popular === null ? <p className="board-rail-muted board-rail-pad">불러오는 중…</p>
              : popular.length === 0 ? <p className="board-rail-muted board-rail-pad">최근 7일 인기 글이 없습니다.</p>
              : (
                <ol className="board-rail-popular">
                  {popular.map((post) => (
                    <li key={post.id}>
                      <Link href={`/board/posts/${post.id}`}>{post.title}</Link>
                      <span className="os-num">{post.commentCount ?? 0}</span>
                    </li>
                  ))}
                </ol>
              )}
          </Panel>
          <Panel className="board-rail-panel">
            <SectionHeader title="세 공간의 경계" tone="info" />
            <ul className="board-rail-bounds">
              <li><b>커뮤니티</b> = 서버 밖, 모든 계정</li>
              <li><b>회의실</b> = 게임 안, 국가 소속 장수</li>
              <li><b>기밀실</b> = 게임 안, 수뇌부만 · 열람 기록 남음</li>
            </ul>
          </Panel>
        </aside>
      </div>
    </BoardShell>
  );
}
