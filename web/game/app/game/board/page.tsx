'use client';

// 회의실 / 기밀실 (Board) — F4 page 4. READ + MUTATION (F4 Wave C2 slice C).
// 그랜드 트루스: legacy hwe/ts/PageBoard.vue + components/BoardArticle.vue +
//   components/BoardComment.vue + hwe/j_board_get_articles.php + j_board_article_add.php +
//   j_board_comment_add.php.
//
// 한 페이지, 두 개의 방을 페이지 내에서 토글한다 (legacy는 PageBoard를 isSecretBoard
// prop으로 인스턴스화한다; 기밀 방은 수뇌부 전용 기밀실이다). 토글은
// api.board(secret) → GET /api/board?secret= 를 구동한다. 제목은 그대로 회의실 / 기밀실.
//
// Mutation(slice C)은 기존 CommandModal을 통해 배선된다 (pinnedCommand + extraArgs,
// slice-A/B 패턴); 두 board 작업 모두 서브 폼이 없으며 (pinnedArgType=null) — args는 extraArgs에 실린다:
//  - 글쓰기 (boardArticle): { isSecret: <room>, title, text }. isSecret이 방(회의실/기밀실)을 고른다.
//  - 댓글 (boardComment): { articleNo: article.id, text }. is_secret은 조회된 article로부터
//    서버 측에서 도출된다 (전송하지 않음). 엔진 BoardHandler가 모든 guard를 재검증한다.
// board read DTO에는 myGeneralId가 없으므로, 호출자 자신의 id를 api.frontInfo()
// (front-info.general.generalId)에서 가져온다 — CommandModal의 controller contract가 인용하는 것과 동일한 소스.
//
// Article 카드 (BoardArticle.vue): 헤더 행 [작성자 | 날짜], 본문 [본문], 댓글 목록, 댓글 달기 footer.
//   날짜는 legacy `date.slice(5, 16)` (MM-DD HH:MM)로 렌더된다.
// Comment 행 (BoardComment.vue): [작성자 | 본문 | 날짜 slice(5,16)].
//
// 권한 gate는 INFO로 렌더된다 (error 아님), 고정 규칙 4에 따라. read endpoint는 result=true를 유지하고
// 기밀 방 차단을 `blockedReason`으로 신호한다; write 경로는 엔진의 그대로의
// PHP deny 문자열을 CommandModal info 채널을 통해 노출한다:
//   '국가에 소속되어있지 않습니다.' (permission < 0)
//   '권한이 부족합니다. 수뇌부가 아닙니다.' (기밀 방, permission < 2)
//   '게시물이 없습니다.' (없는 article에 댓글)  /  '내용이 비어있습니다.' (빈 댓글)
//
// EMPTY-SAFE: articles [] → '게시물이 없습니다.' (그대로의 legacy empty 상태).

import { Suspense, useCallback, useEffect, useState } from 'react';
import { useSearchParams } from 'next/navigation';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import CommandModal from '../../../components/CommandModal';
import { RichTextEditor } from '../../../components/RichTextEditor';
import { SafeHtml } from '../../../components/SafeHtml';
import { api } from '../../../lib/api';
import type { BoardResponse, BoardArticle, BoardComment } from '../../../lib/types';
import { isArticleBodyBlank } from './articleBody';

// 하나의 열린 board CommandModal spec. argType은 항상 null (args는 extraArgs에 실린다).
type BoardModalSpec = { command: string; label: string; extraArgs?: Record<string, unknown> };

// legacy BoardArticle.vue / BoardComment.vue는 `date.slice(5, 16)` → MM-DD HH:MM 로 렌더한다.
// game-api는 ISO instant (…T…)를 내보내므로, MM-DD HH:MM 외관을 위해 'T' 구분자를 공백으로 표시한다.
function shortDate(date: string): string {
    return (date ?? '').slice(5, 16).replace('T', ' ');
}

function CommentRow({ comment }: { comment: BoardComment }) {
    return (
        <div
            style={{
                display: 'grid',
                gridTemplateColumns: 'minmax(8ch, 14ch) 1fr minmax(9ch, 12ch)',
                gap: 'var(--space-sm)',
                alignItems: 'center',
                padding: 'var(--space-xs) 0',
                borderBottom: '1px solid var(--border, rgba(255,255,255,0.08))',
                fontSize: 'var(--text-sm)',
            }}
        >
            <div style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>{comment.authorName}</div>
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
                <SafeHtml html={comment.text} />
            </div>
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>
                {shortDate(comment.date)}
            </div>
        </div>
    );
}

