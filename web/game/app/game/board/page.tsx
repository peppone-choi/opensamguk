'use client';

// 회의실 / 기밀실 (Board) — F4 page 4. READ-ONLY this wave (no mutation wiring).
// Grand-truth: legacy hwe/ts/PageBoard.vue + components/BoardArticle.vue +
//   components/BoardComment.vue + hwe/j_board_get_articles.php.
//
// One page, two rooms toggled in-page (legacy instantiates PageBoard with an
// isSecretBoard prop; the secret room is the 수뇌부-only 기밀실). The toggle drives
// api.board(secret) → GET /api/board?secret=. Verbatim titles 회의실 / 기밀실.
//
// Article card (BoardArticle.vue): header row [작성자 | 본문 일부는 아래 | 날짜],
//   body row [장수 아이콘(생략) | 본문], comment list, 댓글 달기 footer (read-only this
//   wave → the comment input is rendered disabled, mirroring the legacy chrome).
//   Date is rendered as legacy `date.slice(5, 16)` (MM-DD HH:MM).
// Comment row (BoardComment.vue): [작성자 | 본문 | 날짜 slice(5,16)].
//
// Permission gates render as INFO (not error), per locked rule 4. The read endpoint
// returns result=false + a verbatim reason when blocked:
//   '국가에 소속되어있지 않습니다.' (permission < 0)
//   '권한이 부족합니다. 수뇌부가 아닙니다.' (secret room, permission < 2)
//   '접속 제한입니다.' (refresh-limit) — surfaced verbatim if the backend sends it.
//
// EMPTY-SAFE: articles [] → '게시물이 없습니다.' (verbatim legacy empty state).
// Never crashes on an empty/zeroed fresh-seed response.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import StatusBadge from '../../../components/StatusBadge';
import { api } from '../../../lib/api';
import type { BoardResponse, BoardArticle, BoardComment } from '../../../lib/types';

// legacy BoardArticle.vue / BoardComment.vue render `date.slice(5, 16)` → MM-DD HH:MM.
function shortDate(date: string): string {
    return (date ?? '').slice(5, 16);
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
            <div style={{ textAlign: 'center', color: 'var(--text-secondary)' }}>{comment.generalName}</div>
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{comment.text}</div>
            <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: 'var(--text-xs)' }}>
                {shortDate(comment.date)}
            </div>
        </div>
    );
}

function ArticleCard({ article }: { article: BoardArticle }) {
    return (
        <GameCard style={{ marginBottom: 'var(--space-md)' }}>
            {/* header: 작성자 / 날짜 (legacy header row) */}
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
                <span style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{article.generalName}</span>
                <span style={{ fontSize: 'var(--text-xs)', color: 'var(--text-muted)' }}>
                    {shortDate(article.date)}
                </span>
            </div>

            {/* body */}
            <div style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word', marginBottom: 'var(--space-sm)' }}>
                {article.text}
            </div>

            {/* comments */}
            {article.comment.length > 0 && (
                <div style={{ marginTop: 'var(--space-sm)' }}>
                    {article.comment.map((c) => (
                        <CommentRow key={c.no} comment={c} />
                    ))}
                </div>
            )}

            {/* 댓글 달기 footer — read-only this wave (write deferred to a later F4 mutation wave) */}
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
                    disabled
                    style={{ flex: 1 }}
                    maxLength={250}
                />
                <button disabled>등록</button>
            </div>
        </GameCard>
    );
}

export default function BoardPage() {
    // false = 회의실, true = 기밀실 (legacy isSecretBoard). In-page toggle drives api.board(secret).
    const [secret, setSecret] = useState(false);
    const [data, setData] = useState<BoardResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

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

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchBoard(secret));
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchBoard, secret]);

    const title = secret ? '기밀실' : '회의실';
    const articles: BoardArticle[] = data?.articles ?? [];
    // result=false → render the verbatim deny reason as INFO (not error), per locked rule 4.
    const blockedReason = data && !data.result ? data.reason : '';

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>{title}</h1>

            {/* 회의실 / 기밀실 toggle */}
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

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* permission / blocked reason — INFO, verbatim from the read endpoint */}
            {!loading && !error && blockedReason && (
                <GameCard>
                    <p style={{ color: 'var(--text-secondary)', textAlign: 'center', margin: 0 }}>{blockedReason}</p>
                </GameCard>
            )}

            {/* article list */}
            {!loading && !error && !blockedReason && (
                <>
                    {articles.length > 0 ? (
                        <div>
                            {articles.map((a) => (
                                <ArticleCard key={a.no} article={a} />
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
        </Shell>
    );
}
