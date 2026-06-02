'use client';

// 설문 조사 (Vote) — F4 page 5. READ-ONLY this wave (no mutation wiring).
// Grand-truth: legacy hwe/ts/PageVote.vue + hwe/sammo/API/Vote/GetVoteList.php +
//   GetVoteDetail.php + hwe/ts/utilGame/formatVoteColor.ts.
//
// Consumes the F4 read methods api.votes() (list) + api.vote(id) (detail+results+myVote).
// List newest-first (legacy reverses Object.entries(votes)); clicking a row in the
// 이전 설문 조사 list reloads that vote's detail. The result table is read-only — the
// vote radio/checkbox + 투표 button + 댓글 input are rendered disabled (write deferred).
//
// Verbatim labels (PageVote.vue): 설문 조사 / 설문 제목 / 게시자 / [SYSTEM] /
//   (N개 선택 가능 ) / 명 / (N.N%) / 투표 / 결산 / 투표율 / 이전 설문 조사 /
//   comment headers # · 국가명 · 장수명 · 댓글 · 일시 / 댓글 달기.
// multipleOptions: ==1 단일선택(radio), ==0 모두선택 가능, else N개 선택 가능 (legacy logic).
// Option colors reproduce formatVoteColor.ts (red·orange·yellow·green·blue·navy·purple, cyclic).
//
// EMPTY-SAFE: votes {} → '설문 조사가 없습니다.' (no current vote, empty list, no crash);
//   detail with empty votes[] → distribution all 0, percentages render 0.0%.

import { useEffect, useState, useCallback } from 'react';
import Shell from '../../../components/Shell';
import GameCard from '../../../components/GameCard';
import { api } from '../../../lib/api';
import type {
    VoteListResponse,
    VoteDetailResponse,
    VoteInfo,
} from '../../../lib/types';

// formatVoteColor.ts: css-color-names for red·orange·yellow·green·blue·navy·purple, cyclic by index.
const VOTE_COLORS = ['#ff0000', '#ffa500', '#ffff00', '#008000', '#0000ff', '#000080', '#800080'];
function formatVoteColor(idx: number): string {
    return VOTE_COLORS[idx % VOTE_COLORS.length];
}

// legacy isBrightColor: perceived-luminance threshold (r*.299 + g*.587 + b*.114) > 140 → black text.
function isBrightColor(color: string): boolean {
    const m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(color.trim());
    if (!m) return false;
    const r = parseInt(m[1], 16);
    const g = parseInt(m[2], 16);
    const b = parseInt(m[3], 16);
    return r * 0.299 + g * 0.587 + b * 0.114 > 140;
}

// PageVote.vue header: multipleOptions !== 1 shows "(N개 선택 가능 )", N = options.length when 0 else N.
function selectableLabel(info: VoteInfo): string {
    if (info.multipleOptions === 1) return '';
    const n = info.multipleOptions === 0 ? info.options.length : info.multipleOptions;
    return `(${n}개 선택 가능 )`;
}

// legacy comment date: date.substring(5, 5 + 5 + 1 + 5) === substring(5, 16) → MM-DD HH:MM.
function shortDate(date: string): string {
    return (date ?? '').slice(5, 16);
}