function ArticleCard({
    article,
    canComment,
    commentDraft,
    setCommentDraft,
    openModal,
}: {
    article: BoardArticle;
    canComment: boolean;
    commentDraft: string;
    setCommentDraft: (no: number, value: string) => void;
    openModal: (spec: BoardModalSpec) => void;
}) {
    return (
        <GameCard style={{ marginBottom: 'var(--space-md)' }}>
            {/* BLOCKED(백로그): author_icon — legacy BoardArticle.vue는 본문 행에 64px <img class="generalIcon">
               (article.author_icon)를 렌더하지만, 현 스키마(board 테이블)부터 read DTO·이 컴포넌트까지 전구간
               author_icon 필드가 부재(parityViolation MEDIUM). 복원 = 마이그레이션 + DTO + FE 일괄 필요 → 백로그.
               댓글에는 author_icon 없음이 정상(article만). */}
            {/* 헤더: 작성자 / 날짜 (legacy 헤더 행) */}
            <div
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    gap: 'var(--space-sm)',
                    paddingBottom: 'var(--space-xs)',
                    marginBottom: 'var(--space-sm)',
                    borderBottom: '1px solid var(--border, rgba(255,255,255,0.08))',
                }}
            >
                <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{article.authorName}</span>
                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                    {shortDate(article.date)}
                </span>
            </div>

            {article.title && (
                <div style={{ fontWeight: 600, marginBottom: 'var(--space-xs)' }}>{article.title}</div>
            )}
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', marginBottom: 'var(--space-sm)' }}>
                <SafeHtml html={article.contentHtml} />
            </div>

            {/* 댓글 */}
            {(article.comments?.length ?? 0) > 0 && (
                <div style={{ marginTop: 'var(--space-sm)' }}>
                    {(article.comments ?? []).map((c) => (
                        <CommentRow key={c.id} comment={c} />
                    ))}
                </div>
            )}

            {/* 댓글 달기 footer (boardComment) — text는 input을 통해 → extraArgs.text, articleNo = article.id. */}
            {canComment && (
                <div style={{ display: 'flex', gap: 'var(--space-sm)', alignItems: 'center', marginTop: 'var(--space-sm)' }}>
                    <span
                        style={{
                            flexBasis: '7ch',
                            textAlign: 'center',
                            fontSize: 'var(--text-sm)',
                            color: 'var(--text-secondary)',
                        }}
                    >
                        댓글 달기
                    </span>
                    <input
                        type="text"
                        placeholder="새 댓글 내용"
                        value={commentDraft}
                        onChange={(e) => setCommentDraft(article.id, e.target.value)}
                        style={{ flex: 1 }}
                        maxLength={250}
                    />
                    <button
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
        </GameCard>
    );
}

