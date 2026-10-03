import Link from 'next/link';
import { Button, Chip, Icon, Portrait } from '@opensamguk/ui';
import { boardCategoryLabel, boardDate, type BoardPost } from '@/lib/board';

/**
 * 대표 장수 칩(설계서 §3.1 23–31) — 뜻을 보이는 글자로 적는다(옛 배지는 뜻이 `title`에만 있었다).
 * 서버 이름 · 기수(K5-15)가 오기 전이라 내부 월드 번호는 보이지 않는다.
 */
export function RepresentativeChip({ name }: { readonly name?: string | null }) {
    return name ? <Chip className="gw31-board__rep"><Icon name="retinue" size={12} />대표 장수 · {name}</Chip> : null;
}

/** 목록 한 줄 — 데스크톱은 표 행(초상 40 · 제목 · 시각 160), 모바일은 카드. 줄 전체가 제목 링크로 눌린다. */
function PostRow({ post }: { readonly post: BoardPost }) {
    const counts = [
        post.viewCount != null ? `조회 ${post.viewCount.toLocaleString()}` : null,
        post.commentCount != null ? `댓글 ${post.commentCount.toLocaleString()}` : null,
    ].filter(Boolean).join(' · ');
    return (
        <article className={`gw31-board__row${post.pinned ? ' is-pinned' : ''}`}>
            <span className="gw31-board__face">
                <Portrait picture={post.authorPicture ?? null} imageServer={post.authorImageServer ?? 0} size="icon-40" alt="" />
            </span>
            <span className="gw31-board__cell">
                <span className="gw31-board__labels">
                    <Chip>{boardCategoryLabel(post.category)}</Chip>
                    {post.pinned && <Chip tone="bronze">고정</Chip>}
                    <Link className="gw31-board__title" href={`/board/posts/${post.id}`}>{post.title}</Link>
                </span>
                <span className="gw31-board__by">
                    <span>{post.authorName}</span>
                    <RepresentativeChip name={post.authorGeneralName} />
                </span>
            </span>
            <span className="gw31-board__side">
                <time dateTime={post.createdAt} className="os-num">{boardDate(post.createdAt)}</time>
                {counts && <span className="os-num">{counts}</span>}
            </span>
        </article>
    );
}

export function CommunityList({ posts }: { readonly posts: readonly BoardPost[] }) {
    return (
        <div className="gw31-board__list" aria-label="게시글 목록">
            {posts.map((post) => <PostRow key={post.id} post={post} />)}
        </div>
    );
}

/** 쪽 넘김(설계서 §3.1 33–35) — 끝에 닿은 단추는 사유와 함께 잠근다. 한 쪽뿐이면 그리지 않는다. */
export function CommunityPager({ page, totalPages, onPage }: {
    readonly page: number;
    readonly totalPages: number;
    readonly onPage: (page: number) => void;
}) {
    if (totalPages <= 1) return null;
    const last = page + 1 >= totalPages;
    return (
        <nav aria-label="게시글 쪽" className="gw31-board__pager">
            {page === 0 ? <Button disabled reason="첫 쪽입니다">이전</Button> : <Button onClick={() => onPage(page - 1)}>이전</Button>}
            <span className="os-num" aria-current="page">{page + 1} / {totalPages}</span>
            {last ? <Button disabled reason="마지막 쪽입니다">다음</Button> : <Button onClick={() => onPage(page + 1)}>다음</Button>}
        </nav>
    );
}
