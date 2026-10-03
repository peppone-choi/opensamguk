'use client';

import Link from 'next/link';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, Icon, Seg } from '@opensamguk/ui';
import StateLine from '@/components/status/StateLine';
import { useAuth } from '@/lib/auth-context';
import {
    BOARD_CATEGORIES,
    BoardRequestError,
    fetchBoardCategoryCounts,
    fetchBoardPosts,
    type BoardCategory,
    type BoardCategoryCount,
    type BoardPostPage,
    type BoardSort,
} from '@/lib/board';
import CommunityRail from './CommunityRail';
import CommunityShell from './CommunityShell';
import { CommunityList, CommunityPager } from './CommunityList';

const ALL = 'ALL';
type CategoryKey = BoardCategory | typeof ALL;
const PAGE_SIZE = 20;
const WRITE_LOGIN = '/login?next=%2Fboard%2Fwrite';

type List = { kind: 'loading' } | { kind: 'error'; message: string } | { kind: 'ready'; page: BoardPostPage };

/**
 * P-G06 커뮤니티 목록(설계서 §3.1, 보드 V31K5Board · MBoard). 게시판 목록은 G-01(게시판 정의, C8) 전이라
 * 코드 고정 6개로 그린다. 첫 진입은 「공지」(설계서 6–13). 목록 쿼리 계약(`category=&page=&size=` + 기본값이 아닐 때만 sort · q)은 그대로다.
 */
export default function CommunityScreen() {
    const { user } = useAuth();
    const [category, setCategory] = useState<CategoryKey>('NOTICE');
    const [sort, setSort] = useState<BoardSort>('latest');
    const [draft, setDraft] = useState('');
    const [query, setQuery] = useState('');
    const [searchOpen, setSearchOpen] = useState(false);
    const [pageIndex, setPageIndex] = useState(0);
    const [list, setList] = useState<List>({ kind: 'loading' });
    const [counts, setCounts] = useState<readonly BoardCategoryCount[] | undefined>(undefined);

    const load = useCallback(() => {
        let active = true;
        setList({ kind: 'loading' });
        fetchBoardPosts(category === ALL ? null : category, pageIndex, PAGE_SIZE, { sort, q: query })
            .then((page) => { if (active) setList({ kind: 'ready', page }); })
            .catch((cause) => { if (active) setList({ kind: 'error', message: cause instanceof BoardRequestError ? cause.message : '게시판을 불러오지 못했습니다.' }); });
        return () => { active = false; };
    }, [category, pageIndex, sort, query]);
    useEffect(load, [load]);

    // 분류별 글 수 — 실패해도 목록을 막지 않는다(수 없이 이름만, 지어내지 않음).
    useEffect(() => {
        let active = true;
        fetchBoardCategoryCounts().then((next) => { if (active) setCounts(next); }).catch(() => {});
        return () => { active = false; };
    }, []);

    const countOf = (value: BoardCategory) => counts?.find((item) => item.category === value)?.count;
    const total = counts?.reduce((sum, item) => sum + item.count, 0);
    const categories = [
        { value: ALL as CategoryKey, label: '전체', count: total },
        ...BOARD_CATEGORIES.map((c) => ({ value: c.value as CategoryKey, label: c.label, count: countOf(c.value) })),
    ];
    const sorts: { value: BoardSort; label: string }[] = [{ value: 'latest', label: '최신' }, { value: 'popular', label: '인기' }];
    if (user) sorts.push({ value: 'mine', label: '내 글' });

    const pick = <T,>(set: (value: T) => void) => (value: T) => { set(value); setPageIndex(0); };
    const search = (event: FormEvent) => {
        event.preventDefault();
        setQuery(draft.trim());
        setPageIndex(0);
    };

    return (
        <CommunityShell>
            <div className="gw31-board__main">
                <div className="gw31-board__head">
                    <div className="gw31-board__intro">
                        <p className="gw31-board__eyebrow">오픈삼국 커뮤니티</p>
                        <h1 className="gw31-board__h1 os-serif">커뮤니티 게시판</h1>
                        <p className="gw31-card__line">서버 밖, 계정 단위 공간입니다. 세력 회의실 · 기밀실과 분리됩니다.</p>
                    </div>
                    {user ? (
                        <Link className="os-button os-button--primary gw31-board__write" href="/board/write" aria-label="글쓰기">
                            <Icon name="copy" size={20} /><span className="gw31-board__write-text">글쓰기</span>
                        </Link>
                    ) : (
                        <Link className="os-button os-button--ghost gw31-board__write gw31-board__write--guest" href={WRITE_LOGIN}>로그인 후 글쓰기</Link>
                    )}
                </div>
                <Seg label="게시판" options={categories} value={category} onChange={pick(setCategory)} scroll className="gw31-board__cats" />
                <div className="gw31-board__tools">
                    <div className="gw31-board__sort">
                        <Seg label="정렬" options={sorts} value={sort} onChange={pick(setSort)} />
                        {!user && <Button disabled reason="로그인하면 볼 수 있습니다">내 글</Button>}
                    </div>
                    <button
                        type="button"
                        className="os-button os-button--ghost gw31-board__search-toggle"
                        aria-expanded={searchOpen || Boolean(query)}
                        aria-controls="board-search"
                        aria-label="검색"
                        onClick={() => setSearchOpen((v) => !v)}
                    >
                        <Icon name="search" size={20} />
                    </button>
                    <form id="board-search" className={`gw31-board__search${searchOpen || query ? ' is-open' : ''}`} role="search" onSubmit={search}>
                        <input type="search" className="os-input" aria-label="검색어" placeholder="검색 — 제목 · 내용" value={draft} onChange={(e) => setDraft(e.target.value)} maxLength={100} />
                        <Button type="submit">검색</Button>
                    </form>
                </div>
                {query && list.kind === 'ready' && (
                    <p className="gw31-card__line" role="status">「{query}」 검색 결과 <span className="os-num">{list.page.totalElements.toLocaleString()}</span>건</p>
                )}
                <section className="os-panel os-panel--static gw31-board__panel" aria-label="게시글">
                    {list.kind === 'loading' && <StateLine kind="loading" title="게시글을 불러오는 중" />}
                    {list.kind === 'error' && <StateLine kind="error" title="게시판을 불러오지 못했습니다" body={list.message} onRetry={load} />}
                    {list.kind === 'ready' && list.page.content.length === 0 && (
                        <StateLine kind="empty" title={query ? '검색 결과가 없습니다.' : '아직 게시글이 없습니다.'} />
                    )}
                    {list.kind === 'ready' && list.page.content.length > 0 && (
                        <>
                            <CommunityList posts={list.page.content} />
                            <CommunityPager page={list.page.page} totalPages={list.page.totalPages} onPage={setPageIndex} />
                        </>
                    )}
                </section>
            </div>
            <CommunityRail />
        </CommunityShell>
    );
}
