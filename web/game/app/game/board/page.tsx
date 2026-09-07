'use client';
// 회의실 / 기밀실 (Board) — F4 page 4 · ADR-LITE-049 14 아트보드.
// 그랜드 트루스: legacy hwe/ts/PageBoard.vue + components/BoardArticle.vue + components/BoardComment.vue +
//   hwe/j_board_get_articles.php + j_board_article_add.php + j_board_comment_add.php.
//
// 두 개의 방(회의실 = 국가 소속 전원, 기밀실 = 수뇌부 · 열람 기록 · 적갈 프레임)을 페이지 내에서 토글한다.
// 토글은 api.board(secret) → GET /api/board?secret= 를 구동한다. 제목·라벨은 그대로 회의실 / 기밀실.
// 글 종류(kind: 일반/표결/작전/공지)는 V53 열 — 탭은 클라이언트 필터일 뿐 API 는 하나다.
//
// Mutation 은 기존 CommandModal 경로(pinnedCommand + extraArgs, 인자 폼 없음):
//  - 글쓰기 (boardArticle): { isSecret, title, text, kind, voteId? } — 엔진 BoardHandler 가 모든 guard 재검증.
//  - 댓글 (boardComment): { articleNo, text }.
//  - 표결 (voteCast): { voteId, selection:[index] } — 표결 글에 붙은 vote_poll 로 바로 표를 던진다.
//  - 열람 기록 (boardRead): 기밀실 글을 처음 보면 세션당 한 번 인테이크(202 ≠ 성공 — 결과를 기다려 반영).
// 호출 장수 id 는 응답의 myGeneralId(없으면 front-info)에서 가져온다.
//
// 권한 gate 는 INFO 로 렌더된다(고정 규칙 4). read 는 result=true 를 유지하고 기밀 방 차단을 blockedReason 으로 신호한다.
// EMPTY-SAFE: articles [] → '게시물이 없습니다.'
import { Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import { Chip, Flag, Panel, PillTabs, Portrait, PortraitStack, SectionHeader, type ChipTone, EmptyState } from '@opensamguk/ui';
import Shell from '../../../components/Shell';
import PageHead from '../../../components/PageHead';
import { COMMUNITY_HREF } from '../../../components/DeptNav';
import CommandModal from '../../../components/CommandModal';
import { RichTextEditor } from '../../../components/RichTextEditor';
import { SafeHtml } from '../../../components/SafeHtml';
import { api } from '../../../lib/api';
import { submitCommandAndAwaitResult } from '../../../lib/commandSubmit';
import type {
    BoardArticle, BoardComment, BoardKind, BoardPerson, BoardResponse, BoardVoteSummary, FrontInfoResponse, VoteInfo,
} from '../../../lib/types';
import { isArticleBodyBlank } from './articleBody';
import { useTurnRefresh } from '../../../hooks/useTurnRefresh';
import type { OperationsResponse } from '../../../types/game';

// 하나의 열린 board CommandModal spec. argType은 항상 null (args는 extraArgs에 실린다).
type BoardModalSpec = { command: string; label: string; extraArgs?: Record<string, unknown> };
type KindTab = 'all' | BoardKind;

const KIND_LABEL: Record<BoardKind, string> = { general: '일반', vote: '표결', operation: '작전', notice: '공지' };
const KIND_TONE: Record<BoardKind, ChipTone> = { general: 'neutral', vote: 'info', operation: 'moss', notice: 'bronze' };
const KIND_TABS: { key: KindTab; label: string }[] = [
    { key: 'all', label: '전체' }, { key: 'vote', label: '표결' }, { key: 'operation', label: '작전' }, { key: 'notice', label: '공지' },
];

// legacy BoardArticle.vue / BoardComment.vue는 `date.slice(5, 16)` → MM-DD HH:MM 로 렌더한다.
function shortDate(date: string): string {
    return (date ?? '').slice(5, 16).replace('T', ' ');
}
function kindOf(a: BoardArticle): BoardKind {
    return a.kind ?? 'general';
}

function PersonIcon({ person, size }: { person: BoardPerson; size: 'icon-24' | 'icon-28' | 'icon-40' }) {
    return <Portrait picture={person.picture} imageServer={person.imageServer} size={size} alt={person.name} title={person.name} />;
}

function CommentRow({ comment }: { comment: BoardComment }) {
    return (
        <div className="council-comment">
            <Portrait picture={comment.authorPicture ?? null} imageServer={comment.authorImageServer ?? 0} size="icon-28" alt="" />
            <div className="council-comment__body">
                <span className="council-comment__who">{comment.authorName}</span>
                <span className="council-comment__text"><SafeHtml html={comment.text} /></span>
            </div>
            <span className="council-comment__when os-num">{shortDate(comment.date)}</span>
        </div>
    );
}

/** 표결 카드 — 선택지별 표 수 + 표결자 스택(공개 표결). 표는 voteCast 인테이크로 던진다. */
function VoteCard({ vote, canVote, onVote }: { vote: BoardVoteSummary; canVote: boolean; onVote: (index: number) => void }) {
    const unvoted = Math.max(0, vote.eligibleCount - vote.voterCount);
    return (
        <div className="council-vote" aria-label={`표결 ${vote.title}`}>
            <div className="council-vote__head">
                <span className="council-vote__title">{vote.title}</span>
                <span className="council-vote__meta os-num">
                    {vote.options.map((o) => `${o.text} ${o.count}`).join(' · ')} · 미표 {unvoted}
                    {vote.closed ? ' · 마감' : vote.endDate ? ` · 마감 ${shortDate(vote.endDate)}` : ''}
                </span>
            </div>
            <div className="council-vote__options">
                {vote.options.map((o) => {
                    const mine = vote.myVote?.includes(o.index) ?? false;
                    return (
                        <div key={o.index} className={`council-vote__option${mine ? ' is-mine' : ''}`}>
                            <div className="council-vote__option-head">
                                <b>{o.text}</b>
                                <span className="os-num">{o.count}</span>
                            </div>
                            {o.voters.length > 0 && (
                                <PortraitStack label={`${o.text} 표결자`}>
                                    {o.voters.map((p) => <PersonIcon key={p.generalId} person={p} size="icon-24" />)}
                                </PortraitStack>
                            )}
                            {canVote && !vote.closed && (
                                <button type="button" className="os-button os-button--sm" onClick={() => onVote(o.index)}>
                                    {o.text}{mine ? ' (내 표)' : ''}
                                </button>
                            )}
                        </div>
                    );
                })}
            </div>
        </div>
    );
}

function ArticleCard({
    article,
    secret,
    canComment,
    canVote,
    commentDraft,
    setCommentDraft,
    openModal,
}: {
    article: BoardArticle;
    secret: boolean;
    canComment: boolean;
    canVote: boolean;
    commentDraft: string;
    setCommentDraft: (no: number, value: string) => void;
    openModal: (spec: BoardModalSpec) => void;
}) {
    const kind = kindOf(article);
    return (
        <Panel className="record-panel council-article" frame={secret ? 'rust' : 'none'} aria-label={article.title || `${article.authorName}의 글`}>
            {/* 헤더: 작성자 아이콘 40 · 이름 · 직책 · 날짜 · 종류 칩 (legacy 헤더 행 [작성자 | 날짜]) */}
            <div className="council-article__head">
                <Portrait picture={article.authorPicture ?? null} imageServer={article.authorImageServer ?? 0} size="icon-40" alt="" />
                <div className="council-article__who">
                    <b>{article.authorName}</b>
                    {article.authorOfficerLevelText && <span className="council-article__rank">{article.authorOfficerLevelText}</span>}
                    <span className="council-article__when os-num">· {shortDate(article.date)}</span>
                </div>
                <Chip tone={KIND_TONE[kind]}>{KIND_LABEL[kind]}</Chip>
            </div>
            {article.title && <div className="council-article__title">{article.title}</div>}
            <div className="council-article__body">
                <SafeHtml html={article.contentHtml} />
            </div>
            {article.vote && (
                <VoteCard
                    vote={article.vote}
                    canVote={canVote}
                    onVote={(index) => openModal({
                        command: 'voteCast',
                        label: `표결 · ${article.vote?.title ?? ''}`,
                        extraArgs: { voteId: article.vote?.voteId, selection: [index] },
                    })}
                />
            )}
            {/* 기밀실 열람 기록 — 읽은 사람 n / 수뇌부 정원 m */}
            {article.readers && (
                <div className="council-article__readers">
                    <span className="os-num">열람 {article.readers.read.length}/{article.readers.total}</span>
                    {article.readers.read.length > 0 && (
                        <PortraitStack label="열람한 수뇌부">
                            {article.readers.read.map((p) => <PersonIcon key={p.generalId} person={p} size="icon-24" />)}
                        </PortraitStack>
                    )}
                </div>
            )}
            {/* 댓글 */}
            {(article.comments?.length ?? 0) > 0 && (
                <div className="council-article__comments">
                    {(article.comments ?? []).map((c) => (
                        <CommentRow key={c.id} comment={c} />
                    ))}
                </div>
            )}
            {/* 댓글 달기 footer (boardComment) — text는 input을 통해 → extraArgs.text, articleNo = article.id. */}
            {canComment && (
                <div className="council-article__reply">
                    <span className="council-article__reply-label">댓글 달기</span>
                    <input
                        type="text"
                        placeholder="새 댓글 내용"
                        value={commentDraft}
                        onChange={(e) => setCommentDraft(article.id, e.target.value)}
                        maxLength={250}
                    />
                    <button
                        type="button"
                        className="os-button os-button--sm"
                        disabled={commentDraft.trim().length === 0}
                        onClick={() =>
                            openModal({
                                command: 'boardComment',
                                label: '댓글',
                                extraArgs: { articleNo: article.id, text: commentDraft },
                            })
                        }
                    >
                        등록
                    </button>
                </div>
            )}
        </Panel>
    );
}

function BoardContent() {
    const searchParams = useSearchParams();
    // false = 회의실, true = 기밀실 (legacy isSecretBoard). 페이지 내 토글이 api.board(secret)를 구동한다.
    const [secret, setSecret] = useState(() => searchParams.get('secret') === '1');
    const [kindTab, setKindTab] = useState<KindTab>('all');
    const [data, setData] = useState<BoardResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');
    // 행위 주체 장수 자신의 id — 응답의 myGeneralId 가 우선, 없으면 front-info(구 응답 호환). 헤더 국가·직책도 front-info.
    const [front, setFront] = useState<FrontInfoResponse | null>(null);
    const [frontGeneralId, setFrontGeneralId] = useState(0);
    // 글쓰기 draft + article별 댓글 draft (article.id로 keying). reserve 성공 시 비워진다.
    const [articleTitle, setArticleTitle] = useState('');
    const [articleText, setArticleText] = useState('');
    const [articleKind, setArticleKind] = useState<BoardKind>('general');
    const [voteOptions, setVoteOptions] = useState<VoteInfo[] | null>(null);
    const [voteIdDraft, setVoteIdDraft] = useState<number | ''>('');
    // Phase 4X-B — 작전 글에 연결할 진행 중 작전(원천 /api/operations, kind=operation 일 때만 읽는다).
    const [operationOptions, setOperationOptions] = useState<{ id: number; title: string; statusLabel: string }[] | null>(null);
    const [operationIdDraft, setOperationIdDraft] = useState<number | ''>('');
    const [commentDrafts, setCommentDrafts] = useState<Record<number, string>>({});
    const [modal, setModal] = useState<BoardModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);
    // 기밀실 열람 기록 — 세션당 글마다 한 번만 인테이크한다.
    const readRequested = useRef<Set<number>>(new Set());

    // background=true는 턴 갱신용 — 읽던 글 목록과 작성 중인 댓글 draft가 로딩 화면으로
    // 바뀌지 않게 하고, 일시적 실패로 목록을 지우지도 않는다.
    const fetchBoard = useCallback(async (isSecret: boolean, background = false) => {
        if (!background) setLoading(true);
        try {
            const d = await api.board(isSecret);
            setData(d);
            setError('');
        } catch {
            if (!background) {
                setData(null);
                setError('게시판을 불러올 수 없습니다.');
            }
        } finally {
            if (!background) setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchBoard(secret);
    }, [fetchBoard, secret]);

    useEffect(() => {
        api.frontInfo()
            .then((info) => {
                setFront(info);
                setFrontGeneralId(info.general?.generalId ?? 0);
            })
            .catch(() => setFrontGeneralId(0));
    }, []);

    useTurnRefresh(() => fetchBoard(secret, true));

    const myGeneralId = data?.myGeneralId ?? frontGeneralId;
    const title = secret ? '기밀실' : '회의실';
    const articles: BoardArticle[] = useMemo(() => data?.articles ?? [], [data]);
    const blockedReason = data?.blockedReason ?? '';
    // 방이 읽을 수 있고 (권한 차단 아님) 자신의 id를 알고 있다 → write 표면이 활성화된다.
    const canWrite = !loading && !error && !blockedReason && myGeneralId !== 0;
    const myPermission = data?.myPermission ?? -1;
    const participants = data?.participants ?? [];
    const chiefCount = data?.chiefCount ?? 0;

    // 기밀실 글 열람 기록 — 아직 내 열람이 없는 글만, 세션당 한 번. 202 는 성공이 아니므로 결과를 기다린 뒤 재조회한다.
    useEffect(() => {
        if (!secret || !data || myGeneralId === 0 || blockedReason) return;
        const unread = articles.filter(
            (a) => a.readers && !a.readers.read.some((p) => p.generalId === myGeneralId) && !readRequested.current.has(a.id),
        );
        if (unread.length === 0) return;
        unread.forEach((a) => readRequested.current.add(a.id));
        let alive = true;
        (async () => {
            let applied = false;
            for (const a of unread) {
                try {
                    const out = await submitCommandAndAwaitResult(() => api.command('boardRead', { articleNo: a.id }, myGeneralId));
                    if (out.status === 'applied') applied = true;
                } catch {
                    /* 열람 기록 실패는 화면을 막지 않는다 — 다음 방문에 다시 시도한다. */
                    readRequested.current.delete(a.id);
                }
            }
            if (alive && applied) fetchBoard(secret, true);
        })();
        return () => { alive = false; };
    }, [articles, blockedReason, data, fetchBoard, myGeneralId, secret]);

    // 표결 글을 쓸 때만 설문 목록을 읽는다(원천: /api/votes).
    useEffect(() => {
        if (articleKind !== 'vote' || voteOptions !== null) return;
        let alive = true;
        api.votes()
            .then((res) => { if (alive) setVoteOptions(Object.values(res.votes ?? {})); })
            .catch(() => { if (alive) setVoteOptions([]); });
        return () => { alive = false; };
    }, [articleKind, voteOptions]);

    const setCommentDraft = useCallback((no: number, value: string) => {
        setCommentDrafts((prev) => ({ ...prev, [no]: value }));
    }, []);

    const counts = useMemo(() => {
        const c: Record<KindTab, number> = { all: articles.length, general: 0, vote: 0, operation: 0, notice: 0 };
        for (const a of articles) c[kindOf(a)] += 1;
        return c;
    }, [articles]);
    const visible = kindTab === 'all' ? articles : articles.filter((a) => kindOf(a) === kindTab);
    const activeCount = participants.filter((p) => p.active).length;
    const chiefs = participants.filter((p) => p.chief);
    const nation = front?.nation ?? null;
    const myRank = front?.general?.officerLevelText ?? null;
    useEffect(() => {
        if (articleKind !== 'operation' || operationOptions !== null) return;
        Promise.resolve()
            .then(() => api.operations<OperationsResponse>())
            .then((r) => setOperationOptions(r.operations.filter((o) => o.status === 'declared' || o.status === 'active').map((o) => ({ id: o.id, title: o.title, statusLabel: o.statusLabel }))))
            .catch(() => setOperationOptions([]));
    }, [articleKind, operationOptions]);
    const canSubmitArticle = !(articleTitle.length === 0 && isArticleBodyBlank(articleText)) && (articleKind !== 'vote' || voteIdDraft !== '');

    return (
        <>
            <PageHead
                title={title}
                chip={nation?.name ?? undefined}
                tabs={
                    <PillTabs<'open' | 'secret'>
                        label="게시 공간"
                        value={secret ? 'secret' : 'open'}
                        onChange={(key) => setSecret(key === 'secret')}
                        tabs={[{ key: 'open', label: '회의실' }, { key: 'secret', label: '기밀실', count: chiefCount || undefined }]}
                    />
                }
                actions={
                    <>
                        <button type="button" className="os-button os-button--sm os-button--ghost" onClick={() => fetchBoard(secret)}>새로고침</button>
                        <a className="os-button os-button--sm os-button--ghost" href={COMMUNITY_HREF}>커뮤니티는 서버 밖 ↗</a>
                    </>
                }
            />
            {/* 방 머리 — 깃발·국가·방 이름·구성원·내 직책 (14 아트보드). 국가색은 깃발에만. */}
            <section className={`council-head${secret ? ' council-head--secret' : ''}`} aria-label={`${title} 개요`}>
                {nation?.color && <Flag color={nation.color} size={22} label={`${nation.name} 깃발`} />}
                <div className="council-head__text">
                    <div className="council-head__name">{nation ? `${nation.name} · ${title}` : title}</div>
                    <div className="council-head__meta">
                        {secret
                            ? <span>수뇌부 {chiefCount}명 · 열람 기록 남음</span>
                            : <span>국가 소속 장수 {participants.length}명 · 게임 안</span>}
                        {myRank && <span>내 직책 · {myRank}</span>}
                    </div>
                </div>
                {secret && <Chip tone="rust">수뇌부 전용</Chip>}
            </section>

            <div className="council-grid">
                <div className="council-main">
                    {/* 글쓰기 (boardArticle) — title + text + kind(+voteId) → extraArgs; isSecret = 현재 방.
                        legacy PageBoard.vue `#newArticle`: 헤더 '새 게시물 작성', 제목/내용 라벨, title maxlength=250,
                        placeholder '제목'/'내용', 등록 버튼. 제출 가드 = legacy 의 title/body 공동 empty check. */}
                    {canWrite && (
                        <Panel className="record-panel council-write">
                            <SectionHeader title="새 게시물 작성" sub={KIND_LABEL[articleKind]} />
                            <div className="council-write__form">
                                <div className="council-write__row">
                                    <label className="council-write__field">
                                        종류
                                        <select value={articleKind} onChange={(e) => setArticleKind(e.target.value as BoardKind)}>
                                            <option value="general">일반</option>
                                            <option value="vote">표결</option>
                                            <option value="operation">작전</option>
                                            <option value="notice" disabled={myPermission < 2}>공지{myPermission < 2 ? ' (수뇌부만)' : ''}</option>
                                        </select>
                                    </label>
                                    {articleKind === 'operation' && (
                                        <label className="council-write__field">
                                            연결 작전
                                            <select value={operationIdDraft} onChange={(e) => setOperationIdDraft(e.target.value === '' ? '' : Number(e.target.value))}>
                                                <option value="">{operationOptions === null ? '불러오는 중...' : operationOptions.length === 0 ? '진행 중인 작전이 없습니다 (연결 없이 작성)' : '연결 없음'}</option>
                                                {(operationOptions ?? []).map((o) => (
                                                    <option key={o.id} value={o.id}>{o.title} · {o.statusLabel}</option>
                                                ))}
                                            </select>
                                        </label>
                                    )}
                                    {articleKind === 'vote' && (
                                        <label className="council-write__field">
                                            연결할 설문
                                            <select value={voteIdDraft} onChange={(e) => setVoteIdDraft(e.target.value === '' ? '' : Number(e.target.value))}>
                                                <option value="">{voteOptions === null ? '불러오는 중...' : voteOptions.length === 0 ? '진행 중인 설문이 없습니다' : '선택'}</option>
                                                {(voteOptions ?? []).map((v) => (
                                                    <option key={v.id} value={v.id}>{v.title}</option>
                                                ))}
                                            </select>
                                        </label>
                                    )}
                                </div>
                                <input
                                    type="text"
                                    placeholder="제목"
                                    value={articleTitle}
                                    onChange={(e) => setArticleTitle(e.target.value)}
                                    maxLength={250}
                                />
                                <RichTextEditor
                                    ariaLabel="내용"
                                    maxTextLength={65_535}
                                    value={articleText}
                                    onChange={setArticleText}
                                />
                                <div className="council-write__actions">
                                    <button
                                        type="button"
                                        className="os-button os-button--primary"
                                        disabled={!canSubmitArticle}
                                        onClick={() =>
                                            setModal({
                                                command: 'boardArticle',
                                                label: secret ? '기밀실 글쓰기' : '회의실 글쓰기',
                                                // isSecret = 현재 방(legacy PageBoard isSecretBoard prop). 엔진이 모든 guard 재검증.
                                                extraArgs: {
                                                    isSecret: secret,
                                                    title: articleTitle,
                                                    text: articleText,
                                                    kind: articleKind,
                                                    ...(articleKind === 'vote' && voteIdDraft !== '' ? { voteId: voteIdDraft } : {}),
                                                    ...(articleKind === 'operation' && operationIdDraft !== '' ? { operationId: operationIdDraft } : {}),
                                                },
                                            })
                                        }
                                    >
                                        등록
                                    </button>
                                </div>
                            </div>
                        </Panel>
                    )}

                    <PillTabs<KindTab>
                        label="글 종류"
                        className="council-kinds"
                        value={kindTab}
                        onChange={setKindTab}
                        tabs={KIND_TABS.map((t) => ({ key: t.key, label: t.label, count: counts[t.key] }))}
                    />

                    {loading && <p className="text-muted">로딩 중...</p>}
                    {error && <p role="alert" style={{ color: 'var(--rust)' }}>{error}</p>}
                    {/* 권한 / 차단 사유 — INFO, read endpoint에서 그대로 */}
                    {!loading && !error && blockedReason && (
                        <Panel className="record-panel" frame="rust">
                            <p className="record-empty">{blockedReason}</p>
                        </Panel>
                    )}
                    {/* article 목록 */}
                    {!loading && !error && !blockedReason && (
                        visible.length > 0 ? (
                            <div className="council-list">
                                {visible.map((a) => (
                                    <ArticleCard
                                        key={a.id}
                                        article={a}
                                        secret={secret}
                                        canComment={canWrite}
                                        canVote={canWrite}
                                        commentDraft={commentDrafts[a.id] ?? ''}
                                        setCommentDraft={setCommentDraft}
                                        openModal={setModal}
                                    />
                                ))}
                            </div>
                        ) : (
                            <Panel className="record-panel">
                                <EmptyState illustration="posts" title="게시물이 없습니다." />
                            </Panel>
                        )
                    )}
                </div>

                <aside className="council-rail" aria-label="회의실 참여">
                    {secret && (
                        <Panel className="record-panel" frame="rust">
                            <SectionHeader title="열람 기록" tone="rust" sub={`수뇌부 ${chiefCount}명`} />
                            {chiefs.length > 0 && (
                                <div className="council-rail__stack">
                                    <span className="council-rail__label">열람 가능</span>
                                    <PortraitStack label="열람 가능 수뇌부">
                                        {chiefs.map((p) => <Portrait key={p.generalId} picture={p.picture} imageServer={p.imageServer} size="icon-24" alt={p.name} title={p.name} />)}
                                    </PortraitStack>
                                </div>
                            )}
                            <p className="council-rail__notice">직책이 바뀌면 즉시 접근이 끊기고, 이전 열람 기록은 남습니다. URL 직접 입력으로 우회할 수 없습니다.</p>
                        </Panel>
                    )}
                    <Panel className="record-panel">
                        <SectionHeader title="회의실 참여" tone="info" sub="최근 순" />
                        {participants.length === 0 ? (
                            <p className="record-empty">{blockedReason || loading ? '—' : '소속 장수가 없습니다.'}</p>
                        ) : (
                            <div className="council-rail__stack">
                                <PortraitStack label="참여 장수">
                                    {participants.map((p) => (
                                        <Portrait
                                            key={p.generalId}
                                            picture={p.picture}
                                            imageServer={p.imageServer}
                                            size="icon-24"
                                            alt={p.name}
                                            title={`${p.name}${p.active ? ' · 활동' : ' · 침묵'}`}
                                            inactive={!p.active}
                                        />
                                    ))}
                                </PortraitStack>
                                <span className="council-rail__label os-num">활동 {activeCount} · 침묵 {participants.length - activeCount} · NPC 제외</span>
                            </div>
                        )}
                    </Panel>
                </aside>
            </div>

            {/* Board CommandModal (pinnedCommand + extraArgs; pinnedArgType=null — args는 extraArgs에 실린다). */}
            {modal && myGeneralId !== 0 && (
                <CommandModal
                    onClose={() => setModal(null)}
                    onToast={(msg) => setToast(msg)}
                    generalId={myGeneralId}
                    pinnedCommand={modal.command}
                    pinnedLabel={modal.label}
                    pinnedArgType={null}
                    extraArgs={modal.extraArgs}
                    onReserved={() => {
                        setArticleTitle('');
                        setArticleText('');
                        setVoteIdDraft('');
                        setCommentDrafts({});
                        fetchBoard(secret);
                    }}
                />
            )}
            {toast && (
                <div role="status" className="council-toast" onClick={() => setToast(null)}>
                    {toast}
                </div>
            )}
        </>
    );
}

export default function BoardPage() {
    return (
        <Shell>
            <Suspense fallback={<p className="text-muted">로딩 중...</p>}>
                <BoardContent />
            </Suspense>
        </Shell>
    );
}