export default function VotePage() {
    const [list, setList] = useState<VoteListResponse | null>(null);
    const [currentId, setCurrentId] = useState<number | null>(null);
    const [detail, setDetail] = useState<VoteDetailResponse | null>(null);
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string>('');

    const fetchList = useCallback(async () => {
        setLoading(true);
        try {
            const d = await api.votes();
            setList(d);
            setError('');
            // newest-first (legacy reverses entries); auto-select the first vote if none chosen yet.
            const ids = Object.keys(d.votes ?? {})
                .map(Number)
                .sort((a, b) => b - a);
            setCurrentId((prev) => (prev != null ? prev : ids.length > 0 ? ids[0] : null));
        } catch {
            setList(null);
            setError('설문 목록을 불러올 수 없습니다.');
        } finally {
            setLoading(false);
        }
    }, []);

    useEffect(() => {
        fetchList();
    }, [fetchList]);

    useEffect(() => {
        if (currentId == null) {
            setDetail(null);
            return;
        }
        let on = true;
        api.vote(currentId)
            .then((d) => {
                if (on) setDetail(d.result ? d : null);
            })
            .catch(() => {
                if (on) setDetail(null);
            });
        return () => {
            on = false;
        };
    }, [currentId]);

    useEffect(() => {
        const es = new EventSource('/api/game/sse/turn');
        es.addEventListener('turnCompleted', () => fetchList());
        es.onerror = () => es.close();
        return () => es.close();
    }, [fetchList]);

    const votesMap = list?.votes ?? {};
    // 이전 설문 조사 list — newest-first (legacy Object.entries(...).reverse()).
    const voteListEntries = Object.entries(votesMap)
        .map(([id, info]) => [Number(id), info] as [number, VoteInfo])
        .sort((a, b) => b[0] - a[0]);

    // result tally → per-option distribution + total (legacy watch(currentVote)).
    const info = detail?.voteInfo ?? null;
    const distribution: Record<number, number> = {};
    let total = 0;
    if (info) {
        for (let i = 0; i < info.options.length; i++) distribution[i] = 0;
        for (const [selection, count] of detail?.votes ?? []) {
            total += count;
            for (const optIdx of selection) {
                distribution[optIdx] = (distribution[optIdx] ?? 0) + count;
            }
        }
    }
    const userCnt = detail?.userCnt ?? 0;

    return (
        <Shell>
            <h1 style={{ fontSize: 'var(--text-2xl)', fontWeight: 700, marginBottom: 'var(--space-md)' }}>설문 조사</h1>

            <div
                className="control-bar"
                style={{ display: 'flex', gap: 'var(--space-md)', marginBottom: 'var(--space-md)', flexWrap: 'wrap', alignItems: 'center' }}
            >
                <button onClick={fetchList}>새로고침</button>
            </div>

            {loading && <p style={{ color: 'var(--text-muted)' }}>로딩 중...</p>}
            {error && <p style={{ color: 'var(--crimson)' }}>{error}</p>}

            {/* ── current vote: result + comments ─────────────────────────────── */}
            {!loading && !error && info && (
                <>
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table className="game-table" style={{ width: '100%' }}>
                                <thead>
                                    <tr>
                                        <th colSpan={3} style={{ textAlign: 'right' }}>설문 제목</th>
                                        <th>
                                            {info.title}
                                            {info.multipleOptions !== 1 && ` ${selectableLabel(info)}`}
                                        </th>
                                    </tr>
                                    <tr>
                                        <th colSpan={3} style={{ textAlign: 'right' }}>게시자</th>
                                        <th>{info.opener ?? '[SYSTEM]'}</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {info.options.map((option, idx) => {
                                        const count = distribution[idx] ?? 0;
                                        const percent = ((count / Math.max(1, total)) * 100).toFixed(1);
                                        const color = formatVoteColor(idx);
                                        return (
                                            <tr key={idx}>
                                                <td
                                                    style={{
                                                        textAlign: 'right',
                                                        backgroundColor: color,
                                                        color: isBrightColor(color) ? '#000' : '#fff',
                                                    }}
                                                >
                                                    {idx + 1}.
                                                </td>
                                                <td style={{ textAlign: 'right' }}>{count}명</td>
                                                <td style={{ textAlign: 'right' }}>({percent}%)</td>
                                                <td>{option}</td>
                                            </tr>
                                        );
                                    })}
                                    {info.options.length === 0 && (
                                        <tr>
                                            <td colSpan={4} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                                항목이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                                <tfoot>
                                    <tr>
                                        <td colSpan={3} style={{ textAlign: 'center' }}>결산</td>
                                        <td>
                                            투표율: {total} / {userCnt} ({' '}
                                            {((total / Math.max(1, userCnt)) * 100).toFixed(1)}%)
                                        </td>
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </GameCard>

                    {/* comments */}
                    <GameCard style={{ marginBottom: 'var(--space-lg)' }}>
                        <div style={{ overflowX: 'auto' }}>
                            <table className="game-table" style={{ width: '100%' }}>
                                <thead>
                                    <tr>
                                        <th style={{ width: '5ch', textAlign: 'right' }}>#</th>
                                        <th style={{ textAlign: 'center' }}>국가명</th>
                                        <th style={{ textAlign: 'center' }}>장수명</th>
                                        <th>댓글</th>
                                        <th style={{ textAlign: 'center' }}>일시</th>
                                    </tr>
                                </thead>
                                <tbody>
                                    {(detail?.comments ?? []).map((comment, idx) => (
                                        <tr key={comment.id ?? idx}>
                                            <td style={{ textAlign: 'right' }}>{idx + 1}.</td>
                                            <td style={{ textAlign: 'center' }}>{comment.nationName}</td>
                                            <td style={{ textAlign: 'center' }}>{comment.generalName}</td>
                                            <td style={{ whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{comment.text}</td>
                                            <td style={{ textAlign: 'center', fontSize: 'var(--text-xs)' }}>
                                                {shortDate(comment.date)}
                                            </td>
                                        </tr>
                                    ))}
                                    {(detail?.comments?.length ?? 0) === 0 && (
                                        <tr>
                                            <td colSpan={5} style={{ textAlign: 'center', color: 'var(--text-muted)' }}>
                                                댓글이 없습니다.
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                                <tfoot>
                                    <tr>
                                        <td></td>
                                        <td colSpan={2}>
                                            <button disabled style={{ width: '100%' }}>댓글 달기</button>
                                        </td>
                                        <td colSpan={2}>
                                            <input type="text" disabled placeholder="새 댓글 내용" style={{ width: '100%' }} />
                                        </td>
                                    </tr>
                                </tfoot>
                            </table>
                        </div>
                    </GameCard>
                </>
            )}

            {/* ── 이전 설문 조사 list ──────────────────────────────────────────── */}
            {!loading && !error && (
                <>
                    <div
                        className="section-title"
                        style={{ background: 'var(--bg2, #2a2a2a)', color: 'var(--text-primary)', textAlign: 'center', fontSize: 'var(--text-lg)', fontWeight: 600, padding: 'var(--space-xs) var(--space-sm)', marginBottom: 'var(--space-sm)' }}
                    >
                        이전 설문 조사
                    </div>
                    <GameCard>
                        {voteListEntries.length > 0 ? (
                            <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-xs)' }}>
                                {voteListEntries.map(([id, vInfo]) => (
                                    <div key={id}>
                                        <button
                                            onClick={() => setCurrentId(id)}
                                            style={{
                                                background: 'none',
                                                border: 'none',
                                                padding: 0,
                                                color: id === currentId ? 'var(--gold)' : 'var(--jade)',
                                                cursor: 'pointer',
                                                textAlign: 'left',
                                            }}
                                        >
                                            {vInfo.title}
                                        </button>
                                        <span style={{ color: 'var(--text-muted)', fontSize: 'var(--text-sm)' }}>
                                            {' '}({vInfo.startDate})
                                        </span>
                                    </div>
                                ))}
                            </div>
                        ) : (
                            <p style={{ color: 'var(--text-muted)', textAlign: 'center', margin: 0 }}>
                                설문 조사가 없습니다.
                            </p>
                        )}
                    </GameCard>
                </>
            )}
        </Shell>
    );
}
