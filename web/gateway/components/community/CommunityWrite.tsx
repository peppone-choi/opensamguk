'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useState, type FormEvent } from 'react';
import { Button, ConfirmDialog, Seg } from '@opensamguk/ui';
import BoardRichTextEditor from '@/components/board/BoardRichTextEditor';
import StateLine from '@/components/status/StateLine';
import { useAuth } from '@/lib/auth-context';
import { BOARD_CATEGORIES, BoardRequestError, createBoardPost, fetchBoardPost, updateBoardPost, type BoardCategory, type BoardPost } from '@/lib/board';
import CommunityShell from './CommunityShell';

const MAX_CONTENT_LENGTH = 10000;
const BUSY = '처리 중입니다';

/** 보이는 글자가 하나도 없는 서식 HTML(빈 문단 · 공백만) — 서버가 비었다고 거절하기 전에 막는다. */
export function richTextIsBlank(html: string): boolean {
    return html.replace(/<[^>]*>/g, '').replace(/&nbsp;|&#160;|&#xA0;/gi, ' ').trim() === '';
}

type Draft = { category: BoardCategory; title: string; content: string };
type Source = { kind: 'new' } | { kind: 'loading' } | { kind: 'missing' } | { kind: 'denied' } | { kind: 'error'; message: string } | { kind: 'edit'; post: BoardPost };

/** 글쓰기 · 고치기 폼(설계서 §3.3 8–17). 게시판은 G-01 전이라 고정 6개 — 공지는 운영자에게만 보인다. */
function WriteForm({ initial, admin, editId, onDone, onCancel }: {
    readonly initial: Draft;
    readonly admin: boolean;
    readonly editId: number | null;
    readonly onDone: (post: BoardPost) => void;
    readonly onCancel: (dirty: boolean) => void;
}) {
    const [draft, setDraft] = useState<Draft>(initial);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState<string | null>(null);
    const dirty = draft.title !== initial.title || draft.content !== initial.content || draft.category !== initial.category;
    const title = draft.title.trim();
    const content = draft.content.trim();
    const block = busy ? BUSY
        : !title || richTextIsBlank(content) ? '제목과 내용을 모두 입력해주세요'
            : content.length > MAX_CONTENT_LENGTH ? `내용은 ${MAX_CONTENT_LENGTH}자 이내로 입력해주세요` : null;
    const submit = async (event: FormEvent) => {
        event.preventDefault();
        if (block) return;
        setBusy(true);
        setError(null);
        try {
            const input = { category: draft.category, title, content };
            onDone(editId === null ? await createBoardPost(input) : await updateBoardPost(editId, input));
        } catch (cause) {
            setError(cause instanceof Error ? cause.message : '게시글을 등록하지 못했습니다.');
            setBusy(false);
        }
    };
    const label = editId === null ? (busy ? '등록 중…' : '등록') : (busy ? '저장 중…' : '저장');
    return (
        <form className="gw31-form gw31-write" onSubmit={submit}>
            <div className="gw31-field">
                <span className="gw31-field__label">게시판</span>
                <Seg
                    label="게시판"
                    options={BOARD_CATEGORIES.filter((c) => admin || c.value !== 'NOTICE').map((c) => ({ value: c.value, label: c.label }))}
                    value={draft.category}
                    onChange={(category) => { if (!busy) setDraft((d) => ({ ...d, category })); }}
                    scroll
                />
                <span className="gw31-field__help">공지는 운영자만 쓸 수 있습니다.</span>
            </div>
            <div className="gw31-field">
                <label htmlFor="board-title">제목</label>
                <input id="board-title" maxLength={120} value={draft.title} readOnly={busy} onChange={(e) => setDraft((d) => ({ ...d, title: e.target.value }))} aria-describedby="board-title-help" />
                <span className="gw31-field__help" id="board-title-help">120자까지</span>
            </div>
            <div className="gw31-field">
                <span className="gw31-field__label">내용</span>
                <BoardRichTextEditor ariaLabel="내용" disabled={busy} value={draft.content} onChange={(html) => setDraft((d) => ({ ...d, content: html }))} />
                <span className="gw31-field__help gw31-write__count os-num" aria-live="polite">{draft.content.length} / {MAX_CONTENT_LENGTH}</span>
            </div>
            {error && <p className="gw31-alert" role="alert">{error}</p>}
            <div className="gw31-write__actions">
                {busy ? <Button disabled reason={BUSY}>취소</Button> : <Button onClick={() => onCancel(dirty)}>취소</Button>}
                {block ? <Button variant="primary" disabled reason={block}>{label}</Button> : <Button variant="primary" type="submit">{label}</Button>}
            </div>
        </form>
    );
}

/**
 * P-G08 커뮤니티 글쓰기(설계서 §3.3, 보드 V31K5BoardWrite · MBoardWrite). `editId` 가 있으면 수정 모드 —
 * 글을 읽어 작성자 · 운영자(canDelete 와 같은 서버 조건)일 때만 고친다. 쓰던 글을 버릴 때는 확인을 받는다.
 */
export default function CommunityWrite({ editId }: { readonly editId: string | null }) {
    const router = useRouter();
    const { user, loading } = useAuth();
    const [source, setSource] = useState<Source>(editId ? { kind: 'loading' } : { kind: 'new' });
    const [leaving, setLeaving] = useState(false);
    const back = editId ? `/board/posts/${encodeURIComponent(editId)}` : '/board';

    const open = useCallback(() => {
        if (!editId) return undefined;
        let active = true;
        setSource({ kind: 'loading' });
        fetchBoardPost(editId)
            .then(({ post }) => { if (active) setSource(post.canDelete ? { kind: 'edit', post } : { kind: 'denied' }); })
            .catch((cause) => {
                if (!active) return;
                if (cause instanceof BoardRequestError && cause.status === 404) setSource({ kind: 'missing' });
                else setSource({ kind: 'error', message: cause instanceof Error ? cause.message : '게시글을 불러오지 못했습니다.' });
            });
        return () => { active = false; };
    }, [editId]);
    useEffect(() => { if (user) return open(); return undefined; }, [user, open]);

    const heading = (
        <div className="gw31-board__intro">
            <p className="gw31-board__eyebrow">{editId ? '글 고치기' : '새 글'}</p>
            <h1 className="gw31-board__h1 os-serif">{editId ? '게시글 수정' : '게시글 작성'}</h1>
            <p className="gw31-card__line">굵게 · 기울임 · 취소선 같은 기본 서식을 쓸 수 있습니다.</p>
        </div>
    );
    const login = `/login?next=${encodeURIComponent(editId ? `/board/write?edit=${editId}` : '/board/write')}`;

    let body;
    if (loading) body = <StateLine kind="loading" title="로그인 상태를 확인하는 중" />;
    else if (!user) body = <p className="gw31-card__line">글을 작성하려면 로그인이 필요합니다. <Link className="gw31-link" href={login}>로그인</Link></p>;
    else if (source.kind === 'loading') body = <StateLine kind="loading" title="게시글을 불러오는 중" />;
    else if (source.kind === 'missing') body = <StateLine kind="empty" title="게시글을 찾을 수 없습니다" body="지워졌거나 주소가 잘못되었습니다." />;
    else if (source.kind === 'denied') body = <StateLine kind="empty" title="작성자 또는 관리자만 변경할 수 있습니다." />;
    else if (source.kind === 'error') body = <StateLine kind="error" title="게시글을 불러오지 못했습니다" body={source.message} onRetry={open} />;
    else {
        const initial: Draft = source.kind === 'edit'
            ? { category: source.post.category, title: source.post.title, content: source.post.contentHtml }
            : { category: 'FREE', title: '', content: '' };
        body = (
            <WriteForm
                initial={initial}
                admin={user.role === 'ADMIN'}
                editId={source.kind === 'edit' ? source.post.id : null}
                onDone={(post) => router.push(`/board/posts/${post.id}`)}
                onCancel={(dirty) => (dirty ? setLeaving(true) : router.push(back))}
            />
        );
    }

    return (
        <CommunityShell>
            <div className="gw31-post">
                {heading}
                {body}
            </div>
            <ConfirmDialog
                open={leaving}
                title="쓰던 글 버리기"
                message="지금까지 쓴 내용이 사라집니다."
                confirmLabel="버리기"
                cancelLabel="계속 쓰기"
                danger
                onCancel={() => setLeaving(false)}
                onConfirm={() => router.push(back)}
            />
        </CommunityShell>
    );
}