function BoardContent() {
    const searchParams = useSearchParams();
    // false = 회의실, true = 기밀실 (legacy isSecretBoard). 페이지 내 토글이 api.board(secret)를 구동한다.
    const [secret, setSecret] = useState(() => searchParams.get('secret') === '1');
    const [data, setData] = useState<BoardResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    // board read DTO에는 myGeneralId가 없다 — front-info에서 가져온다 (CommandModal이 필요로 한다).
    const [myGeneralId, setMyGeneralId] = useState(0);
    // 글쓰기 draft + article별 댓글 draft (article.id로 keying). reserve 성공 시 비워진다.
    const [articleTitle, setArticleTitle] = useState('');
    const [articleText, setArticleText] = useState('');
    const [commentDrafts, setCommentDrafts] = useState<Record<number, string>>({});
    const [modal, setModal] = useState<BoardModalSpec | null>(null);
    const [toast, setToast] = useState<string | null>(null);

    const fetchBoard = useCallback(async (isSecret: boolean) => {
        setLoading(true);
        try {
            const d = await api.board(isSecret);
            setData(d);
            setError('');
        } catch {
            setData(null);
            setError('게시판을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchBoard(secret);
    }, [fetchBoard, secret]);

    // 행위 주체 장수 자신의 id (front-info.general.generalId), write 경로를 위해 한 번 가져온다.
    useEffect(() => {
        api.frontInfo()
            .then((info) => setMyGeneralId(info.general?.generalId ?? 0))
            .catch(() => setMyGeneralId(0));
    }, []);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchBoard(secret));
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchBoard, secret]);

    const setCommentDraft = useCallback((no: number, value: string) => {
        setCommentDrafts((prev) => ({ ...prev, [no]: value }));
    }, []);

    const title = secret ? '기밀실' : '회의실';
    const articles: BoardArticle[] = data?.articles ?? [];
    // 백엔드는 result=true를 유지하고 기밀 방 권한 차단을 `blockedReason`으로 신호한다
    // (INFO로 렌더, error 아님, 고정 규칙 4에 따라). canWrite도 이 동일한 필드로 gate한다.
    const blockedReason = data?.blockedReason ?? '';
    // 방이 읽을 수 있고 (권한 차단 아님) 자신의 id를 알고 있다 → write 표면이 활성화된다.
    const canWrite = !loading && !error && !blockedReason && myGeneralId !== 0;

    return (
        <>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>{title}</h1>

            {/* 회의실 / 기밀실 토글 */}
            <div
                className="control-bar"
                style={{ display: 'flex', gap: 'var(--space-sm)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}
            >
                <button
                    onClick={() => setSecret(false)}
                    aria-pressed={!secret}
                    style={!secret ? { background: 'var(--gold)', color: '#000' } : undefined}
                >
                    회의실
                </button>
                <button
                    onClick={() => setSecret(true)}
                    aria-pressed={secret}
                    style={secret ? { background: 'var(--gold)', color: '#000' } : undefined}
                >
                    기밀실
                </button>
                <button onClick={() => fetchBoard(secret)}>새로고침</button>
                {secret && <StatusBadge variant="crimson">수뇌부 전용</StatusBadge>}
            </div>

            {/* 글쓰기 (boardArticle) — title + text → extraArgs; isSecret = 현재 방.
                legacy PageBoard.vue `#newArticle`: 헤더 '새 게시물 작성', 제목/내용 라벨,
                title maxlength=250, placeholder '제목'/'내용', 등록 버튼. isSecret은 방(prop)에서 결정.
                제출 가드 = legacy의 title/body 공동 empty check. Rich editor는 시각적 빈 본문을 HTML로
                직렬화하므로, page-local predicate가 tag-only/HTML-space 본문을 empty로 되돌린다.
                데몬은 계속 최종 trim/권한/결과를 소유한다. */}
            {canWrite && (
                <GameCard style={{ marginBottom: 'var(--space-md)' }}>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                        <strong style={{ fontSize: 'var(--text-sm)' }}>새 게시물 작성</strong>
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
                        <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
                            <button
                                disabled={articleTitle.length === 0 && isArticleBodyBlank(articleText)}
                                onClick={() =>
                                    setModal({
                                        command: 'boardArticle',
                                        label: secret ? '기밀실 글쓰기' : '회의실 글쓰기',
                                        // isSecret = 현재 방(legacy PageBoard isSecretBoard prop). 엔진이 모든 guard 재검증.
                                        extraArgs: { isSecret: secret, title: articleTitle, text: articleText },
                                    })
                                }
                            >
                                등록
                            </button>
                        </div>
                    </div>
                </GameCard>
            )}

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* 권한 / 차단 사유 — INFO, read endpoint에서 그대로 */}
            {!loading && !error && blockedReason && (
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)', textAlign: 'center', margin: 0 }}>{blockedReason}</p>
                </GameCard>
            )}

            {/* article 목록 */}
            {!loading && !error && !blockedReason && (
                <>
                    {articles.length > 0 ? (
                        <div>
                            {articles.map((a) => (
                                <ArticleCard
                                    key={a.id}
                                    article={a}
                                    canComment={canWrite}
                                    commentDraft={commentDrafts[a.id] ?? ''}
                                    setCommentDraft={setCommentDraft}
                                    openModal={setModal}
                                />
                            ))}
                        </div>
                    ) : (
                        <GameCard>
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                                게시물이 없습니다.
                            </p>
                        </GameCard>
                    )}
                </>
            )}

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
                        setCommentDrafts({});
                        fetchBoard(secret);
                    }}
                />
            )}

            {toast && (
                <div
                    role="status"
                    style={{
                        position: 'fixed',
                        bottom: 16,
                        left: '50%',
                        transform: 'translateX(-50%)',
                        background: 'var(--surface-raised)',
                        padding: '8px 16px',
                        borderRadius: 8,
                    }}
                    onClick={() => setToast(null)}
                >
                    {toast}
                </div>
            )}
        </>
    );
}

export default function BoardPage() {
    return (
        <Shell>
            <Suspense fallback={<p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}>
                <BoardContent />
            </Suspense>
        </Shell>
    );
}
